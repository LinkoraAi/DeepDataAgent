package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.agent.infrastructure.convert.DeploymentPersistenceConvert;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.DeploymentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 部署仓储实现（MyBatis-Plus）
 */
@Repository
public class JdbcDeploymentRepository implements DeploymentRepository {

    private final DeploymentMapper mapper;

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
    public List<Deployment> listByAgentId(String agentId) {
        return mapper.selectByAgentId(agentId).stream()
                .map(DeploymentPersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public void deleteByDeploymentId(String deploymentId) {
        mapper.delete(Wrappers.<DeploymentEntity>lambdaUpdate()
                .eq(e -> e.getDeploymentId(), deploymentId));
    }
}