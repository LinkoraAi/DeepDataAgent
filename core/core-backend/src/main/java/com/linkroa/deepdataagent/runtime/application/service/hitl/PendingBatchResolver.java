package com.linkroa.deepdataagent.runtime.application.service.hitl;

import com.linkroa.deepdataagent.runtime.application.service.PayloadJson;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HITL 待确认批次解析器（durable 等待现场的账本读取侧，读多写零）。
 * <p>等待事实完全由事件账本承载（最新 {@code session.requires_action} 批次明细 + 会话等待状态），
 * 本组件负责从账本把该事实还原为可续跑的整批明细，供人工确认用例在领取前定位批次、
 * 在领取事务提交后重建现场——任意实例（含服务重启后）、任意等待时长均可完成解析
 * （decompose-command-facade 2.2：三方法自命令服务 HITL 分区逐字平移，判定与降级语义零变化）。</p>
 * <ul>
 *   <li>{@link #locatePendingBatch}：锚点 → 当前等待批次的公开事件 id 集合（定位与兼容口径见方法注释）；</li>
 *   <li>{@link #rebuildPendingBatch}：批次 id → 整批 {@link PendingToolCallSpec}（SDK id / 工具名 / 入参
 *       均取自账本 {@code agent.tool_use} payload，非法行跳过）。</li>
 * </ul>
 * <p>本组件<b>不做</b>租约抢占、状态 CAS 与续跑调度（那些编排留守应用服务），也不抛 HTTP 语义异常——
 * 「无待确认项 / 明细重建失败」由调用方按其对外契约映射 404 / 运行错误。</p>
 * <p><b>可见性</b>：public（永久）——主干消费方仅同包的 {@link HumanConfirmationService}，
 * 但 5.1 归位复核确认共享测试夹具基座 {@code AgentRuntimeServiceTestSupport}（位于父包
 * {@code application.service} 测试包）须装配本类的<b>真实</b>实例进协作网（HITL 领取用例端到端钉桩），
 * 收紧为包私有会切断该装配；为此在子包内再加一层构造委托属过度设计，故保持 public。</p>
 */
@Service
public class PendingBatchResolver {

    private static final Logger log = LoggerFactory.getLogger(PendingBatchResolver.class);

    /** 账本 payload JSON 文本反序列化目标类型（批次明细 / 工具调用行同口径）。 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 事件账本仓储：等待批次与工具调用行的只读来源。 */
    @Resource
    private ChatEventRepository chatEventRepository;
    /** 配置化 JSON mapper（与命令服务共用同一实例口径，由装配注入）。 */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 定位当前等待批次（hitl spec：批次内任一 id 均可锚定，裁决整批生效）。
     * <p>等待事实完全由账本承载：**未应答的工具调用事件**（{@code agent.tool_use} /
     * {@code agent.mcp_tool_use} 中尚未出现配对 tool_result 的行）即完整确认现场，
     * 不存在 {@code session.requires_action} 旁路事件。锚点为空视为无定位约束，取全体未应答项；
     * 锚点非批次成员（越界 / 旧批次已被解析）返回空，由调用方映射 404。</p>
     *
     * @param sessionId 会话 ID
     * @param anchor    确认锚点（待确认工具事件的公开 {@code evt_} ID，可空）
     * @return 当前等待批次的公开事件 id 集合（无等待项返回空列表）
     */
    public List<String> locatePendingBatch(String sessionId, String anchor) {
        List<ChatEvent> toolUses = chatEventRepository.findByTypes(sessionId, ChatEventType.TOOL_USE_TYPES);
        if (toolUses.isEmpty()) {
            return List.of();
        }
        Set<String> answered = chatEventRepository.findByTypes(sessionId, ChatEventType.TOOL_RESULT_TYPES)
                .stream()
                .map(this::toolUseIdOf)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        List<String> pendingEventIds = toolUses.stream()
                .filter(event -> !answered.contains(toolUseIdOf(event)))
                .map(ChatEvent::eventId)
                .toList();
        if (pendingEventIds.isEmpty()) {
            return List.of();
        }
        if (StringUtils.isBlank(anchor)) {
            return pendingEventIds;
        }
        return pendingEventIds.contains(anchor) ? pendingEventIds : List.of();
    }

    /** 按批次公开事件 id 批查工具调用行，重建整批待确认明细（SDK id 从 payload 读取）。
     *  <p>账本查询覆盖内置 {@code agent.tool_use} 与 MCP {@code agent.mcp_tool_use} 两类
     *  （D15：MCP 调用的暂停锚点与内置同构，载荷 {@code tool_use_id}/{@code name}/{@code input} 同形），
     *  故 MCP 工具在 {@code always_ask} 下的挂起批次可被同样解析。</p> */
    public List<PendingToolCallSpec> rebuildPendingBatch(String sessionId, List<String> batchEventIds) {
        return chatEventRepository.findToolUsesByEventIds(sessionId, batchEventIds).stream()
                .map(this::toPendingSpec)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 工具调用事件行（内置 / MCP 同形）→ 待确认明细值对象（payload.tool_use_id/name/input；非法行返回 null 跳过）。 */
    private PendingToolCallSpec toPendingSpec(ChatEvent toolUseEvent) {
        try {
            Map<String, Object> payload = objectMapper.readValue(toolUseEvent.payload(), MAP_TYPE);
            String toolCallId = payload.get("tool_use_id") instanceof String id ? id : null;
            String toolName = payload.get("name") instanceof String name ? name : null;
            String inputJson = payload.get("input") == null ? "{}"
                    : PayloadJson.jsonOf(objectMapper, PayloadJson.asMap(payload.get("input")));
            if (StringUtils.isBlank(toolCallId)) {
                return null;
            }
            return new PendingToolCallSpec(toolCallId, toolName, inputJson);
        } catch (RuntimeException ex) {
            log.warn("工具调用明细行解析失败，跳过: sessionId={}, eventId={}",
                    toolUseEvent.sessionId(), toolUseEvent.eventId(), ex);
            return null;
        }
    }

    /** 事件 payload 中的工具调用配对键（{@code tool_use_id}）；解析失败返回 null（按未应答处置）。 */
    private String toolUseIdOf(ChatEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.payload(), MAP_TYPE);
            return payload.get("tool_use_id") instanceof String id ? id : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
