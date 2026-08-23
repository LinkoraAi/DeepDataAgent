package com.linkroa.deepdataagent.agent.application.contract;

import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent 运行时装配契约（发布语言 DTO，Published Language）。
 * <p>由 agent BC 在应用边界出版，作为 {@code AgentVersionAssemblyPort} 的返回类型，
 * 供下游 runtime BC 的防腐层（ACL）消费，并转换为 runtime 自身领域模型
 * {@code AgentAssemblySpec}。跨 BC 只共享本无逻辑的 DTO，双方领域层互不接触：
 * system ← {@code agent_version.system}、modelIndicator ← api_format + model_name 拼接结果、
 * maxIters ← {@code model_profile.tool_call_rounds}；凭证已在基础设施层解密
 * （不进 {@code AgentAssemblySpec}，直接注入运行时工厂装配配置）。
 * 环境引用经 {@link EnvironmentReferenceDTO}、记忆库引用经 {@code MemoryStoreReferenceDTO}
 * 对外输出已格式化值，不泄露 agent BC 领域枚举 / 值对象。</p>
 *
 * @param agentId        Agent 业务 ID
 * @param versionNumber  发布号（十进制）
 * @param versionName    版本名称（Agent 装配显示名）
 * @param system         系统提示词（可空）
 * @param modelIndicator 模型标识（api_format + model_name 拼接结果，如 openai:gpt-4）
 * @param maxIters       工具调用轮次上限
 * @param credential     解密后的模型凭证（无鉴权时可空）
 * @param apiEndpointUrl 模型 API 端点
 * @param dataSourceIds 数据源引用（数据源 id，可空/空，供运行时自动装配数据源查询工具）
 * @param skills         挂载技能装配契约（技能包原始字节，可空/空）
 * @param environment    运行环境引用（可空，未引用回退默认规格）
 * @param memoryStores   记忆库引用（可空/空，未引用不装配记忆工具）
 */
public record ResolvedAgentAssemblyDTO(
        String agentId,
        int versionNumber,
        String versionName,
        String system,
        String modelIndicator,
        int maxIters,
        String credential,
        String apiEndpointUrl,
        List<Long> dataSourceIds,
        List<ResolvedSkillDTO> skills,
        EnvironmentReferenceDTO environment,
        List<MemoryStoreReferenceDTO> memoryStores
) {

    private static final int MAX_SYSTEM_LENGTH = 20000;

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
        if (system != null && system.length() > MAX_SYSTEM_LENGTH) {
            throw new IllegalArgumentException("系统提示词长度不能超过20000个字符");
        }
        dataSourceIds = dataSourceIds == null ? List.of() : List.copyOf(dataSourceIds);
        skills = skills == null ? List.of() : List.copyOf(skills);
        memoryStores = memoryStores == null ? List.of() : List.copyOf(memoryStores);
    }

    /**
     * 脱敏 toString：明文凭证与技能包字节不随日志/异常链输出（凭证保留前 4 位，技能仅列名称）。
     */
    @Override
    public String toString() {
        return "ResolvedAgentAssemblyDTO[agentId=" + agentId
                + ", versionNumber=" + versionNumber
                + ", versionName=" + versionName
                + ", system=" + system
                + ", modelIndicator=" + modelIndicator
                + ", maxIters=" + maxIters
                + ", credential=" + mask(credential)
                + ", apiEndpointUrl=" + apiEndpointUrl
                + ", dataSourceIds=" + dataSourceIds
                + ", skills=" + skills.stream()
                        .map(s -> s.name() + "@v" + s.versionNumber())
                        .toList()
                + ", environment=" + environment
                + ", memoryStores=" + memoryStores + "]";
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