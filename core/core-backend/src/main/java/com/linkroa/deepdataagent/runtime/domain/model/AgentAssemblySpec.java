package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent 组装规格：声明式描述一次 Harness 装配所需参数，由领域层持有，不包含任何框架类型。
 * <p>工厂（infrastructure.client）据此装配 AgentScope HarnessAgent，规格本身保持框架无关注。</p>
 *
 * @param agentId     Agent 业务 ID
 * @param name        Agent 名称
 * @param description Agent 描述
 * @param model       模型字符串 ID（如 dashscope:qwen-plus，经 AgentScope ModelRegistry 解析）
 * @param systemPrompt 系统提示词（可空）
 * @param toolNames    允许注册的工具名集合（与 AgentToolGateway 注册的工具对齐）
 * @param maxIters     单轮最大迭代次数
 * @param sandbox      沙箱规格（镜像/内存/CPU）
 */
public record AgentAssemblySpec(
        String agentId,
        String name,
        String description,
        String model,
        String systemPrompt,
        List<String> toolNames,
        int maxIters,
        Sandbox sandbox
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
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("Agent 描述长度不能超过 500");
        }
        if (systemPrompt != null && systemPrompt.length() > 20000) {
            throw new IllegalArgumentException("系统提示词长度不能超过 20000");
        }
        if (sandbox == null) {
            throw new IllegalArgumentException("沙箱规格不能为空");
        }
        if (toolNames == null) {
            throw new IllegalArgumentException("工具名集合不能为空");
        }
    }

    /**
     * 装配规格工厂方法。
     */
    public static AgentAssemblySpec of(
            String agentId,
            String name,
            String description,
            String model,
            String systemPrompt,
            List<String> toolNames,
            int maxIters,
            Sandbox sandbox
    ) {
        List<String> tools = toolNames == null ? List.of() : List.copyOf(toolNames);
        return new AgentAssemblySpec(agentId, name, description, model, systemPrompt, tools, maxIters, sandbox);
    }
}