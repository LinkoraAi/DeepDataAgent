package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 运行时装配防腐映射器（ACL）：agent 契约 DTO → 本 BC 领域模型 {@link AgentAssemblySpec}。
 * <p>runtime BC 不接触 agent 领域模型，仅依赖其发布语言 DTO，经 MapStruct 生成
 * 映射代码转换为自身可执行业务规则的装配规格。凭证 / API 端点作为工厂装配参数一并
 * 映射进装配规格（明文凭证经 {@link AgentAssemblySpec#toString()} 脱敏）。
 * 技能已由 {@code SkillPackageMaterializer} 物化为框架无关注值对象 {@link Skill}，
 * 此处原样透传进装配规格。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentAssemblyAssembler {

    /**
     * 装配规格转换：DTO 契约字段按运行时语义组装为本 BC 领域模型。
     *
     * @param dto     agent 装配契约（agentId / versionName / modelIndicator / system / maxIters / credential / apiEndpointUrl / dataSourceIds / skills）
     * @param sandbox 沙箱规格（运行时基础设施参数）
     * @param skills  已物化技能（框架无关注值对象）
     * @return 运行时装配规格
     */
    @Mapping(target = "name", source = "dto.versionName")
    @Mapping(target = "model", source = "dto.modelIndicator")
    @Mapping(target = "systemPrompt", source = "dto.system")
    @Mapping(target = "sandbox", source = "sandbox")
    @Mapping(target = "credential", source = "dto.credential")
    @Mapping(target = "apiEndpointUrl", source = "dto.apiEndpointUrl")
    @Mapping(target = "dataSourceIds", source = "dto.dataSourceIds")
    @Mapping(target = "skills", source = "skills")
    AgentAssemblySpec toSpec(ResolvedAgentAssemblyDTO dto, AgentAssemblySpec.Sandbox sandbox, List<Skill> skills);
}