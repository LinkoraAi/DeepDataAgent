package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 运行时装配防腐映射器（ACL）：agent 契约 DTO → 本 BC 领域模型 {@link AgentAssemblySpec}。
 * <p>runtime BC 不接触 agent 领域模型，仅依赖其发布语言 DTO，经 MapStruct 生成
 * 映射代码转换为自身可执行业务规则的装配规格。凭证 / API 端点属运行时工厂装配配置，
 * 不进组装规格。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentAssemblyAssembler {

    /**
     * 装配规格转换：DTO 契约字段按运行时语义组装为本 BC 领域模型。
     *
     * @param dto       agent 装配契约（agentId / versionName / modelIndicator / system / maxIters）
     * @param toolNames 已注册工具名集合（排序后）
     * @param sandbox   沙箱规格（运行时基础设施参数）
     * @return 运行时装配规格
     */
    @Mapping(target = "name", source = "dto.versionName")
    @Mapping(target = "description", expression = "java(\"DeepDataAgent 装配的 Agent（发布号 \" + dto.versionNumber() + \"）\")")
    @Mapping(target = "model", source = "dto.modelIndicator")
    @Mapping(target = "systemPrompt", source = "dto.system")
    @Mapping(target = "toolNames", source = "toolNames")
    @Mapping(target = "sandbox", source = "sandbox")
    AgentAssemblySpec toSpec(ResolvedAgentAssemblyDTO dto, List<String> toolNames, AgentAssemblySpec.Sandbox sandbox);
}