package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Session Thread 响应（公开契约 Thread 对象字段全集，严格不多不少）。
 * <p>字段集：{@code id}（{@code sthr_} 前缀）/ {@code type}（恒 {@code "session_thread"}）/
 * {@code session_id} / {@code parent_thread_id}（协调器主线程为 null）/ {@code agent}
 * （该线程使用的 Agent 裁剪快照对象）/ {@code status}（对外四态
 * {@code idle/running/rescheduling/terminated}）/ {@code archived_at}（主线程恒 null）/
 * {@code created_at} / {@code updated_at}（RFC 3339 UTC）。</p>
 * <p><b>MUST NOT 出现</b>：{@code name}、{@code role}、{@code stop_reason}、{@code usage}
 * 等旧字段（单 Agent 场景仅有主线程，子线程归档端点本期不提供）。</p>
 */
public record ThreadResponse(
        String id,
        String type,
        String session_id,
        String parent_thread_id,
        Map<String, Object> agent,
        String status,
        OffsetDateTime archived_at,
        OffsetDateTime created_at,
        OffsetDateTime updated_at
) {
}