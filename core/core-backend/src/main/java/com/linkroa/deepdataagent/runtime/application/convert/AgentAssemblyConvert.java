package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.AgentIdentity;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ArtifactOwnership;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.MountView;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ModelBinding;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.PromptContent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolGovernance;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;

/**
 * 运行时装配防腐映射器（ACL）：agent 契约 DTO → 本 BC 领域模型 {@link AgentAssemblySpec}。
 * <p>runtime BC 不接触 agent 领域模型，仅依赖其发布语言 DTO，映射为自身可执行业务规则的装配规格。
 * 分组改造（R13 / D1）后，{@code AgentAssemblySpec} 顶层只接受分组类型，MapStruct 无法再生成
 * 嵌套 record 的构造映射，故 {@link #toSpec} 改由 {@code default} 方法按分组手工装配（Builder 逐组
 * withX），仍保持 MapStruct 静态单例风格（{@code INSTANCE}）。</p>
 * <p>凭证 / API 端点作为工厂装配参数一并映射进 {@link ModelBinding}（明文凭证经其 {@code toString}
 * 脱敏）；技能已由 {@link SkillAssemblyConvert}（{@code materialize} 批量入口）物化为
 * 框架无关注值对象 {@link Skill}，此处原样归入 {@link MountView}。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AgentAssemblyConvert {

    AgentAssemblyConvert INSTANCE = Mappers.getMapper(AgentAssemblyConvert.class);

    /**
     * 装配规格转换：DTO 契约字段按运行时语义分组组装为本 BC 领域模型。
     * <p>空契约（{@code dto == null}）时各槽位取 null，交由分组紧凑构造器抛
     * {@link IllegalArgumentException}（等价旧「不变量拒绝空 agentId」语义）。</p>
     *
     * @param dto                  agent 装配契约（agentId / versionName / modelIndicator / sysPrompt / agentsMd / maxIters / credential / apiEndpointUrl / skills / modelEffort / modelContextWindow / toolPolicies / toolVisibility）
     * @param sandbox              沙箱规格（由解析服务按全局运行时属性格式化而来）
     * @param skills               已物化技能（框架无关注值对象）
     * @param environmentVariables 会话级环境变量（Session 挂载解析结果，注入沙箱数据面）
     * @param sessionId            产出归属会话业务 ID（Session 装配现场透传，交付工具登记用）
     * @param ownerId              产出归属用户 ID（同上，不信任模型自报）
     * @param fileMounts           会话文件挂载视图（解析服务装配期 reconcile 产出的「有记录且副本就位」清单，
     *                             工厂据此拼系统提示挂载段落并判定只读 bind mount 下发；未挂载为空）
     * @return 运行时装配规格
     */
    default AgentAssemblySpec toSpec(ResolvedAgentAssemblyDTO dto, AgentAssemblySpec.Sandbox sandbox,
                                     List<Skill> skills,
                                     Map<String, String> environmentVariables,
                                     String sessionId, Long ownerId,
                                     List<AgentAssemblySpec.FileMountRef> fileMounts) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentIdentity(
                        dto == null ? null : dto.agentId(),
                        dto == null ? null : dto.versionName()))
                .withModelBinding(new ModelBinding(
                        dto == null ? null : dto.modelIndicator(),
                        dto == null ? null : dto.credential(),
                        dto == null ? null : dto.apiEndpointUrl(),
                        dto == null ? null : dto.modelEffort(),
                        dto == null ? null : dto.modelContextWindow()))
                .withPromptContent(new PromptContent(
                        dto == null ? null : dto.sysPrompt(),
                        dto == null ? null : dto.agentsMd()))
                .withMaxIters(dto == null ? 0 : dto.maxIters())
                .withSandbox(sandbox)
                .withMountView(new MountView(skills, environmentVariables, fileMounts))
                .withToolGovernance(new ToolGovernance(toPolicies(dto), toVisibility(dto)))
                .withArtifactOwnership(new ArtifactOwnership(sessionId, ownerId))
                .build();
    }

    /**
     * 工具权限策略列表映射：agent 契约 DTO → 本 BC 值对象（保持声明顺序，空契约返回空列表）。
     *
     * @param dto agent 装配契约（可空）
     * @return 领域策略值对象列表
     */
    default List<AgentAssemblySpec.ToolExecutionPolicy> toPolicies(ResolvedAgentAssemblyDTO dto) {
        if (dto == null || dto.toolPolicies() == null) {
            return List.of();
        }
        return dto.toolPolicies().stream()
                .map(this::toPolicy)
                .toList();
    }

    /**
     * 工具权限策略元素映射：agent 契约 DTO → 本 BC 值对象（组件同名，null 元素由上层契约保证不出现）。
     *
     * @param policy 契约策略项（可空，null 元素映射为 null）
     * @return 领域策略值对象
     */
    default AgentAssemblySpec.ToolExecutionPolicy toPolicy(
            com.linkroa.deepdataagent.agent.application.dto.AgentToolPolicyDTO policy) {
        if (policy == null) {
            return null;
        }
        return new AgentAssemblySpec.ToolExecutionPolicy(policy.name(), policy.permissionPolicy());
    }

    /**
     * 工具可见性约束映射：agent 契约 DTO → 本 BC 值对象（契约名名单原样透传，
     * 契约名 → 运行时实名翻译属工厂基础设施职责，不在此进行）。
     *
     * @param dto agent 装配契约（可空，其 toolVisibility 为空时返回 null 由分组归一为无约束）
     * @return 领域可见性值对象
     */
    default AgentAssemblySpec.ToolVisibility toVisibility(ResolvedAgentAssemblyDTO dto) {
        if (dto == null || dto.toolVisibility() == null) {
            return null;
        }
        return toVisibility(dto.toolVisibility());
    }

    /**
     * 工具可见性约束映射：agent 契约 DTO → 本 BC 值对象。
     *
     * @param visibility 契约可见性对象（可空 = 无约束，由领域紧凑构造器归一空名单）
     * @return 领域可见性值对象
     */
    default AgentAssemblySpec.ToolVisibility toVisibility(
            com.linkroa.deepdataagent.agent.application.dto.AgentToolVisibilityDTO visibility) {
        if (visibility == null) {
            return null;
        }
        return new AgentAssemblySpec.ToolVisibility(visibility.allowedTools(), visibility.hiddenTools());
    }
}