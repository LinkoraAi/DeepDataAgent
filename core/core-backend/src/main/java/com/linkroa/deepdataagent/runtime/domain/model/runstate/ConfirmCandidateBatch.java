package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 待确认候选批次组件（单轮运行态五件套之一，由 {@link TurnRunState} 门面组合）。
 * <p>吸收原 {@code AgentRunState}「HITL 待确认候选批次」节：TOOL_CALL_END 按到达顺序累积候选，
 * 挂起信号（REQUIRE_*）按信号携带的 tool_call_id 批次在本表逐一定位，装配
 * {@code session.requires_action} 批次明细（以公开事件 id 为锚点）。</p>
 * <p>并发契约：{@code confirmCandidates} 为 {@link LinkedHashMap}（保到达顺序），
 * 仅由 Reactor 流线程独占读写，非线程安全。</p>
 * <p>本组件不带日志（领域运行态组件无 slf4j）。批次与候选登记的「错配即拒绝」判定
 * 由应用层 HITL 编排承担（见变更 decompose-turn-pipeline D19），本组件仅提供纯定位视图。</p>
 */
public final class ConfirmCandidateBatch {

    /** HITL 待确认候选（tool_call_id → 挂起现场明细，TOOL_CALL_END 按到达顺序累积成批次） */
    private final Map<String, ConfirmCandidate> confirmCandidates = new LinkedHashMap<>();

    /**
     * HITL 待确认候选明细（单次工具调用的挂起现场：SDK id / 工具名 / 入参 / 公开事件 ID）。
     *
     * @param toolCallId  SDK 工具调用 ID
     * @param toolName    工具名
     * @param inputJson   聚合完成后的入参 JSON（可空）
     * @param toolEventId agent.tool_use 落库事件公开 ID（evt_，requires_action 批次锚点）
     */
    public record ConfirmCandidate(String toolCallId, String toolName, String inputJson, String toolEventId) {
    }

    /**
     * 记忆工具调用为 HITL 待确认候选（TOOL_CALL_END 时调用，按到达顺序累积成批次）。
     * <p>SDK 的 {@code REQUIRE_USER_CONFIRM / REQUIRE_EXTERNAL_EXECUTION} 信号按 reply
     * 整批携带待确认工具调用，挂起时按信号批次定位本表候选，装配
     * {@code session.requires_action} 明细（公开事件 id 锚点）。</p>
     *
     * @param toolCallId  工具调用 ID
     * @param toolName    工具名
     * @param inputJson   聚合完成后的入参 JSON（可空）
     * @param toolEventId agent.tool_use 落库事件公开 ID（evt_）
     */
    public void rememberConfirmCandidate(String toolCallId, String toolName, String inputJson, String toolEventId) {
        if (toolCallId != null) {
            confirmCandidates.put(toolCallId, new ConfirmCandidate(toolCallId, toolName, inputJson, toolEventId));
        }
    }

    /**
     * 按 SDK 批次 id 定位待确认候选（保持批次顺序；未登记的 id 收敛跳过）。
     * <p>D19（变更 decompose-turn-pipeline D4）：不再提供任何静默兜底——批次 id 全部未登记、
     * 或本轮候选登记为空时返回<b>空列表</b>。「错配即拒绝」判定由应用层 HITL 编排据空批次承担，
     * 杜绝把用户 allow 裁决作用到非候选工具调用（授权错配）。</p>
     * <p>批次<b>部分</b>命中（至少定位到一个候选）时仅返回已对齐候选（不引入未登记候选），
     * 沿用完整信号语义。</p>
     *
     * @param toolCallIds 信号携带的待确认工具调用 id 批次
     * @return 命中的候选明细列表（无命中返回空列表，交由应用层拒绝挂起）
     */
    public List<ConfirmCandidate> confirmBatch(Collection<String> toolCallIds) {
        List<ConfirmCandidate> batch = new ArrayList<>();
        if (toolCallIds != null) {
            for (String toolCallId : toolCallIds) {
                ConfirmCandidate candidate = confirmCandidates.get(toolCallId);
                if (candidate != null) {
                    batch.add(candidate);
                }
            }
        }
        return batch;
    }

    /** 本轮已登记的候选数（D19 错配诊断消息用；不含未登记批次 id）。 */
    public int registeredCount() {
        return confirmCandidates.size();
    }

    /** 本轮已登记候选的 tool_call_id 集合（保持到达顺序，D19 错配诊断消息 / WARN 用）。 */
    public Collection<String> registeredIds() {
        return confirmCandidates.keySet();
    }
}
