package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.AgentSessionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储实现（MyBatis-Plus）。
 * <p>基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由
 * {@code MybatisPlusMetaObjectHandler} 自动填充；同一会话同时只有一个执行的互斥由
 * {@code transition(sessionId, Transition.BEGIN_TURN)} 原子 CAS 保证；状态迁移按迁移声明
 * 选择性原子更新 status / turn_phase（小写规范值，见 {@code AgentSessionStatus} / {@code TurnPhase}）；
 * 归档只写 archived_at（{@code archive}，status 保持原值）。</p>
 */
@Repository
public class JdbcAgentSessionRepository implements AgentSessionRepository {

    @Resource
    private AgentSessionMapper mapper;

    @Override
    public AgentSession save(AgentSession session) {
        AgentSessionEntity entity = RuntimePersistenceConvert.INSTANCE.toEntity(session);
        if (entity.getId() == null) {
            entity.setId(null);
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return findBySessionId(session.sessionId()).orElse(session);
    }

    @Override
    public Optional<AgentSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(RuntimePersistenceConvert.INSTANCE.toDomain(mapper.findBySessionId(sessionId)));
    }

    @Override
    public List<AgentSession> findByCursor(String userId, SessionListFilter filter, int limit) {
        // 游标行位点（created_at + 数据库主键 id）由应用层解析 after_id/before_id 后装配
        return mapper.selectByCursor(userId, filter, limit)
                .stream()
                .map(RuntimePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public int transition(String sessionId, Transition transition) {
        // 前置条件与写入列完全由迁移声明生成（选择性写 status / turn_phase），
        // 本仓储不出现任何状态或相位字面量
        return mapper.transition(sessionId, transition);
    }

    @Override
    public int archive(String sessionId) {
        // 仅写 archived_at（唯一归档落点），status / turn_phase 保持原值
        return mapper.archive(sessionId);
    }

    @Override
    public Optional<AgentSessionStatus> currentStatus(String sessionId) {
        String raw = mapper.selectStatusValue(sessionId);
        if (raw == null || raw.isBlank()) {
            // 会话行不存在 / 已逻辑删除：读不到即放弃前置判定（终态资格仍由 CAS 仲裁）
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(AgentSessionStatus.fromValue(raw));
        } catch (IllegalArgumentException ex) {
            // 存量取值不在四态值域内（历史脏值）：同样放弃前置判定，保持与迁移前一致的行为
            return Optional.empty();
        }
    }

    @Override
    public boolean isCancelling(String sessionId) {
        return mapper.isCancelling(sessionId);
    }

    @Override
    public void updateProfile(String sessionId, String title, boolean titlePresent,
                              String metadata, String environmentVariables) {
        // title 仅在请求显式提交时更新（可置 null 清空）；metadata / environment_variables 传 null 不更新
        // （environment_variables 为整体替换语义）
        mapper.updateProfile(sessionId, title, titlePresent, metadata, environmentVariables);
    }

    @Override
    public int updateResources(String sessionId, List<SessionResource> resources) {
        // 领域列表 → snake_case jsonb 文本（含 sesr_ 资源 ID）后整列覆盖
        return mapper.updateResources(sessionId,
                RuntimePersistenceConvert.INSTANCE.sessionResourcesToString(resources));
    }

    @Override
    public List<String> findActiveExecutionSessionIds() {
        return mapper.findActiveExecutionSessionIds();
    }

    @Override
    public long countByEnvironmentId(String environmentId) {
        Long count = mapper.countByEnvironmentId(environmentId);
        return count != null ? count : 0;
    }

    @Override
    public long countByMemoryStoreId(String storeId) {
        Long count = mapper.countByMemoryStoreId(storeId);
        return count == null ? 0L : count;
    }

    @Override
    public long countByVaultId(String vaultId) {
        Long count = mapper.countByVaultId(vaultId);
        return count == null ? 0L : count;
    }

    @Override
    public void touchLastActive(String sessionId) {
        mapper.touchLastActive(sessionId);
    }

    @Override
    public int deleteBySessionId(String sessionId) {
        // @TableLogic 逻辑删除（is_deleted=1）；历史事件清理由应用层同事务执行
        return mapper.deleteBySessionId(sessionId);
    }
}