package com.linkroa.deepdataagent.agent.application.dto;

import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent 运行时装配契约（应用层物化 DTO，仅进程内流转，永不发布）。
 * <p>由 agent BC 在应用边界出版，作为 {@code AgentVersionAssemblyPort} 的返回类型，
 * 供下游 runtime BC 的防腐层（ACL）消费，并转换为 runtime 自身领域模型
 * {@code AgentAssemblySpec}。跨 BC 只共享本无逻辑的 DTO，双方领域层互不接触：
 * sysPrompt ← {@code agent_version.sys_prompt}、modelIndicator ← api_format + model_name 拼接结果、
 * maxIters ← {@code model_profile.tool_call_rounds}；凭证已在基础设施层解密
 * （不进 {@code AgentAssemblySpec}，直接注入运行时工厂装配配置）。
 * 环境引用经 {@link EnvironmentReferenceDTO}、记忆库引用经 {@code MemoryStoreReferenceDTO}
 * 对外输出已格式化值，不泄露 agent BC 领域枚举 / 值对象。</p>
 *
 * @param agentId        Agent 业务 ID
 * @param versionNumber  发布号（十进制）
 * @param versionName    版本名称（Agent 装配显示名）
 * @param sysPrompt      系统提示词（可空）
 * @param agentsMd       AGENTS.md 指令文件原文（可空 = 未配置；已格式化纯文本，不外泄 agent 领域值对象）
 * @param modelIndicator 模型标识（api_format + model_name 拼接结果，如 openai:gpt-4）
 * @param maxIters       工具调用轮次上限
 * @param credential     解密后的模型凭证（无鉴权时可空）
 * @param apiEndpointUrl 模型 API 端点
 * @param skills         挂载技能装配契约（技能包原始字节，可空/空）
 * @param environment    运行环境引用（可空，未引用回退默认规格）
 * @param memoryStores   记忆库引用（可空/空，未引用不装配记忆工具）
 * @param vaultCredentials 挂载保管库解密后的凭证（可空/空，明文仅内存持有，不经响应/日志泄露）
 * @param modelEffort    生效的模型 effort 档位（目录引用解析结果，可空 = 无调优语义）
 * @param modelContextWindow 生效的模型上下文窗口档位（可空 = 提供方默认）
 * @param toolPolicies   逐工具权限策略（tools_json configs 中 permission_policy 非空项，可空/空 = 无策略配置）
 * @param toolVisibility 内置工具可见性约束（agent_toolset 的 enabled_tools / disallowed_tools /
 *                       configs[].enabled 聚合结果，null 归一为无约束空契约；供 runtime 侧翻译为
 *                       Harness 工具过滤指令，契约名名单不含运行时实名）
 * @param mcpServers     版本声明的 MCP 服务器清单（{@code agent_version.mcp_servers_json} 的结构化发布，
 *                       仅 name/type/url，<b>不含任何凭据材料</b>；可空/空 = 版本未声明 MCP。
 *                       供 runtime 侧映射为 MCP 连接清单并装配 JVM 侧 MCP 客户端）
 */
public record ResolvedAgentAssemblyDTO(
        String agentId,
        int versionNumber,
        String versionName,
        String sysPrompt,
        String agentsMd,
        String modelIndicator,
        int maxIters,
        String credential,
        String apiEndpointUrl,
        List<ResolvedSkillDTO> skills,
        EnvironmentReferenceDTO environment,
        List<MemoryStoreReferenceDTO> memoryStores,
        List<ResolvedVaultCredentialDTO> vaultCredentials,
        String modelEffort,
        Integer modelContextWindow,
        List<AgentToolPolicyDTO> toolPolicies,
        AgentToolVisibilityDTO toolVisibility,
        List<AgentMcpServerDTO> mcpServers
) {

    /**
     * 兼容构造器：无模型调优参数与工具策略（内部供应商直连引用 / 无目录形态 / 无策略配置）。
     */
    public ResolvedAgentAssemblyDTO(
            String agentId, int versionNumber, String versionName, String sysPrompt, String agentsMd,
            String modelIndicator, int maxIters, String credential, String apiEndpointUrl,
            List<ResolvedSkillDTO> skills, EnvironmentReferenceDTO environment,
            List<MemoryStoreReferenceDTO> memoryStores, List<ResolvedVaultCredentialDTO> vaultCredentials
    ) {
        this(agentId, versionNumber, versionName, sysPrompt, agentsMd, modelIndicator, maxIters,
                credential, apiEndpointUrl, skills, environment, memoryStores, vaultCredentials,
                null, null, List.of(), null, List.of());
    }

    /**
     * 兼容构造器：无工具权限策略（模型调优参数显式给出）。
     */
    public ResolvedAgentAssemblyDTO(
            String agentId, int versionNumber, String versionName, String sysPrompt, String agentsMd,
            String modelIndicator, int maxIters, String credential, String apiEndpointUrl,
            List<ResolvedSkillDTO> skills, EnvironmentReferenceDTO environment,
            List<MemoryStoreReferenceDTO> memoryStores, List<ResolvedVaultCredentialDTO> vaultCredentials,
            String modelEffort, Integer modelContextWindow
    ) {
        this(agentId, versionNumber, versionName, sysPrompt, agentsMd, modelIndicator, maxIters,
                credential, apiEndpointUrl, skills, environment, memoryStores, vaultCredentials,
                modelEffort, modelContextWindow, List.of(), null, List.of());
    }

    /**
     * 兼容构造器：无可见性约束（工具权限策略显式给出）。
     */
    public ResolvedAgentAssemblyDTO(
            String agentId, int versionNumber, String versionName, String sysPrompt, String agentsMd,
            String modelIndicator, int maxIters, String credential, String apiEndpointUrl,
            List<ResolvedSkillDTO> skills, EnvironmentReferenceDTO environment,
            List<MemoryStoreReferenceDTO> memoryStores, List<ResolvedVaultCredentialDTO> vaultCredentials,
            String modelEffort, Integer modelContextWindow, List<AgentToolPolicyDTO> toolPolicies
    ) {
        this(agentId, versionNumber, versionName, sysPrompt, agentsMd, modelIndicator, maxIters,
                credential, apiEndpointUrl, skills, environment, memoryStores, vaultCredentials,
                modelEffort, modelContextWindow, toolPolicies, null, List.of());
    }

    /**
     * 兼容构造器：无 MCP 服务器声明（版本未声明 MCP 的旧装配链）。
     */
    public ResolvedAgentAssemblyDTO(
            String agentId, int versionNumber, String versionName, String sysPrompt, String agentsMd,
            String modelIndicator, int maxIters, String credential, String apiEndpointUrl,
            List<ResolvedSkillDTO> skills, EnvironmentReferenceDTO environment,
            List<MemoryStoreReferenceDTO> memoryStores, List<ResolvedVaultCredentialDTO> vaultCredentials,
            String modelEffort, Integer modelContextWindow, List<AgentToolPolicyDTO> toolPolicies,
            AgentToolVisibilityDTO toolVisibility
    ) {
        this(agentId, versionNumber, versionName, sysPrompt, agentsMd, modelIndicator, maxIters,
                credential, apiEndpointUrl, skills, environment, memoryStores, vaultCredentials,
                modelEffort, modelContextWindow, toolPolicies, toolVisibility, List.of());
    }

    /** 系统提示词长度上限（对齐版本快照 {@code system} 上限，AgentScope 装配契约）。 */
    private static final int MAX_SYSTEM_LENGTH = 100000;

    /** AGENTS.md 长度上限（对齐领域 {@code AgentsMd} 值对象不变量）。 */
    private static final int MAX_AGENTS_MD_LENGTH = 20000;

    /**
     * 紧凑构造器：契约边界校验
     */
    public ResolvedAgentAssemblyDTO {
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("发布号必须大于0");
        }
        if (StringUtils.isBlank(versionName)) {
            throw new IllegalArgumentException("版本名称不能为空");
        }
        if (StringUtils.isBlank(modelIndicator)) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        if (maxIters < 1) {
            throw new IllegalArgumentException("工具调用轮次必须为正数");
        }
        if (sysPrompt != null && sysPrompt.length() > MAX_SYSTEM_LENGTH) {
            throw new IllegalArgumentException("系统提示词长度不能超过" + MAX_SYSTEM_LENGTH + "个字符");
        }
        if (agentsMd != null && agentsMd.length() > MAX_AGENTS_MD_LENGTH) {
            throw new IllegalArgumentException("AGENTS.md 长度不能超过" + MAX_AGENTS_MD_LENGTH + "个字符");
        }
        if (modelContextWindow != null && modelContextWindow < 1) {
            throw new IllegalArgumentException("模型上下文窗口必须大于0");
        }
        skills = skills == null ? List.of() : List.copyOf(skills);
        memoryStores = memoryStores == null ? List.of() : List.copyOf(memoryStores);
        vaultCredentials = vaultCredentials == null ? List.of() : List.copyOf(vaultCredentials);
        toolPolicies = toolPolicies == null ? List.of() : List.copyOf(toolPolicies);
        toolVisibility = toolVisibility == null
                ? new AgentToolVisibilityDTO(null, null) : toolVisibility;
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    }

    /**
     * 脱敏 toString：明文凭证与技能包字节不随日志/异常链输出（凭证保留前 4 位，技能仅列名称）。
     */
    @Override
    public String toString() {
        return "ResolvedAgentAssemblyDTO[agentId=" + agentId
                + ", versionNumber=" + versionNumber
                + ", versionName=" + versionName
                + ", sysPrompt=" + sysPrompt
                + ", agentsMdLength=" + (agentsMd == null ? 0 : agentsMd.length())
                + ", modelIndicator=" + modelIndicator
                + ", maxIters=" + maxIters
                + ", credential=" + mask(credential)
                + ", apiEndpointUrl=" + apiEndpointUrl
                + ", skills=" + skills.stream()
                        .map(ResolvedSkillDTO::name)
                        .toList()
                + ", environment=" + environment
                + ", memoryStores=" + memoryStores
                + ", vaultCredentials=" + vaultCredentials
                + ", modelEffort=" + modelEffort
                + ", modelContextWindow=" + modelContextWindow
                + ", toolPolicies=" + toolPolicies
                + ", mcpServers=" + mcpServers
                + "]";
    }

    /** 凭证打码：非空且长度大于 4 时保留前 4 位，其余替换为掩码（长度不足以保留时全掩码）。 */
    private static String mask(String credential) {
        if (credential == null || credential.isBlank()) {
            return credential;
        }
        if (credential.length() <= 4) {
            return "****";
        }
        return credential.substring(0, 4) + "****";
    }
}