package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 完整 Agent 对象响应（对外契约字段全集，严格不多不少）。
 * <p>字段集：{@code id}（agent_ 前缀）/ {@code type}（恒 {@code "agent"}）/ {@code name} /
 * {@code description} / {@code model}（按提交形态回显：目录模型 id 字符串或
 * {@code {id, effort?, context_window?}} 对象）/ {@code system} / {@code tools[]} /
 * {@code mcp_servers[]} / {@code skills[]} / {@code metadata} 对象 / {@code multiagent}
 * （本期恒 {@code null}，词汇预留）/ {@code version}（当前版本，起始 1）/ {@code archived_at}
 * （未归档为 null）/ {@code created_at} / {@code updated_at}（RFC 3339 UTC）。</p>
 * <p><b>与版本快照同形</b>：Agent 对象与版本快照（{@code GET /agents/{id}?version=N} 与
 * {@code GET /agents/{id}/versions}）字段集完全一致，故共用本记录——差别仅在
 * {@code version} 的取值口径（Agent 对象取当前生效版本，快照取该快照自身的版本号）。</p>
 * <p><b>MUST NOT 出现</b>：{@code latest_version} / {@code active_version}（内部版本台账口径）、
 * {@code archived} 布尔（归档仅以 {@code archived_at} 时间戳表达）、{@code agents_md}
 * （已废止）、{@code model_profile_id}（内部供应商映射）、{@code version_id} /
 * {@code agent_id}（版本行内部标识，契约以 {@code id} + {@code version} 表达）。</p>
 * <p>结构化承载：{@code tools} / {@code mcp_servers} / {@code skills} 未配置时为<b>空数组</b>、
 * {@code metadata} 未配置时为<b>空对象</b>，形状稳定便于调用方直接取字段而无需二次解析 JSON 文本。</p>
 */
public record AgentResponse(
        String id,
        String type,
        String name,
        String description,
        Object model,
        String system,
        List<Object> tools,
        List<Object> mcp_servers,
        List<Object> skills,
        Map<String, Object> metadata,
        Map<String, Object> multiagent,
        int version,
        OffsetDateTime archived_at,
        OffsetDateTime created_at,
        OffsetDateTime updated_at
) {
}