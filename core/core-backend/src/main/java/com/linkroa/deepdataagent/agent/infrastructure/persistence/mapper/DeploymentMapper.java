package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.DeploymentEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 部署 Mapper
 */
@Mapper
public interface DeploymentMapper extends BaseMapper<DeploymentEntity> {

    default DeploymentEntity selectByDeploymentId(String deploymentId) {
        return selectOne(Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(e -> e.getDeploymentId(), deploymentId)
                .last("LIMIT 1"));
    }

    default List<DeploymentEntity> selectByAgentId(String agentId) {
        return selectList(Wrappers.<DeploymentEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .orderByDesc(e -> e.getCreatedAt()));
    }
}