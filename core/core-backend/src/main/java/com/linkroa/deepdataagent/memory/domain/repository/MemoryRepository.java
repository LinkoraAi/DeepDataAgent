package com.linkroa.deepdataagent.memory.domain.repository;

import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆条目与版本仓储接口（条目单表原语 + 不可变版本快照）。
 * <p>事务、锁行与统计编排由应用服务负责；本接口仅暴露进程内数据原语：
 * 条目以 {@code mem_} 业务ID寻址，版本以 {@code memver_} 业务ID寻址，
 * 更新走 OCC 条件写（期望版本不符 / 非活跃行返回 {@code false}）。</p>
 */
public interface MemoryRepository {

    /** 保存新条目并落 created 版本（并发同 path 首写由唯一约束抛 DuplicateKeyException） */
    Memory createEntry(Memory memory, MemoryVersion createdVersion);

    /** 按业务ID查询活跃条目 */
    Optional<Memory> findByMemoryId(String memoryId);

    /** 按业务ID查询活跃条目并锁定该行（FOR UPDATE） */
    Optional<Memory> findByMemoryIdForUpdate(String memoryId);

    /** 查询某记忆库下全部活跃条目（按 path 升序，不返回内容） */
    List<Memory> listByStoreId(String storeId);

    /** OCC 条件更新条目并追加版本快照；期望版本不符或已删除时返回 false */
    boolean updateEntry(Memory updated, int expectedVersion, MemoryVersion version);

    /** tombstone 软删条目并追加 deleted 墓碑版本；非活跃行时返回 false */
    boolean deleteEntry(String memoryId, MemoryVersion tombstoneVersion);

    /** 按业务ID查询版本 */
    Optional<MemoryVersion> findVersion(String versionId);

    /** 按业务ID查询版本并锁定该行（FOR UPDATE） */
    Optional<MemoryVersion> findVersionForUpdate(String versionId);

    /** 查询某条目的全部版本（按版本号降序） */
    List<MemoryVersion> listVersions(String entryId);

    /** 查询条目指定版本的快照（读当前内容用；已脱敏版本 content 为 null） */
    Optional<MemoryVersion> findVersionAt(String entryId, int version);

    /** 版本级脱敏（仅未脱敏行生效；已脱敏返回 false，幂等） */
    boolean redactVersion(String versionId, OffsetDateTime redactedAt);

    /** 硬删某记忆库下全部条目与版本（删除记忆库级联用） */
    void deleteByStoreId(String storeId);
}
