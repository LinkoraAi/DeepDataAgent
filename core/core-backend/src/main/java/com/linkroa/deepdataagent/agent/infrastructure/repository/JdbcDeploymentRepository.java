package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentListFilter;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.DeploymentPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DeploymentMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调度器仓储实现（MyBatis-Plus）。
 *
 * <p>注意：更新走 {@code mapper.updateById}，MyBatis-Plus 默认字段策略为非空即更新；
 * 恢复时清除的 {@code pausedReason} 以实体列 {@code FieldStrategy.ALWAYS} 强制参与更新。
 * 调度领取（D14）由 {@link #advanceNextRun} 的条件更新 CAS 承担，多实例安全。</p>
 */
@Repository
public class JdbcDeploymentRepository implements DeploymentRepository {

    private final DeploymentMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 调度器表 Mapper
     */
    public JdbcDeploymentRepository(DeploymentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Deployment save(Deployment deployment) {
        DeploymentEntity entity = DeploymentPersistenceConvert.INSTANCE.toEntity(deployment);
        entity.setId(null);
        mapper.insert(entity);
        return findByDeploymentId(deployment.deploymentId()).orElse(deployment);
    }

    @Override
    public Optional<Deployment> findByDeploymentId(String deploymentId) {
        return Optional.ofNullable(DeploymentPersistenceConvert.INSTANCE.toDomain(mapper.selectByDeploymentId(deploymentId)));
    }

    @Override
    public Optional<Deployment> findByWebhookToken(String webhookToken) {
        return Optional.ofNullable(DeploymentPersistenceConvert.INSTANCE.toDomain(mapper.selectActiveByWebhookToken(webhookToken)));
    }

    @Override
    public List<Deployment> findByCursor(Long ownerId, DeploymentListFilter filter, int limit) {
        return mapper.selectByCursor(ownerId, filter, limit).stream()
                .map(DeploymentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<Deployment> findDue(OffsetDateTime now, int limit) {
        return mapper.selectDue(now, limit).stream()
                .map(DeploymentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public boolean advanceNextRun(String deploymentId, OffsetDateTime expectedNextRunAt, OffsetDateTime nextRunAt) {
        return mapper.advanceNextRunCas(deploymentId, expectedNextRunAt, nextRunAt) > 0;
    }

    @Override
    public Deployment update(Deployment deployment) {
        mapper.updateById(DeploymentPersistenceConvert.INSTANCE.toEntity(deployment));
        return findByDeploymentId(deployment.deploymentId()).orElse(deployment);
    }

    @Override
    public boolean updateLastRun(String deploymentId, String sessionId, String lastStatus, OffsetDateTime runAt) {
        return mapper.updateLastRun(deploymentId, sessionId, lastStatus, runAt) > 0;
    }

    @Override
    public boolean refreshLastStatusForTrigger(String deploymentId, String sessionId, String lastStatus) {
        return mapper.refreshLastStatusCas(deploymentId, sessionId, lastStatus) > 0;
    }

    @Override
    public long countActiveByEnvironmentId(String environmentId) {
        Long count = mapper.selectCountActiveByEnvironmentId(environmentId);
        return count == null ? 0L : count;
    }
}
