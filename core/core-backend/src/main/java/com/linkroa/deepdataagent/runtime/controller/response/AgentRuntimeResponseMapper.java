package com.linkroa.deepdataagent.runtime.controller.response;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 领域对象 → 响应 DTO 的 MapStruct 转换器。
 * <p>枚举映射为字符串名（status / eventType / spanKind 等），字段同名直转。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentRuntimeResponseMapper {

    SessionResponse toSessionResponse(AgentSession session);

    RoundResponse toRoundResponse(ExecutionRound round);

    RunTraceResponse toRunTraceResponse(RunTrace trace);
}