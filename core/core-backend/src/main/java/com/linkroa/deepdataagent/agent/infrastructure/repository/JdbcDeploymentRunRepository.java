package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRunsFilter;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRunRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.DeploymentRunPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentRunEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DeploymentRunMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调度运行记录仓储实现（MyBatis-Plus）：触发落行 + CAS 窄列终态化（不整行回写）。
 */
@Repository
public class JdbcDeploymentRunRepository implements DeploymentRunRepository {

    private final DeploymentRunMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 调度运行记录表 Mapper
     */
    public JdbcDeploymentRunRepository(DeploymentRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DeploymentRun save(DeploymentRun run) {
        DeploymentRunEntity entity = DeploymentRunPersistenceConvert.INSTANCE.toEntity(run);
        entity.setId(null);
        mapper.insert(entity);
        return findByRunId(run.runId()).orElse(run);
    }

    @Override
    public Optional<DeploymentRun> findByRunId(String runId) {
        return Optional.ofNullable(DeploymentRunPersistenceConvert.INSTANCE.toDomain(mapper.selectByRunId(runId)));
    }

    @Override
    public Optional<DeploymentRun> findRunningBySessionId(String sessionId) {
        return Optional.ofNullable(
                DeploymentRunPersistenceConvert.INSTANCE.toDomain(mapper.selectRunningBySessionId(sessionId)));
    }

    @Override
    public boolean casCompleteBySessionId(String sessionId, DeploymentRunStatus status, OffsetDateTime finishedAt) {
        return mapper.casCompleteBySessionId(sessionId, status.getValue(), finishedAt) > 0;
    }

    @Override
    public List<DeploymentRun> findByCursor(DeploymentRunsFilter filter, int limit) {
        return mapper.selectByCursor(filter, limit).stream()
                .map(DeploymentRunPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }
}
