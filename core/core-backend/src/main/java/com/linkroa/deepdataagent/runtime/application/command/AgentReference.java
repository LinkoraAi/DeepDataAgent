package com.linkroa.deepdataagent.runtime.application.command;

import org.apache.commons.lang3.StringUtils;

/**
 * 创建会话时的 Agent 引用（请求 {@code agent} 字段的已归一形态）。
 * <p>公开契约允许两种等价提交形态：字符串 Agent ID，或对象
 * {@code {id, type:"agent", version?}}；对象缺 {@code type:"agent"}、
 * {@code id} 非非空字符串、{@code version} 非「正整数或 0」一律拒绝（400）。
 * 解析入口为
 * {@link com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert#toAgentReference(Object)}。</p>
 *
 * @param agentId      Agent 业务 ID（{@code agent_} 前缀，非空白）
 * @param agentVersion 显式发布版本号（十进制文本）；省略 / {@code 0} 时为 {@code null}
 *                     （= 绑定该 Agent 的激活版本，由应用服务解析物化）
 */
public record AgentReference(String agentId, String agentVersion) {

    public AgentReference {
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("智能体ID不能为空");
        }
    }
}