package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.AgentDetailResponse;
import com.linkroa.deepdataagent.agent.controller.response.AgentResponse;
import com.linkroa.deepdataagent.agent.controller.response.AgentVersionResponse;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * Agent 定义/版本 → 响应 DTO 转换器
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentResponseConvert {

    AgentResponseConvert INSTANCE = Mappers.getMapper(AgentResponseConvert.class);

    AgentResponse toResponse(AgentDefinition definition);

    AgentVersionResponse toVersionResponse(AgentVersion version);

    /**
     * 组装详情（定义 + 最新版本快照）
     */
    default AgentDetailResponse toDetailResponse(AgentDefinition definition, AgentVersion latestVersion) {
        return new AgentDetailResponse(toResponse(definition), toVersionResponse(latestVersion));
    }
}