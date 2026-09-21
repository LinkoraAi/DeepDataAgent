package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.KnowledgeBaseRepository;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.convert.KnowledgeBasePersistenceConvert;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储的关系型实现（MyBatis-Plus）。
 */
@Repository
public class JdbcKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;

    public JdbcKnowledgeBaseRepository(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase kb) {
        KnowledgeBaseEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(kb);
        entity.setId(null);
        // 彻底物理删体系：audit 字段不再由 MetaObjectHandler 填充，插入前显式补齐（无操作人上下文回落 system）
        KbAuditFieldUtils.fillInsert(entity, null);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(kb);
    }

    @Override
    public KnowledgeBase update(KnowledgeBase kb) {
        return update(kb, null);
    }

    @Override
    public KnowledgeBase update(KnowledgeBase kb, String updatedBy) {
        KnowledgeBaseEntity entity = KnowledgeBasePersistenceConvert.INSTANCE.toEntity(kb);
        // 更新前显式刷新 updatedAt/updatedBy；操作人空白时由工具类回落系统缺省值
        KbAuditFieldUtils.fillUpdate(entity, updatedBy);
        mapper.updateById(entity);
        return findById(kb.id()).orElse(kb);
    }

    @Override
    public Optional<KnowledgeBase> findById(Long id) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<KnowledgeBase> findByIdForUpdate(Long id) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectByIdForUpdate(id)));
    }

    @Override
    public Optional<KnowledgeBase> findByName(String name) {
        return Optional.ofNullable(KnowledgeBasePersistenceConvert.INSTANCE.toDomain(mapper.selectByName(name)));
    }

    @Override
    public List<KnowledgeBase> findByCondition(String keyword, LifecycleStatus status, KnowledgeBaseSortField sortField,
                                               boolean ascending, int page, int size) {
        return mapper.selectByCondition(keyword, statusName(status), sortField, ascending, offset(page, size), size)
                .stream()
                .map(KnowledgeBasePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public long countByCondition(String keyword, LifecycleStatus status) {
        return mapper.countByCondition(keyword, statusName(status));
    }

    @Override
    public void deleteById(Long id) {
        // 彻底物理删体系：无 @TableLogic，deleteById 直接生成 DELETE FROM（行消失即已删除）
        mapper.deleteById(id);
    }

    @Override
    public long countByStatus(LifecycleStatus status) {
        return mapper.countByStatus(statusName(status));
    }

    @Override
    public List<Long> findIdsByLifecycle(LifecycleStatus status) {
        // 入参为空视为无命中：零 DB 交互
        if (ObjectUtils.isEmpty(status)) {
            return List.of();
        }
        return mapper.selectAllIdsByLifecycleStatus(status.name());
    }

    @Override
    public boolean transitLifecycle(Long kbId, LifecycleStatus fromLifecycle, LifecycleStatus toLifecycle) {
        // 入参非法视为未命中：CAS 语义下零 DB 交互，调用方按幂等空转处理
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(fromLifecycle) || ObjectUtils.isEmpty(toLifecycle)) {
            return false;
        }
        // 单条条件更新（不先查后写）：命中 1 行即迁移成功；已收口库行物理不存在，天然不可达
        return mapper.transitLifecycleStatus(kbId, fromLifecycle.name(), toLifecycle.name()) > 0;
    }

    @Override
    public boolean transitLifecycleClearingFailure(Long kbId, LifecycleStatus fromLifecycle,
                                                   LifecycleStatus toLifecycle) {
        // 入参非法视为未命中：CAS 语义下零 DB 交互，调用方按并发未命中处理
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(fromLifecycle) || ObjectUtils.isEmpty(toLifecycle)) {
            return false;
        }
        // 复用同一条带留痕列的条件 UPDATE，以 null 覆盖 error_message 完成「重删清痕」（不先查后写）
        return mapper.transitLifecycleStatusWithMessage(kbId, fromLifecycle.name(), toLifecycle.name(), null) > 0;
    }

    @Override
    public boolean executeDelete(Long kbId) {
        // 入参非法视为未命中：收口语义下零 DB 交互，调用方按已收口幂等处理
        if (ObjectUtils.isEmpty(kbId)) {
            return false;
        }
        // 收口 = 条件物理 DELETE（无 @TableLogic，delete(wrapper) 即 DELETE FROM）：
        // 仅删除处于可收口态 {DELETING, DELETE_FAILED} 的行，行消失即收口、同名释放；
        // 0 行命中＝已收口/状态不符，幂等空转；可收口态以枚举名下传，杜绝魔法值
        return mapper.delete(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getId, kbId)
                .in(KnowledgeBaseEntity::getLifecycleStatus,
                        LifecycleStatus.DELETING.name(), LifecycleStatus.DELETE_FAILED.name())) > 0;
    }

    @Override
    public boolean markFailed(Long kbId, String errorMessage) {
        // 入参非法视为未命中：CAS 语义下零 DB 交互，调用方按幂等空转处理
        if (ObjectUtils.isEmpty(kbId)) {
            return false;
        }
        // 单条条件 UPDATE（不先查后写）：DELETING → DELETE_FAILED 同语句写入留痕，非源态零行命中
        return mapper.transitLifecycleStatusWithMessage(kbId, LifecycleStatus.DELETING.name(),
                LifecycleStatus.DELETE_FAILED.name(), errorMessage) > 0;
    }

    private String statusName(LifecycleStatus status) {
        return ObjectUtils.isEmpty(status) ? null : status.name();
    }

    private long offset(int page, int size) {
        return (long) Math.max(0, page - 1) * size;
    }
}
