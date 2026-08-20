package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent 组装规格：声明式描述一次 Harness 装配所需参数，由领域层持有，不包含任何框架类型。
 * <p>工厂（infrastructure.client）据此装配 AgentScope HarnessAgent，规格本身保持框架无关注。</p>
 * <p>凭证/API 端点作为工厂装配参数一并承载：本对象为单次请求瞬态值对象，不参与持久化与响应
 * 序列化；明文凭证仅经 {@link #toString()} 脱敏，避免随日志/异常链泄露。</p>
 *
 * @param agentId        Agent 业务 ID
 * @param name           Agent 名称
 * @param model          模型字符串 ID（如 dashscope:qwen-plus，经 AgentScope ModelRegistry 解析）
 * @param systemPrompt   系统提示词（可空）
 * @param maxIters       单轮最大迭代次数
 * @param sandbox        沙箱规格（镜像/内存/CPU）
 * @param credential     解密后的模型凭证（无鉴权时可空）
 * @param apiEndpointUrl 模型 API 端点（可空，默认走提供方内置端点）
 * @param dataSourceIds 数据源引用（数据源 id，框架无关注，可空/空）
 * @param skills         挂载技能（已物化的框架无关注内容，可空/空）
 */
public record AgentAssemblySpec(
        String agentId,
        String name,
        String model,
        String systemPrompt,
        int maxIters,
        Sandbox sandbox,
        String credential,
        String apiEndpointUrl,
        List<Long> dataSourceIds,
        List<Skill> skills
) {

    /**
     * 沙箱规格。
     *
     * @param image        Docker 镜像
     * @param memoryBytes  内存上限（字节，可空）
     * @param cpuCount     CPU 核数（可空）
     */
    public record Sandbox(String image, Long memoryBytes, Long cpuCount) {

        public Sandbox {
            if (StringUtils.isBlank(image)) {
                throw new IllegalArgumentException("沙箱镜像不能为空");
            }
            if (memoryBytes != null && memoryBytes <= 0) {
                throw new IllegalArgumentException("沙箱内存上限必须为正数");
            }
            if (cpuCount != null && cpuCount <= 0) {
                throw new IllegalArgumentException("沙箱 CPU 核数必须为正数");
            }
        }

        public static Sandbox of(String image, Long memoryBytes, Long cpuCount) {
            return new Sandbox(image, memoryBytes, cpuCount);
        }
    }

    public AgentAssemblySpec {
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID 不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException("Agent 名称长度不能超过 128");
        }
        if (StringUtils.isBlank(model)) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        if (maxIters <= 0) {
            throw new IllegalArgumentException("最大迭代次数必须为正数");
        }
        if (systemPrompt != null && systemPrompt.length() > 20000) {
            throw new IllegalArgumentException("系统提示词长度不能超过 20000");
        }
        if (sandbox == null) {
            throw new IllegalArgumentException("沙箱规格不能为空");
        }
        dataSourceIds = dataSourceIds == null ? List.of() : List.copyOf(dataSourceIds);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * 脱敏 toString：明文凭证不随日志/异常链输出（保留前 4 位，其余掩码），技能仅列名称。
     */
    @Override
    public String toString() {
        return "AgentAssemblySpec[agentId=" + agentId
                + ", name=" + name
                + ", model=" + model
                + ", systemPrompt=" + systemPrompt
                + ", maxIters=" + maxIters
                + ", sandbox=" + sandbox
                + ", credential=" + mask(credential)
                + ", apiEndpointUrl=" + apiEndpointUrl
                + ", dataSourceIds=" + dataSourceIds
                + ", skills=" + skills.stream().map(Skill::name).toList() + "]";
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