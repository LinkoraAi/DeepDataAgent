package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话响应（Session 对象对外字段全集，严格不多不少）。
 * <p>字段集：{@code id}（sess_）/ {@code type}（恒 session）/ {@code agent}（完整冻结快照）/
 * {@code environment_id} / {@code status}（对外四态）/ {@code title} / {@code metadata} 对象 /
 * {@code resources[]} / {@code vault_ids[]} / {@code deployment_id}（普通会话为 null）/
 * {@code archived_at}（未归档为 null）/ {@code created_at} / {@code updated_at}（RFC 3339 UTC）。</p>
 * <p><b>MUST NOT 出现</b>：{@code turn_status}（或任何轮次相位字段）、{@code agent_id}、
 * {@code memory_store_ids}、{@code trigger_type}、{@code trigger_id}、{@code stats}、
 * {@code usage}、{@code outcome_evaluations}、{@code environment_variables}
 * （环境变量仅入参接受，MUST NOT 在任何响应中回显键名与值）。</p>
 */
public record SessionResponse(
        String id,
        String type,
        Map<String, Object> agent,
        String environment_id,
        String status,
        String title,
        Map<String, Object> metadata,
        List<Map<String, Object>> resources,
        List<String> vault_ids,
        String deployment_id,
        OffsetDateTime archived_at,
        OffsetDateTime created_at,
        OffsetDateTime updated_at
) {
}