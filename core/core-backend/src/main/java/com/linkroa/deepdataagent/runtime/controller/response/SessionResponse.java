package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话响应（对齐 Managed Agents Session 对象字段）。
 * <p>{@code agent} 当前仅返回 {@code id / type / version} 快照摘要，完整智能体快照留待后续；
 * {@code resources} 与 {@code archived_at} 尚未落库，暂返回空列表与 {@code null}。</p>
 */
public record SessionResponse(
        String id,
        String type,
        String status,
        Map<String, Object> agent,
        String environment_id,
        String title,
        Map<String, Object> metadata,
        List<Map<String, Object>> resources,
        String archived_at,
        OffsetDateTime created_at,
        OffsetDateTime updated_at
) {
}