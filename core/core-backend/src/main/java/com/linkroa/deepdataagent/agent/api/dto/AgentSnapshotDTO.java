package com.linkroa.deepdataagent.agent.api.dto;

import java.util.Map;

/**
 * Agent 嵌入快照发布语言 DTO（跨 BC 契约载体，纯 record 零业务逻辑）。
 * <p>供 runtime BC 在 Session / Session Thread 响应中嵌入 Agent 快照（6.3 / 3.9）：
 * {@code agent} 为<b>已按裁剪规则格式化</b>的对外对象（snake_case 键，
 * {@code created_at / updated_at / archived / archived_at / metadata} 已剔除，
 * 系统提示词以 {@code system} 输出、{@code multiagent} 恒 {@code null}），
 * 消费方仅透传渲染、不得依赖本 DTO 的任何派生行为。</p>
 *
 * @param agentId       Agent 业务 ID（agent_ 前缀）
 * @param versionNumber 快照对应的发布版本号（≥1）
 * @param agent         裁剪后的 Agent 快照对象（可 JSON 序列化，非空）
 */
public record AgentSnapshotDTO(
        String agentId,
        int versionNumber,
        Map<String, Object> agent
) {

    public AgentSnapshotDTO {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber 必须为正发布号");
        }
        if (agent == null) {
            throw new IllegalArgumentException("agent 快照对象不能为空");
        }
    }
}
