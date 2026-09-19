package com.linkroa.deepdataagent.agent.application.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent 工具可见性装配契约（应用层物化 DTO，仅进程内流转，永不发布）。
 * <p>由 agent BC 在应用边界出版，聚合 {@code agent_version.tools_json} 中各
 * {@code agent_toolset_20260401} 条目的三组可见性字段（公开契约语义）：
 * {@code enabled_tools} 白名单并集、{@code disallowed_tools} 隐藏名单、
 * {@code configs[].enabled} 逐工具开关（false 并入隐藏、true 末位叠加可复活）。
 * 供下游 runtime BC 翻译为 Harness 工具过滤指令（契约名 → 运行时实名映射在
 * runtime 侧完成，本契约仅承载契约名名单）。词汇不泄露 agent BC 领域类型。</p>
 *
 * @param allowedTools 内置工具白名单（契约名；空 = 无白名单约束，全量基座暴露——
 *                     对齐契约「省略或空数组 = 全部内置工具暴露」语义）
 * @param hiddenTools  隐藏并拒绝名单（契约名；空 = 无隐藏约束）
 */
public record AgentToolVisibilityDTO(List<String> allowedTools, List<String> hiddenTools) {

    /**
     * 紧凑构造器：契约边界校验（名单元素非空白；null 归一为空名单 = 无约束）。
     */
    public AgentToolVisibilityDTO {
        allowedTools = normalize(allowedTools, "allowed_tools");
        hiddenTools = normalize(hiddenTools, "hidden_tools");
    }

    /** 是否携带任一可见性约束（两名单皆空时 runtime 侧不产生过滤指令）。 */
    public boolean hasConstraint() {
        return !allowedTools.isEmpty() || !hiddenTools.isEmpty();
    }

    private static List<String> normalize(List<String> names, String field) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        for (String name : names) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("工具可见性名单 " + field + " 元素不能为空白");
            }
        }
        return List.copyOf(names);
    }
}
