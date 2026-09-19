package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.AgentIdentity;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ArtifactOwnership;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.MountView;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.Sandbox;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolExecutionPolicy;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolGovernance;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolVisibility;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.VaultCredentialRef;

import java.util.List;
import java.util.Map;

/**
 * 装配规格测试夹具（R13 / D14：集中构造分组，消除散落的 {@code new AgentAssemblySpec(...)}）。
 * <p>提供分组值对象的便捷工厂与一个基线 Builder，供装配规格 / 转换器 / 工厂 / 解析器各单测复用；
 * 只承载「构造」职责，不做任何断言逻辑。</p>
 */
public final class AssemblySpecFixtures {

    /** 基线 Agent 业务 ID。 */
    public static final String AGENT_ID = "agent-a";
    /** 基线 Agent 名称。 */
    public static final String NAME = "数据分析Agent";
    /** 基线模型标识。 */
    public static final String MODEL = "dashscope:qwen-plus";

    private AssemblySpecFixtures() {
    }

    // ==================== 分组工厂 ====================

    public static AgentIdentity identity(String agentId, String name) {
        return new AgentIdentity(agentId, name);
    }

    public static AgentAssemblySpec.ModelBinding model(String model) {
        return new AgentAssemblySpec.ModelBinding(model, null, null, null, null);
    }

    public static AgentAssemblySpec.ModelBinding model(String model, String credential, String apiEndpointUrl,
                                                       String effort, Integer contextWindow) {
        return new AgentAssemblySpec.ModelBinding(model, credential, apiEndpointUrl, effort, contextWindow);
    }

    public static AgentAssemblySpec.PromptContent prompt(String systemPrompt, String agentsMd) {
        return new AgentAssemblySpec.PromptContent(systemPrompt, agentsMd);
    }

    public static Sandbox sandbox() {
        return Sandbox.of("ubuntu:22.04", null, null);
    }

    public static Sandbox sandbox(String image, Long memoryBytes, Long cpuCount) {
        return Sandbox.of(image, memoryBytes, cpuCount);
    }

    public static MountView mounts(List<Skill> skills, List<MemoryStoreRef> memoryStoreRefs,
                                   List<VaultCredentialRef> vaultCredentials,
                                   Map<String, String> envVars,
                                   List<AgentAssemblySpec.FileMountRef> fileMounts) {
        return new MountView(skills, memoryStoreRefs, vaultCredentials, envVars, fileMounts);
    }

    public static MountView noMounts() {
        return new MountView(List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    public static ToolGovernance governance(List<ToolExecutionPolicy> policies, ToolVisibility visibility) {
        return new ToolGovernance(policies, visibility);
    }

    public static ToolGovernance noGovernance() {
        return ToolGovernance.empty();
    }

    public static ArtifactOwnership ownership(String sessionId, Long ownerId) {
        return new ArtifactOwnership(sessionId, ownerId);
    }

    public static ArtifactOwnership noOwnership() {
        return ArtifactOwnership.empty();
    }

    /**
     * 基线 Builder：最小合法规格（无凭证 / 无挂载 / 无策略 / 未绑定会话），
     * 调用方按需 {@code withX} 覆盖单个分组。
     */
    public static AgentAssemblySpec.Builder baseBuilder() {
        return AgentAssemblySpec.builder()
                .withIdentity(identity(AGENT_ID, NAME))
                .withModelBinding(model(MODEL))
                .withPromptContent(prompt("你是助手", null))
                .withMaxIters(20)
                .withSandbox(sandbox())
                .withMountView(noMounts())
                .withToolGovernance(noGovernance())
                .withArtifactOwnership(noOwnership());
    }
}
