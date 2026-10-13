package com.linkroa.deepdataagent.agent.application.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * Agent 工具权限策略装配契约（应用层物化 DTO，仅进程内流转，永不发布）。
 * <p>由 agent BC 在应用边界出版，取 {@code agent_version.tools_json} 中逐工具
 * {@code configs[].permission_policy} 非空的配置项，供下游 runtime BC 装配
 * Harness 权限引擎（always_ask → HITL 暂停确认、always_deny → 直接拒绝）。
 * 词汇对齐 {@code AgentTool.ToolConfig} 的三值策略，不泄露 agent BC 领域类型。</p>
 *
 * @param name             工具名（内置工具为公开契约名，非空）
 * @param permissionPolicy 权限策略（always_allow / always_ask / always_deny，可空 = 平台默认不产生本契约项）
 */
public record AgentToolPolicyDTO(String name, String permissionPolicy) {

    /** 恒允许策略。 */
    public static final String POLICY_ALWAYS_ALLOW = "always_allow";
    /** 每次询问策略（触发 HITL tool_confirmation）。 */
    public static final String POLICY_ALWAYS_ASK = "always_ask";
    /** 恒拒绝策略。 */
    public static final String POLICY_ALWAYS_DENY = "always_deny";

    /**
     * 紧凑构造器：契约边界校验（工具名非空、策略词汇合法）。
     */
    public AgentToolPolicyDTO {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("工具策略 name 不能为空");
        }
        if (permissionPolicy != null && !POLICY_ALWAYS_ALLOW.equals(permissionPolicy)
                && !POLICY_ALWAYS_ASK.equals(permissionPolicy)
                && !POLICY_ALWAYS_DENY.equals(permissionPolicy)) {
            throw new IllegalArgumentException("工具 permission_policy 非法，须为 always_allow/always_ask/always_deny");
        }
    }
}