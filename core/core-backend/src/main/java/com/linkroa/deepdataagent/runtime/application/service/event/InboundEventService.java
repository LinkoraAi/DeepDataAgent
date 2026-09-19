package com.linkroa.deepdataagent.runtime.application.service.event;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.convert.SseEventEnvelopeConvert;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnEventWriter;
import com.linkroa.deepdataagent.runtime.application.service.execution.ExecutionContext;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnExecutionService;
import com.linkroa.deepdataagent.runtime.application.service.hitl.HumanConfirmationService;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.application.validation.InboundEventValidator;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 入站事件批量摄取入口服务（decompose-command-facade 4.2）。
 * <p><b>职责边界</b>：承载「外部门户 POST /sessions/{id}/events 的批量摄取 + 按语义驱动 turn」，
 * 方法与门面原文逐字平移（异常类型与消息文案、校验次序、事务边界、日志级别与占位符零变化）。</p>
 * <p><b>入站摄取链路</b>（全有或全无 + 按语义驱动 turn）：</p>
 * <pre>{@code
 *  POST /sessions/{id}/events → ingestInboundEventEnvelopes
 *     载荷结构校验（首行前置，400 先于会话不存在）→ requireOwnedSession（404）
 *     → 归档 404 / 终止会话消息 409 门禁 → 出站类型门禁（400，保持在属主校验之后）
 *     → 单活跃执行 409 门禁
 *     → 含 user.message：并入 turn 启动事务（status_running → thread_status_running
 *                        → 入站事件，提交后保序广播并由执行侧异步触发本轮）
 *     → 纯控制事件批：[事务] 逐事件 seq 分配落库 → 实时订阅者广播（回放兜底）
 *     → driveControlActions: user.interrupt → interruptSession
 *                            user.tool_confirmation → resolveHumanConfirmation
 * }</pre>
 * <p><b>依赖方向（design D3 单向无环）</b>：本服务 → {@link SessionLifecycleService}（中断）、
 * {@link HumanConfirmationService}（自动裁决领取）、{@link TurnExecutionService}（跑 turn）；
 * 三者 MUST NOT 反向注入本服务。轮外事件写入底座复用 {@link TurnEventWriter}
 * （会话级 seq 计数器 + 静默广播）。</p>
 * <p>载荷解析小件（{@code parsePayload} / {@code extractText} / {@code parseConfirmation} /
 * {@code ConfirmationPayload}）按 design D2 尾注由各簇**自持**（package-private 私有形态），
 * MUST NOT 为其新建共享 Bean。</p>
 */
@Service
public class InboundEventService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_RUN_ERROR = "DEEP_AGENT_RUN_ERROR";

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private ObjectMapper objectMapper;
    /** turn 事件写入器（decompose-command-facade 2.3）：入站落库点的会话级 seq 分配与静默广播。 */
    @Resource
    private TurnEventWriter turnEventWriter;
    /** 入站批次结构校验器（content blocks / system.message 批规则 / image file source 门禁）。 */
    @Resource
    private InboundEventValidator inboundEventValidator;
    /** 线程仓储：轮外落库点现查主线程归属。 */
    @Resource
    private SessionThreadRepository sessionThreadRepository;
    /** turn 执行链服务（decompose-command-facade 4.2）：入站 user.message 驱动跑 turn。 */
    @Resource
    private TurnExecutionService turnExecutionService;
    /** HITL 人工确认入口服务（decompose-command-facade 4.2）：入站 user.tool_confirmation 自动裁决。 */
    @Resource
    private HumanConfirmationService humanConfirmationService;
    /** 会话生命周期入口服务（decompose-command-facade 4.2）：入站 user.interrupt 驱动中断。 */
    @Resource
    private SessionLifecycleService sessionLifecycleService;

    // ==================== 入站事件批量摄取（全有或全无） ====================

    /**
     * 入站事件批量摄取的协议出口包装：落库结果（领域 {@link ChatEvent}）经
     * {@link SseEventEnvelopeConvert} 装配为扁平 Event 回显，控制器侧零领域引用。
     *
     * @param sessionId 会话 ID
     * @param drafts    已解析的事件草案（按到达顺序）
     * @return 已落库事件的扁平 Event 回显（按 seq 升序，与入参顺序一致）
     */
    public List<SseEventEnvelope> ingestInboundEventEnvelopes(String sessionId, List<InboundEventDraft> drafts) {
        return ingestInboundEvents(sessionId, drafts).stream()
                .map(SseEventEnvelopeConvert.INSTANCE::toEnvelope)
                .toList();
    }

    /**
     * 批量摄取入站事件：全量校验 + 单事务落库（全有或全无，任一失败整体回滚），
     * 落库后按语义驱动 turn（user.message → 跑 turn；user.interrupt → 中断；
     * user.tool_confirmation → 人工确认）。
     * <p>{@link InboundEventDraft} 由控制器装配（类型名解析与 payload 序列化：未知类型名 → 400
     * {@code unknown_event_type}，先于任何会话查询）；本服务在方法体首行执行入站 payload 结构校验
     * （{@link InboundEventValidator}，载荷类 400 先于会话不存在），其后才校验入站白名单
     * （出站类型拒绝保持在属主校验之后）；seq 在事务内逐事件经 {@link TurnEventWriter#nextSequence(String)}
     * 会话级计数器单调分配（在场线程安全、缺席回退 DB max+1）。</p>
     *
     * <p>写面门禁（均在落库前整批判定）：会话已归档 → 404 {@code not_found_error}（归档即写面不可见）；
     * 会话已终止且批内含有效文本 {@code user.message} → 409 {@code invalid_request_error}
     * （{@link SessionBusyException}，终态不可再摄取新消息）；同一会话存在活跃执行
     * （内部相位 running / awaiting_confirmation / cancelling）期间提交含文本的
     * {@code user.message} → 同样 409 {@code invalid_request_error}。</p>
     *
     * @param sessionId 会话 ID
     * @param drafts    已校验的事件草案（按到达顺序）
     * @return 已落库的事件（按 seq 升序，与入参顺序一致）
     */
    public List<ChatEvent> ingestInboundEvents(String sessionId, List<InboundEventDraft> drafts) {
        // 载荷结构校验首行前置（D2 落点）：保持「载荷类 400 先于会话不存在」的对外优先序
        inboundEventValidator.validateInboundBatch(drafts, AuthContext.requireUserId());
        AgentSession session = requireOwnedSession(sessionId);
        if (session.archived()) {
            // 归档即会话对写操作面不可见（sessions spec：归档后提交 user.message → 404）
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话已归档");
        }
        // 终止为持久终态：新用户消息不可再摄取（409 invalid_request_error，D7 特例口径）；
        // 纯控制事件批（user.interrupt / user.tool_confirmation）不被此门禁拦截
        if (session.status() == AgentSessionStatus.TERMINATED && hasUserMessageWithText(drafts)) {
            throw new SessionBusyException("会话已终止，不可提交新消息: " + sessionId);
        }
        // 出站类型不允许入站投递（保持在属主校验之后：会话不存在时仍先返回会话类错误）
        for (InboundEventDraft draft : drafts) {
            if (!draft.type().inbound()) {
                throw new DeepDataAgentException("unknown_event_type: 事件类型 " + draft.type().value() + " 不支持入站投递");
            }
        }
        // 单活跃执行约束（409 冲突）：已有活跃执行（processing / canceling /
        // waiting_confirmation，轮次恒为 running）时提交新用户消息（含有效文本），
        // 整批在落库前拒绝、零部分成功（须先取消当前轮或等待回到 idle）；
        // user.interrupt / user.tool_confirmation 等控制事件不受此门禁限制
        if (session.hasActiveExecution() && hasUserMessageWithText(drafts)) {
            throw new SessionBusyException(
                    "会话存在活跃执行，请先取消当前轮或等待回到 idle 后再提交新消息: " + sessionId);
        }
        List<ChatEvent> persisted;
        // 主线程归属：轮外落库点每批现查一次（控制事件批共用；消息批由执行侧启动事务内解析）
        String mainThreadId = resolveMainThreadId(sessionId);
        // user.message 批次（含可选批尾 system.message）：并入 turn 启动事务，按 events spec 明文的
        // 权威次序「status_running → thread_status_running → 入站事件」落库并保序广播，
        // 提交后由执行侧异步触发本轮（「状态先于内容可见」不依赖跨事务可见性）
        String messageText = firstMessageText(drafts);
        if (StringUtils.isNotBlank(messageText)) {
            persisted = turnExecutionService.startTurnWithInbound(
                    new SendMessageCommand(sessionId, messageText), drafts);
            driveControlActions(sessionId, drafts);
            return persisted;
        }
        // 纯控制事件批（user.interrupt / user.tool_confirmation / user.tool_result 等）：轮外独立事务落库
        try {
            persisted = transactionTemplate.execute(status -> drafts.stream()
                    .map(draft -> {
                        ChatEvent event = ChatEvent.create(sessionId, draft.type(),
                                draft.payloadJson(), turnEventWriter.nextSequence(sessionId), null, mainThreadId);
                        ChatEvent saved = chatEventRepository.save(event);
                        return saved != null ? saved : event;
                    })
                    .toList());
        } catch (RuntimeException ex) {
            log.error("入站事件落库失败，事务整体回滚: sessionId={}", sessionId, ex);
            throw new DeepDataAgentException(DEEP_AGENT_RUN_ERROR + ": 入站事件落库失败");
        }
        // 入站事件向实时订阅者广播（回放兜底）
        sessionRegistry.get(sessionId).ifPresent(ctx -> persisted.forEach(event ->
                turnEventWriter.pushQuietly(ctx, event)));
        driveControlActions(sessionId, drafts);
        return persisted;
    }

    /** 按入站控制事件类型驱动语义：user.interrupt → 中断；user.tool_confirmation → 人工确认。 */
    private void driveControlActions(String sessionId, List<InboundEventDraft> drafts) {
        for (InboundEventDraft draft : drafts) {
            switch (draft.type()) {
                case USER_INTERRUPT -> sessionLifecycleService.interruptSession(sessionId);
                case USER_TOOL_CONFIRMATION -> {
                    // result=allow|deny 已由应用入口校验器严格校验（含 tool_use_id 必填、deny_message 规则），此处直接驱动
                    ConfirmationPayload confirmation = parseConfirmation(draft.payloadJson());
                    if (confirmation != null) {
                        humanConfirmationService.resolveHumanConfirmation(new ResolveHumanConfirmationCommand(
                                sessionId, confirmation.allowed(), confirmation.toolUseId(),
                                confirmation.denyMessage()));
                    }
                }
                // user.tool_result / user.custom_tool_result / user.define_outcome：
                // payload 结构校验在应用入口校验器完成（define_outcome 本期不预分配 outcome_id、不注入）；
                // 外化工具结果续跑与结果评估执行属自托管执行面里程碑，本期仅落库不驱动 turn
                default -> log.info("入站事件 {} 已落库，本期不驱动 turn 动作: sessionId={}", draft.type().value(), sessionId);
            }
        }
    }

    /**
     * 批内是否含携带有效文本的 user.message（409 活跃执行门禁判定口径：
     * 纯控制事件批与无文本消息批不受门禁限制）。
     */
    private boolean hasUserMessageWithText(List<InboundEventDraft> drafts) {
        return StringUtils.isNotBlank(firstMessageText(drafts));
    }

    /**
     * 提取批内首条携带有效文本的 user.message 文本（无则空串）。
     * <p>契约（events spec「用户消息回显落库与推送」）：批内 user.message 决定本批是否经
     * turn 启动事务落库；文本同时作为本轮执行输入。</p>
     */
    private String firstMessageText(List<InboundEventDraft> drafts) {
        return drafts.stream()
                .filter(draft -> draft.type() == ChatEventType.USER_MESSAGE)
                .map(draft -> extractText(draft.payloadJson()))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    /**
     * 从入站 user.message payload JSON 提取文本。
     * <p>content 为非空 content block 数组（接口层已校验），逐块取 {@code text} 字段以空格拼接；
     * 无有效文本时返回空串（调用方跳过驱动）。</p>
     */
    private String extractText(String payloadJson) {
        Map<String, Object> payload = parsePayload(payloadJson);
        if (!(payload.get("content") instanceof List<?> blocks)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> blockMap && blockMap.get("text") instanceof String blockText
                    && !blockText.isBlank()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(blockText);
            }
        }
        return text.toString();
    }

    /**
     * 从入站 user.tool_confirmation payload JSON 解析确认结果（失败返回 null，不阻断批）。
     * <p>payload 形状已由接口层校验（tool_use_id 非空、result ∈ allow|deny），此处解析容错兜底。</p>
     */
    private ConfirmationPayload parseConfirmation(String payloadJson) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            boolean allowed = "allow".equals(payload.get("result"));
            Object toolUseId = payload.get("tool_use_id");
            String denyMessage = payload.get("deny_message") instanceof String message ? message : null;
            return new ConfirmationPayload(allowed, toolUseId == null ? null : String.valueOf(toolUseId),
                    denyMessage);
        } catch (Exception ex) {
            // 仅记录解析失败的类型与影响面（sessionId 由调用方日志覆盖），不回显原始 payload（可能含敏感入参）
            log.warn("解析 user.tool_confirmation payload 失败: {}", ex.toString());
            return null;
        }
    }

    /** 确认结果解析载体（允许标记 + 工具调用定位键 + 拒绝说明）。 */
    private record ConfirmationPayload(boolean allowed, String toolUseId, String denyMessage) {
    }

    /** 解析事件 payload JSON 为 Map（非法收敛为空 Map）。 */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(payloadJson,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    // ==================== 私有工具方法 ====================

    /**
     * 按 ID 查询会话，不存在时抛 404（会话相关用例的统一前置校验）。
     * <p>供 {@link #requireOwnedSession} 复用：会话写操作自身的校验已随会话簇
     * 迁至 {@link SessionLifecycleService}（自持同形副本），本方法不做 owner 校验。</p>
     */
    private AgentSession requireSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
    }

    /**
     * 按 ID 查询会话并校验归属（owner 隔离）：越权与不存在不可区分（统一 404 语义）。
     */
    private AgentSession requireOwnedSession(String sessionId) {
        AgentSession session = requireSession(sessionId);
        if (!session.ownedBy(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在");
        }
        return session;
    }

    /**
     * 解析会话主线程归属 ID（轮外低频落库路径现查一次；主线程缺失降级 null = 无归属，不阻断落库）。
     * <p>轮内高频路径不经本方法：归属在执行现场构造（启动 / 领取事务）时解析一次，
     * 随 {@link ExecutionContext#sessionThreadId()} 传递。</p>
     */
    private String resolveMainThreadId(String sessionId) {
        return sessionThreadRepository.findMain(sessionId)
                .map(SessionThread::threadId)
                .orElse(null);
    }

}
