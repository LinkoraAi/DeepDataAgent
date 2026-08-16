package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Agent 定义/版本 ⇄ 持久化实体转换器（基础字段一一对应，JSONB 字符串透传）
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentPersistenceMapper {

    AgentDefinitionEntity toEntity(AgentDefinition definition);

    AgentDefinition toDomain(AgentDefinitionEntity entity);

    AgentVersionEntity toEntity(AgentVersion version);

    AgentVersion toDomain(AgentVersionEntity entity);
}