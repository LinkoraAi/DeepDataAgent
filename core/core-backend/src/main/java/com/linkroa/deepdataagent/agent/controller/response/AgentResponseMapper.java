package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Agent 定义/版本 → 响应 DTO 转换器
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentResponseMapper {

    AgentResponse toResponse(AgentDefinition definition);

    AgentVersionResponse toVersionResponse(AgentVersion version);

    /**
     * 组装详情（定义 + 最新版本快照）
     */
    default AgentDetailResponse toDetailResponse(AgentDefinition definition, AgentVersion latestVersion) {
        return new AgentDetailResponse(toResponse(definition), toVersionResponse(latestVersion));
    }
}