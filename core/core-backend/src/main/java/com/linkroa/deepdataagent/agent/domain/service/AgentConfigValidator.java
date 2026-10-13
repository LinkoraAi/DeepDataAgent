package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;

import java.util.List;

/**
 * Agent 版本配置领域校验器（跨值对象的结构不变量，零框架依赖）。
 * <p>单值对象内的不变量（custom schema type=object、enabled/disallowed 互斥、字段词汇）
 * 由各值对象紧凑构造器保证；本校验器承载<b>跨列表</b>规则：
 * skills/tools 数量上限（20/128）。</p>
 * <p>MCP 服务器声明能力已废止（{@code mcp_servers} 字段移除、{@code mcp_toolset} 类型不再支持），
 * 故不再承载服务器名唯一性 / 引用完整性校验。</p>
 * <p>目录级校验（模型 id 是否在目录、effort/context_window 档位是否受支持、custom 技能资产存在性）
 * 属应用层职责（依赖模型目录 / skill 资产端口），不在本校验器内。</p>
 */
public final class AgentConfigValidator {

    private AgentConfigValidator() {
    }

    /**
     * 校验版本配置面的跨列表不变量。
     *
     * @param tools   工具配置列表（可空 = 未配置）
     * @param skills  技能绑定列表（可空 = 未配置）
     * @throws IllegalArgumentException 数量超限
     */
    public static void validate(List<AgentTool> tools, List<SkillBinding> skills) {
        AgentTool.validateCount(tools);
        SkillBinding.validateCount(skills);
    }

    /**
     * 校验多智能体编排配置未被提交：本期 multiagent 非空一律 400 invalid_request_error
     * （响应恒 null，仅保留词汇位），不做静默忽略。
     *
     * @param multiagentJson 多智能体编排配置 JSON（可空 / 空对象 / 空数组视为未提交）
     * @throws IllegalArgumentException 提交了非空 multiagent
     */
    public static void validateMultiagentAbsent(String multiagentJson) {
        if (multiagentJson == null) {
            return;
        }
        String trimmed = multiagentJson.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed) || "{}".equals(trimmed) || "[]".equals(trimmed)) {
            return;
        }
        throw new IllegalArgumentException("multiagent 本期不实现，提交非空值须 400");
    }
}