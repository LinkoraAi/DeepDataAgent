package com.linkroa.deepdataagent.memory.application.service;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.command.RedactMemoryVersionCommand;
import com.linkroa.deepdataagent.memory.application.command.UpdateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.query.ListMemoryStoreQuery;
import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryDetail;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryRepository;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 记忆库应用服务（Store → Memory(mem_) → MemoryVersion(memver_) 三阶用例编排）。
 * <p>Memory Store 语义：写操作统一以 {@link TransactionTemplate} 界定事务边界；
 * owner 隔离按当前认证用户收敛（非 owner → 404，不泄露存在性）。库级 status 控制读写：
 * active 可读写，archived 仅读（一切写入含条目创建 / 更新 / 删除 → 409）。
 * 条目更新走 OCC（请求携带版本号，与存储不符 → 409），内容上限 UTF-8 100KB（超限 400），
 * 统计列 entry_count / total_size 随条目创建 / 更新 / 删除同步维护；
 * 删除记忆库前经 {@link SessionReferenceApi} 校验会话引用（仍被引用 → 409，否则级联硬删）。</p>
 */
@Service
public class MemoryStoreApplicationService {

    /** 记忆库业务 ID 前缀（与 Flyway 注释 / 接口对齐） */
    private static final String STORE_ID_PREFIX = "ms_";

    @Resource
    private MemoryStoreRepository memoryStoreRepository;
    @Resource
    private MemoryRepository memoryRepository;
    @Resource
    private SessionReferenceApi sessionReferenceApi;
    @Resource
    private TransactionTemplate transactionTemplate;

    // ===== Store 用例 =====

    /**
     * 创建记忆库：生成唯一业务 ID（{@code ms_} 前缀），初始 status=active、统计归零。
     */
    public MemoryStore create(CreateMemoryStoreCommand command) {
        Long ownerId = AuthContext.requireUserId();
        MemoryStore store = MemoryStore.create(
                STORE_ID_PREFIX + UUID.randomUUID(),
                command.name(),
                command.description(),
                ownerId
        );
        return transactionTemplate.execute(status -> memoryStoreRepository.save(store));
    }

    /**
     * 分页列出当前 owner 的 active 记忆库（归档库不出现在列表）。
     */
    public List<MemoryStore> list(ListMemoryStoreQuery query) {
        Long ownerId = AuthContext.requireUserId();
        return memoryStoreRepository.findByPage(ownerId, query.page(), query.size());
    }

    /**
     * 统计当前 owner 的 active 记忆库总数（供分页 total）。
     */
    public long count() {
        Long ownerId = AuthContext.requireUserId();
        return memoryStoreRepository.countByOwnerId(ownerId);
    }

    /**
     * 读取记忆库详情：owner 隔离（非 owner → 404）；归档库仍可读取详情与内容。
     */
    public MemoryStore get(String storeId) {
        return requireOwned(storeId);
    }

    /**
     * 归档记忆库：置 status=archived 与 archived_at（幂等，已归档直接返回现状）。
     */
    public MemoryStore archive(String storeId) {
        return transactionTemplate.execute(status -> {
            MemoryStore store = requireOwnedForUpdate(storeId);
            if (store.archived()) {
                return store;
            }
            memoryStoreRepository.archive(store.storeId(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
            return requireOwned(storeId);
        });
    }

    /**
     * 硬删记忆库：仍被任一未删除 Session 挂载 → 409；否则级联删除其下全部条目与版本后删库。
     */
    public void delete(String storeId) {
        transactionTemplate.executeWithoutResult(status -> {
            MemoryStore store = requireOwnedForUpdate(storeId);
            long referencing = sessionReferenceApi.countSessionsByMemoryStoreId(store.storeId());
            if (referencing > 0) {
                throw new ResourceConflictException("记忆库仍被 " + referencing + " 个会话引用，无法删除");
            }
            memoryRepository.deleteByStoreId(store.storeId());
            memoryStoreRepository.deleteByStoreId(store.storeId());
        });
    }

    // ===== Memory 条目用例 =====

    /**
     * 创建记忆条目（mem_ 前缀、version=1）并落 created 版本，统计 entry_count/total_size 递增。
     * <p>内容超 100KB / path 非法 → 400（领域不变量）；同库 path 并发重复 → 唯一约束 409。</p>
     */
    public MemoryDetail createEntry(CreateMemoryEntryCommand command) {
        requireActiveOwned(command.storeId());
        Memory memory = Memory.create(
                Memory.MEMORY_ID_PREFIX + UUID.randomUUID(),
                command.storeId(), command.path(), command.content(), command.metadata());
        MemoryVersion created = MemoryVersion.created(
                MemoryVersion.VERSION_ID_PREFIX + UUID.randomUUID(),
                memory.storeId(), memory.memoryId(), memory.path(), memory.version(), command.content());
        Memory saved = transactionTemplate.execute(status -> {
            // 事务内锁行复检（首行）：消除「事务外 active 校验 → 归档并发提交」的 TOCTOU 窗口
            requireActiveOwnedForUpdate(command.storeId());
            Memory result = memoryRepository.createEntry(memory, created);
            memoryStoreRepository.adjustStats(command.storeId(), 1, memory.size());
            return result;
        });
        return new MemoryDetail(saved, command.content());
    }

    /**
     * OCC 更新记忆条目内容（version 递增、path 不可变）并追加 updated 版本，
     * total_size 按新旧字节差回退 / 递增。
     * <p>期望版本与存储不符 → 409（并发修改）；条目不存在 / 非本库 → 404。</p>
     */
    public MemoryDetail updateEntry(UpdateMemoryEntryCommand command) {
        requireActiveOwned(command.storeId());
        Memory next = transactionTemplate.execute(status -> {
            // 事务内锁行复检（首行）：归档并发提交后本事务读到 archived → 409 回滚，不发生条目写入
            requireActiveOwnedForUpdate(command.storeId());
            Memory current = memoryRepository.findByMemoryIdForUpdate(command.memoryId())
                    .filter(entry -> entry.storeId().equals(command.storeId()))
                    .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
            if (current.version() != command.expectedVersion()) {
                throw new ResourceConflictException(
                        "记忆版本冲突：期望 " + command.expectedVersion() + "，当前 " + current.version());
            }
            Memory updated = current.nextVersion(command.content(), command.metadata());
            MemoryVersion version = MemoryVersion.updated(
                    MemoryVersion.VERSION_ID_PREFIX + UUID.randomUUID(),
                    updated.storeId(), updated.memoryId(), updated.path(),
                    updated.version(), command.content());
            if (!memoryRepository.updateEntry(updated, command.expectedVersion(), version)) {
                throw new ResourceConflictException("记忆已被并发修改，请重试");
            }
            memoryStoreRepository.adjustStats(command.storeId(), 0, updated.size() - current.size());
            return updated;
        });
        return new MemoryDetail(next, command.content());
    }

    /**
     * 删除记忆条目（tombstone 软删）：落 deleted 墓碑版本，entry_count/total_size 回退。
     */
    public void deleteEntry(String storeId, String memoryId) {
        requireActiveOwned(storeId);
        transactionTemplate.executeWithoutResult(status -> {
            // 事务内锁行复检（首行）：归档并发提交后本事务读到 archived → 409 回滚，不落墓碑 / 不动统计
            requireActiveOwnedForUpdate(storeId);
            Memory current = memoryRepository.findByMemoryIdForUpdate(memoryId)
                    .filter(entry -> entry.storeId().equals(storeId))
                    .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
            MemoryVersion tombstone = MemoryVersion.tombstone(
                    MemoryVersion.VERSION_ID_PREFIX + UUID.randomUUID(),
                    storeId, memoryId, current.path(), current.version() + 1);
            if (!memoryRepository.deleteEntry(memoryId, tombstone)) {
                throw new ResourceNotFoundException("记忆不存在");
            }
            memoryStoreRepository.adjustStats(storeId, -1, -current.size());
        });
    }

    /**
     * 读取单条记忆（条目 + 头版本内容；头版本已脱敏时 content 为 null）。
     */
    public MemoryDetail getEntry(String storeId, String memoryId) {
        Memory memory = requireOwnedEntry(storeId, memoryId);
        String content = memoryRepository
                .findVersionAt(memoryId, memory.version())
                .map(MemoryVersion::content)
                .orElse(null);
        return new MemoryDetail(memory, content);
    }

    /**
     * 列出某记忆库下全部活跃条目（不含内容全文，按 path 升序）。
     */
    public List<Memory> listEntries(String storeId) {
        requireOwned(storeId);
        return memoryRepository.listByStoreId(storeId);
    }

    // ===== MemoryVersion 用例 =====

    /**
     * 列出某记忆条目的全部版本历史（按版本号降序；已脱敏版本 content 为 null）。
     */
    public List<MemoryVersion> listVersions(String storeId, String memoryId) {
        requireOwnedEntry(storeId, memoryId);
        return memoryRepository.listVersions(memoryId);
    }

    /**
     * 读取单个版本快照（memver_ 寻址；已脱敏版本 content 为 null）。
     */
    public MemoryVersion getVersion(String storeId, String versionId) {
        requireOwned(storeId);
        return memoryRepository.findVersion(versionId)
                .filter(version -> version.storeId().equals(storeId))
                .orElseThrow(() -> new ResourceNotFoundException("记忆版本不存在"));
    }

    /**
     * 版本级 redact：清除该版本内容与校验值（字节数保留），幂等。
     * <p>归档库亦允许（隐私清除语义），不受只读限制。</p>
     */
    public MemoryVersion redactVersion(RedactMemoryVersionCommand command) {
        requireOwned(command.storeId());
        return transactionTemplate.execute(status -> {
            MemoryVersion version = memoryRepository.findVersionForUpdate(command.versionId())
                    .filter(v -> v.storeId().equals(command.storeId()))
                    .orElseThrow(() -> new ResourceNotFoundException("记忆版本不存在"));
            if (version.redacted()) {
                return version;
            }
            memoryRepository.redactVersion(command.versionId(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
            return version.redact();
        });
    }

    // ===== 守卫方法 =====

    /**
     * owner 隔离：非 owner / 不存在 → 404（归档库可读取，不在此拦截）。
     */
    private MemoryStore requireOwned(String storeId) {
        MemoryStore store = memoryStoreRepository.findByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆库不存在"));
        if (!store.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("记忆库不存在");
        }
        return store;
    }

    /**
     * 写入守卫：owner 隔离 + 归档只读（已归档 → 409 拒绝一切条目写入）。
     */
    private MemoryStore requireActiveOwned(String storeId) {
        MemoryStore store = requireOwned(storeId);
        if (store.archived()) {
            throw new ResourceConflictException("记忆库已归档，禁止写入");
        }
        return store;
    }

    /**
     * 事务内写入守卫：{@code FOR UPDATE} 锁行读 + owner 隔离（非 owner → 404）+ 归档只读复检
     * （已归档 → 409）。
     * <p>条目写入事务（创建 / 更新 / 删除）第一行调用，消除「事务外 {@link #requireActiveOwned}
     * 快速失败 → 归档事务并发提交 → 写入仍落库」的 TOCTOU 窗口：归档经同一
     * {@code findByStoreIdForUpdate} 锁行，两事务在库行上互斥——归档先提交时写入事务锁行后
     * 读到 archived → 409，条目 / 版本 / 统计变更随事务整体回滚；写入先提交时归档随后锁行
     * 生效，已写数据保留（归档仅冻结后续写入）。</p>
     */
    private MemoryStore requireActiveOwnedForUpdate(String storeId) {
        // 锁行 owner 隔离复用 requireOwnedForUpdate（唯一一份 FOR UPDATE + owner 404 逻辑），
        // 本守卫仅追加归档只读复检
        MemoryStore store = requireOwnedForUpdate(storeId);
        if (store.archived()) {
            throw new ResourceConflictException("记忆库已归档，禁止写入");
        }
        return store;
    }

    /**
     * 锁行版本的 owner 隔离（归档 / 删除用，避免 check-then-act 竞态）。
     */
    private MemoryStore requireOwnedForUpdate(String storeId) {
        MemoryStore store = memoryStoreRepository.findByStoreIdForUpdate(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆库不存在"));
        if (!store.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("记忆库不存在");
        }
        return store;
    }

    /**
     * 条目归属校验：条目存在且属于指定记忆库（否则 404，不泄露跨库存在性）。
     */
    private Memory requireOwnedEntry(String storeId, String memoryId) {
        requireOwned(storeId);
        return memoryRepository.findByMemoryId(memoryId)
                .filter(entry -> entry.storeId().equals(storeId))
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
    }
}
