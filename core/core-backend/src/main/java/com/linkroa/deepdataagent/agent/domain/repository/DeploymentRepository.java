package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.Deployment;

import java.util.List;
import java.util.Optional;

/**
 * 部署仓储接口
 */
public interface DeploymentRepository {

    /**
     * 保存部署审计记录（新增）
     */
    Deployment save(Deployment deployment);

    /**
     * 按业务ID查询
     */
    Optional<Deployment> findByDeploymentId(String deploymentId);

    /**
     * 按 Agent 查询部署审计记录列表
     */
    List<Deployment> listByAgentId(String agentId);

    /**
     * 逻辑删除
     */
    void deleteByDeploymentId(String deploymentId);
}