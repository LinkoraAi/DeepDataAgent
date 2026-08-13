package com.linkroa.deepdataagent.agent.application.adapter;

import com.linkroa.deepdataagent.agent.infrastructure.collector.AnalysisSnapshotCollector;
import com.linkroa.deepdataagent.agent.infrastructure.collector.ToolCallItem;
import com.linkroa.deepdataagent.agent.domain.model.DialogueContent;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageStatus;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;
import com.linkroa.deepdataagent.agent.infrastructure.util.TextCleaner;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件适配器
 * <p>负责将 AgentScope 的 {@link AgentEvent} 流适配为领域模型的 {@link DialogueMessage}，
 * 每个事件（含 DELTA 增量）实时创建或返回对应的进行中消息（IN_PROGRESS），
 * 块结束事件将消息收敛为终态（COMPLETED/FAILED），返回受影响消息列表供调用方逐事件落库。</p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>按 sessionId 聚合 AgentEvent，DELTA 增量统一累积到 {@link AnalysisSnapshotCollector}
 *   （单一累积源，StringBuilder 摊销 O(1)），进行中消息仅占位；块结束事件收敛消息状态时一次性写入完整内容</li>
 *   <li>{@link #handleEvent} 返回"本次事件创建或更新的消息列表"，驱动调用方实时持久化（先落库后推送）；
 *   落库内容取 {@link CollectorContext#getPersistenceSnapshot()} 快照，从收集器注入进行中消息的当前累积文本</li>
 *   <li>维护 {@link AnalysisSnapshotCollector} 收集流式推送所需的最小状态（思考/报告缓冲、工具调用、结果缓冲）</li>
 *   <li>工具调用拆分为两条独立消息：TOOL_CALL（工具名 + 入参，TOOL_CALL_END 收敛）+ TOOL_RESULT
 *   （工具名 + 返回结果，惰性创建、TOOL_RESULT_END 收敛），两者通过相同的 toolCallId 配对：
 *   内存映射承接实时增量，toolCallId 随消息内容持久化，供前端实时与回放精确配对</li>
 * </ul>
 *
 * <p>线程安全：每个 sessionId 对应独立的 CollectorContext，使用 ConcurrentHashMap 保证并发安全；
 * 进行中消息的创建与快照生成通过 {@code synchronized (this)} 保持一致。</p>
 */
@Component
public class EventAdapter {

    private static final Logger log = LoggerFactory.getLogger(EventAdapter.class);

    /** 按 sessionId 索引的收集器上下文映射 */
    private final Map<String, CollectorContext> contexts = new ConcurrentHashMap<>();

    /**
     * 构造方法
     */
    public EventAdapter() {
    }

    /**
     * 注册会话上下文
     * <p>在分析开始前调用，创建独立的 CollectorContext 用于聚合事件。
     * 用户消息（messageNumber=1）已作为首条消息加入上下文，随全量快照落库，
     * 因此后续分析消息序号从 2 开始。</p>
     *
     * @param sessionId 会话 ID
     * @param text      用户问题
     * @return 新创建的收集器上下文
     */
    public CollectorContext registerContext(String sessionId, String text) {
        CollectorContext context = new CollectorContext(sessionId, text);
        contexts.put(sessionId, context);
        return context;
    }

    /**
     * 获取会话上下文
     *
     * @param sessionId 会话 ID
     * @return 收集器上下文，如果不存在返回 null
     */
    public CollectorContext getContext(String sessionId) {
        return contexts.get(sessionId);
    }

    /**
     * 注销会话上下文
     * <p>在分析完成、失败或取消后调用，释放资源。</p>
     *
     * @param sessionId 会话 ID
     */
    public void unregisterContext(String sessionId) {
        contexts.remove(sessionId);
    }

    /**
     * 追加错误消息
     * <p>流式失败时由调用方显式调用，创建一条 ERROR 消息追加到消息列表。</p>
     *
     * @param sessionId    会话 ID
     * @param errorMessage 错误信息
     */
    public void addError(String sessionId, String errorMessage) {
        CollectorContext context = contexts.get(sessionId);
        if (context == null) {
            log.warn("EventAdapter: no context found for sessionId={}, cannot add error message", sessionId);
            return;
        }
        context.addMessage(buildErrorMessage(context, errorMessage != null ? errorMessage : "未知错误"));
    }

    /**
     * 处理 AgentEvent
     * <p>根据事件类型实时创建或更新 {@link DialogueMessage}：DELTA 事件追加到进行中消息（IN_PROGRESS），
     * 块结束事件（THINKING_BLOCK_END/TOOL_CALL_END/TOOL_RESULT_END/AGENT_END）将消息收敛为终态。
     * 一次工具调用持久化为 TOOL_CALL + TOOL_RESULT 两条独立消息：
     * TOOL_CALL_END 收敛调用消息（工具名 + 入参），TOOL_RESULT_TEXT_DELTA 惰性创建结果消息，
     * TOOL_RESULT_END 收敛结果消息（工具名 + 返回结果）。
     * 返回值"本次事件创建或更新的消息列表"供调用方实时持久化（先落库后推送）。</p>
     *
     * @param sessionId 会话 ID
     * @param event     AgentEvent 事件
     * @return 本次事件创建或更新的对话消息列表（无变化时返回空列表）
     */
    public List<DialogueMessage> handleEvent(String sessionId, AgentEvent event) {
        CollectorContext context = contexts.get(sessionId);
        if (context == null) {
            log.warn("EventAdapter: no context found for sessionId={}, ignoring event", sessionId);
            return List.of();
        }

        AgentEventType type = event.getType();
        AnalysisSnapshotCollector collector = context.collector();

        switch (type) {
            case THINKING_BLOCK_START:
            case THINKING_BLOCK_DELTA:
                // 思考过程增量事件，实时追加到进行中的 THINKING 消息并同步快照缓冲
                if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
                    collector.addThinkingStep(thinkingEvent.getDelta());
                    DialogueMessage thinkingMessage = context.appendThinkingDelta(thinkingEvent.getDelta());
                    if (thinkingMessage != null) {
                        return List.of(thinkingMessage);
                    }
                }
                return List.of();

            case THINKING_BLOCK_END:
                // 思考块结束，将进行中 THINKING 消息收敛为 COMPLETED
                String thinking = collector.flushThinkingStep();
                DialogueMessage thinkingMessage = context.completeThinking(thinking);
                if (thinkingMessage != null) {
                    return List.of(thinkingMessage);
                }
                // 兜底：无进行中消息但存在思考文本时新建完整消息
                if (thinking != null) {
                    return List.of(context.addMessage(buildThinkingMessage(context, thinking)));
                }
                return List.of();

            case TOOL_CALL_START:
                // 工具调用开始：记录 toolCall 项，将未完成的助手叙述转 THINKING 收敛，
                // 并立即创建进行中的 TOOL_CALL 消息供后续 DELTA 实时追加入参
                if (event instanceof ToolCallStartEvent toolCallEvent) {
                    collector.addToolCall(new ToolCallItem(
                            toolCallEvent.getToolCallName(), "",
                            System.currentTimeMillis(), toolCallEvent.getToolCallId()));
                    List<DialogueMessage> affected = new ArrayList<>();
                    DialogueMessage convertedMessage = context.convertAssistantToThinking();
                    if (convertedMessage != null) {
                        affected.add(convertedMessage);
                    }
                    affected.add(context.createToolCallMessage(
                            toolCallEvent.getToolCallId(), toolCallEvent.getToolCallName()));
                    return affected;
                }
                return List.of();

            case TOOL_CALL_DELTA:
                // 工具调用参数增量，实时追加到进行中 TOOL_CALL 消息的入参
                if (event instanceof ToolCallDeltaEvent toolCallDeltaEvent) {
                    collector.appendToolCallInput(toolCallDeltaEvent.getToolCallId(), toolCallDeltaEvent.getDelta());
                    DialogueMessage message = context.appendToolInputDelta(
                            toolCallDeltaEvent.getToolCallId(), toolCallDeltaEvent.getDelta());
                    if (message != null) {
                        return List.of(message);
                    }
                }
                return List.of();

            case TOOL_CALL_END:
                // 工具调用结束：以 collector 中最终入参同步消息并立即收敛为 COMPLETED，
                // 结果由独立的 TOOL_RESULT 消息按同一 toolCallId 承接
                if (event instanceof ToolCallEndEvent toolCallEndEvent) {
                    ToolCallItem tc = collector.getToolCallById(toolCallEndEvent.getToolCallId());
                    if (tc != null) {
                        DialogueMessage message = context.completeToolCall(toolCallEndEvent.getToolCallId(), tc.input());
                        if (message != null) {
                            return List.of(message);
                        }
                        // 兜底：消息未创建（异常路径）时按原逻辑构建为已完成的调用消息
                        DialogueMessage built = context.addMessage(buildToolCallMessage(context, tc));
                        return List.of(built);
                    }
                }
                return List.of();

            case TOOL_RESULT_TEXT_DELTA:
                // 工具结果文本增量：首次到达时惰性创建独立的 TOOL_RESULT 消息并实时追加结果
                if (event instanceof ToolResultTextDeltaEvent deltaEvent) {
                    String toolCallId = deltaEvent.getToolCallId();
                    String delta = deltaEvent.getDelta();
                    if (toolCallId != null && delta != null) {
                        collector.appendToolResultDelta(toolCallId, delta);
                        DialogueMessage message = context.appendToolResultDelta(toolCallId, delta);
                        if (message != null) {
                            return List.of(message);
                        }
                    }
                }
                return List.of();

            case TOOL_RESULT_END:
                // 工具结果完成：将最终结果写入独立的 TOOL_RESULT 消息并收敛终态（COMPLETED/FAILED）
                if (event instanceof ToolResultEndEvent toolResultEvent) {
                    String toolCallId = toolResultEvent.getToolCallId();
                    var state = toolResultEvent.getState();
                    boolean success = state != null && !"error".equalsIgnoreCase(state.getValue());
                    String resultContent = collector.getToolResult(toolCallId);
                    collector.setToolCallResult(toolCallId, resultContent, success);
                    DialogueMessage message = context.completeToolResult(toolCallId, resultContent, success);
                    if (message != null) {
                        return List.of(message);
                    }
                }
                return List.of();

            case TEXT_BLOCK_DELTA:
                // 文本增量（Agent 最终回复），先累积到收集器缓冲，实时创建/返回进行中的 ASSISTANT 报告消息
                if (event instanceof TextBlockDeltaEvent textEvent) {
                    collector.addAssistantStep(textEvent.getDelta());
                    DialogueMessage message = context.appendAssistantDelta(textEvent.getDelta());
                    if (message != null) {
                        return List.of(message);
                    }
                }
                return List.of();

            case AGENT_RESULT:
                // Agent 最终结果，记录权威最终文本（不产生消息变化）
                if (event instanceof AgentResultEvent resultEvent) {
                    var result = resultEvent.getResult();
                    if (result != null) {
                        String responseText = result.getTextContent();
                        if (responseText != null) {
                            context.setFinalResponse(responseText);
                        }
                    }
                }
                return List.of();

            case AGENT_END:
                // Agent 执行结束，将进行中的报告消息收敛为 COMPLETED
                // 优先采用 AGENT_RESULT 的权威最终文本；否则退回清洗后的累积助手文本
                String report = context.finalResponse();
                if (report != null && !report.isBlank()) {
                    DialogueMessage completed = context.completeAssistant(report);
                    if (completed != null) {
                        return List.of(completed);
                    }
                    return List.of(context.addMessage(buildAssistantMessage(context, report)));
                }
                DialogueMessage fallback = context.takeAssistantMessage();
                if (fallback != null) {
                    String accumulated = collector.flushAssistantStep();
                    if (accumulated != null && !accumulated.isBlank()) {
                        fallback.setContent(DialogueContent.text(TextCleaner.stripReasoningPreamble(accumulated)));
                    }
                    fallback.complete();
                    return List.of(fallback);
                }
                return List.of();

            default:
                // 其他事件类型忽略
                return List.of();
        }
    }

    /**
     * 构建 THINKING 消息
     *
     * @param context  收集器上下文
     * @param thinking 思考文本
     * @return THINKING 消息
     */
    private DialogueMessage buildThinkingMessage(CollectorContext context, String thinking) {
        return new DialogueMessage(
                context.nextMessageNumber(),
                MessageRole.ASSISTANT,
                MessageType.THINKING,
                DialogueContent.text(thinking),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 构建 TOOL_CALL 消息
     *
     * @param context 收集器上下文
     * @param tc      工具调用项
     * @return TOOL_CALL 消息
     */
    private DialogueMessage buildToolCallMessage(CollectorContext context, ToolCallItem tc) {
        return new DialogueMessage(
                context.nextMessageNumber(),
                MessageRole.TOOL,
                MessageType.TOOL_CALL,
                DialogueContent.toolCall(tc.name(), tc.input(), null, tc.toolCallId()),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 构建最终 ASSISTANT 消息
     *
     * @param context 收集器上下文
     * @param text    完整分析报告
     * @return ASSISTANT 消息
     */
    private DialogueMessage buildAssistantMessage(CollectorContext context, String text) {
        return new DialogueMessage(
                context.nextMessageNumber(),
                MessageRole.ASSISTANT,
                MessageType.MESSAGE,
                DialogueContent.text(text),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 构建 ERROR 消息
     *
     * @param context 收集器上下文
     * @param error   错误信息
     * @return ERROR 消息
     */
    private DialogueMessage buildErrorMessage(CollectorContext context, String error) {
        return new DialogueMessage(
                context.nextMessageNumber(),
                MessageRole.ASSISTANT,
                MessageType.ERROR,
                DialogueContent.text(error),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 收集器上下文
     * <p>封装单个会话的分析状态，包括消息列表、进行中消息持有、最终响应、消息序号计数器等。
     * 进行中消息的创建与内容追加通过 {@code synchronized (this)} 保持一致。</p>
     */
    public static class CollectorContext {

        /** 会话 ID */
        private final String sessionId;
        /** 用户问题 */
        private final String userQuestion;
        /** 分析状态收集器（流式推送所需最小状态） */
        private final AnalysisSnapshotCollector collector;
        /** 对话消息列表（完整分析过程） */
        private final List<DialogueMessage> messages = new ArrayList<>();
        /** 按 toolCallId 关联的工具消息映射：TOOL_CALL_START 登记进行中调用消息（入参增量期），
         *  TOOL_CALL_END 移除；TOOL_RESULT_TEXT_DELTA 惰性登记进行中结果消息（结果增量期），TOOL_RESULT_END 移除 */
        private final Map<String, DialogueMessage> toolCallMessageMap = new HashMap<>();
        /** 最终响应文本 */
        private String finalResponse;
        /** 进行中的思考消息（THINKING_BLOCK_DELTA 实时追加，THINKING_BLOCK_END 收敛） */
        private DialogueMessage thinkingMessage;
        /** 进行中的助手报告消息（TEXT_BLOCK_DELTA 实时追加，AGENT_END 收敛；TOOL_CALL_START 时转思考） */
        private DialogueMessage assistantMessage;
        /** 消息序号计数器：用户消息已持久化占 messageNumber=1，因此从 2 开始 */
        private long messageNumberCounter = 2;

        /**
         * 构造方法
         *
         * @param sessionId 会话 ID
         * @param text      用户问题
         */
        public CollectorContext(String sessionId, String userQuestion) {
            this.sessionId = sessionId;
            this.userQuestion = userQuestion;
            this.collector = new AnalysisSnapshotCollector();
            // 用户消息作为第一条消息（messageNumber=1）加入列表，随全量快照落库，
            // 避免 updateMessages 覆盖整个 messages 列时丢失用户消息
            messages.add(DialogueMessage.userMessage(1, userQuestion));
        }

        /**
         * 获取会话 ID
         *
         * @return 会话 ID
         */
        public String sessionId() {
            return sessionId;
        }

        /**
         * 获取用户问题
         *
         * @return 用户问题
         */
        public String userQuestion() {
            return userQuestion;
        }

        /**
         * 获取分析状态收集器
         *
         * @return 收集器
         */
        public AnalysisSnapshotCollector collector() {
            return collector;
        }

        /**
         * 获取最终响应文本
         *
         * @return 最终响应
         */
        public String finalResponse() {
            return finalResponse;
        }

        /**
         * 设置最终响应文本
         *
         * @param finalResponse 最终响应
         */
        public void setFinalResponse(String finalResponse) {
            this.finalResponse = finalResponse;
        }

        /**
         * 获取下一个消息序号
         *
         * @return 消息序号
         */
        public long nextMessageNumber() {
            return messageNumberCounter++;
        }

        /**
         * 追加一条消息
         * <p>与进行中消息的创建/更新共用同一把锁，保证并发安全。</p>
         *
         * @param message 对话消息
         * @return 追加的消息（供调用方直接使用）
         */
        public DialogueMessage addMessage(DialogueMessage message) {
            synchronized (this) {
                messages.add(message);
                return message;
            }
        }

        /**
         * 按 toolCallId 将工具结果写入独立的 TOOL_RESULT 消息并收敛终态
         * <p>TOOL_RESULT_END 时调用：将收集器累积的最终结果一次性写入结果消息内容
         * （title=工具名、result=结果），success→COMPLETED / error→FAILED，随后移除 toolCallId 映射。
         * 若此前无 TOOL_RESULT_TEXT_DELTA（结果为空/异常路径），此处惰性创建结果消息承接最终结果。
         * 若分析中断（仅 TOOL_CALL_END 无 TOOL_RESULT_END），则仅产生 TOOL_CALL 消息、无结果消息。</p>
         *
         * @param toolCallId 工具调用 ID
         * @param result     工具结果文本（来自收集器累积的结果缓冲，可为 null）
         * @param success    是否成功
         * @return 收敛后的 TOOL_RESULT 消息；未找到对应工具调用项时返回 null
         */
        public DialogueMessage completeToolResult(String toolCallId, String result, boolean success) {
            synchronized (this) {
                if (toolCallId == null) {
                    return null;
                }
                DialogueMessage message = toolCallMessageMap.remove(toolCallId);
                if (message == null) {
                    // 无增量场景（结果为空/异常路径）：惰性创建结果消息
                    ToolCallItem tc = collector.getToolCallById(toolCallId);
                    if (tc == null) {
                        return null;
                    }
                    message = DialogueMessage.inProgressMessage(
                            nextMessageNumber(), MessageRole.TOOL, MessageType.TOOL_RESULT);
                    messages.add(message);
                }
                String name = message.getContent() != null ? message.getContent().title() : null;
                if (name == null) {
                    ToolCallItem tc = collector.getToolCallById(toolCallId);
                    name = tc != null ? tc.name() : "";
                }
                message.setContent(DialogueContent.toolResult(name, result != null ? result : "", toolCallId));
                if (success) {
                    message.complete();
                } else {
                    message.fail();
                }
                return message;
            }
        }

        /**
         * 获取消息列表引用
         * <p>调用方需在 {@code synchronized (this)} 内复制快照后再读取。</p>
         *
         * @return 消息列表
         */
        public List<DialogueMessage> getMessages() {
            return messages;
        }

        /**
         * 生成落库用消息快照
         * <p>进行中消息（IN_PROGRESS）在 DELTA 阶段仅持有占位内容，完整文本统一累积在
         * {@link AnalysisSnapshotCollector}（单一累积源）。落库前调用本方法，从收集器读取当前累积文本
         * 为进行中消息生成带完整内容的副本，保证「每事件落库」的历史内容与收敛后一致，
         * 且不污染内存中的消息对象（避免重复持有完整文本）。</p>
         *
         * @return 落库用消息快照列表（进行中消息为注入当前累积文本的副本，已收敛消息原样引用）
         */
        public List<DialogueMessage> getPersistenceSnapshot() {
            synchronized (this) {
                List<DialogueMessage> snapshot = new ArrayList<>(messages.size());
                for (DialogueMessage message : messages) {
                    snapshot.add(withLiveContent(message));
                }
                return snapshot;
            }
        }

        /**
         * 为消息生成带当前累积文本的副本
         * <p>仅对进行中的思考/报告/工具消息生效：从收集器读取当前累积文本，
         * 生成内容更新的副本；其余消息原样返回。
         * 进行中的 TOOL_CALL（入参增量期）注入累积入参，进行中的 TOOL_RESULT（结果增量期）注入累积结果。</p>
         *
         * @param message 原消息
         * @return 带当前累积文本的副本或原消息
         */
        private DialogueMessage withLiveContent(DialogueMessage message) {
            if (message.getStatus() != MessageStatus.IN_PROGRESS) {
                return message;
            }
            if (message == thinkingMessage) {
                String thinking = collector.getThinkingBuffer();
                if (thinking != null) {
                    return copyWithContent(message, DialogueContent.text(thinking));
                }
            } else if (message == assistantMessage) {
                String text = collector.getAssistantBuffer();
                if (text != null) {
                    return copyWithContent(message, DialogueContent.text(text));
                }
            }
            // 工具消息：进行中 TOOL_CALL 注入累积入参，进行中 TOOL_RESULT 注入累积结果
            String toolCallId = findToolCallIdByMessage(message);
            if (toolCallId != null) {
                ToolCallItem tc = collector.getToolCallById(toolCallId);
                if (message.getMessageType() == MessageType.TOOL_RESULT) {
                    String result = collector.getToolResult(toolCallId);
                    if (result != null) {
                        String name = message.getContent() != null ? message.getContent().title()
                                : (tc != null ? tc.name() : "");
                        return copyWithContent(message, DialogueContent.toolResult(name, result, toolCallId));
                    }
                } else {
                    String input = tc != null ? tc.input() : message.getContent().input();
                    if (input != null) {
                        return copyWithContent(message, DialogueContent.toolCall(
                                message.getContent().title(), input, null, toolCallId));
                    }
                }
            }
            return message;
        }

        /**
         * 查找消息对应的 toolCallId
         *
         * @param message 消息
         * @return 对应的 toolCallId；非工具消息返回 null
         */
        private String findToolCallIdByMessage(DialogueMessage message) {
            for (Map.Entry<String, DialogueMessage> entry : toolCallMessageMap.entrySet()) {
                if (entry.getValue() == message) {
                    return entry.getKey();
                }
            }
            return null;
        }

        /**
         * 复制消息并替换内容
         *
         * @param message 原消息
         * @param content 新内容
         * @return 复制后的消息
         */
        private DialogueMessage copyWithContent(DialogueMessage message, DialogueContent content) {
            return new DialogueMessage(message.getMessageNumber(), message.getRole(),
                    message.getMessageType(), content, message.getStatus(),
                    message.getStartTime(), message.getEndTime());
        }

        /**
         * 创建或返回进行中的 THINKING 消息
         * <p>思考文本增量由 {@link AnalysisSnapshotCollector#addThinkingStep} 统一累积（单一累积源，
         * StringBuilder 摊销 O(1)），此处仅负责创建进行中消息占位，内容在收敛（{@link #completeThinking}）
         * 或落库快照（{@link #getPersistenceSnapshot}）时一次性写入，避免逐 delta 全量复制字符串。</p>
         *
         * @param delta 思考文本增量（已由收集器累积，此处仅用于占位创建）
         * @return 进行中的思考消息
         */
        public DialogueMessage appendThinkingDelta(String delta) {
            synchronized (this) {
                if (thinkingMessage == null) {
                    thinkingMessage = DialogueMessage.inProgressMessage(
                            nextMessageNumber(), MessageRole.ASSISTANT, MessageType.THINKING);
                    messages.add(thinkingMessage);
                }
                return thinkingMessage;
            }
        }

        /**
         * 完成进行中的 THINKING 消息
         * <p>以最终思考文本覆盖内容并标记 COMPLETED，随后清空持有引用，消息保留在列表中。</p>
         *
         * @param finalText 最终思考文本（null 或空白时保留已累积内容）
         * @return 收敛后的思考消息；无进行中消息时返回 null
         */
        public DialogueMessage completeThinking(String finalText) {
            synchronized (this) {
                if (thinkingMessage == null) {
                    return null;
                }
                DialogueMessage message = thinkingMessage;
                thinkingMessage = null;
                if (finalText != null && !finalText.isBlank()) {
                    message.setContent(DialogueContent.text(finalText));
                }
                message.complete();
                return message;
            }
        }

        /**
         * 创建或返回进行中的 ASSISTANT 报告消息
         * <p>报告文本增量由 {@link AnalysisSnapshotCollector#addAssistantStep} 统一累积（单一累积源），
         * 此处仅负责创建进行中消息占位，内容在收敛（{@link #completeAssistant}）或落库快照
         * （{@link #getPersistenceSnapshot}）时一次性写入，避免逐 delta 全量复制字符串。</p>
         *
         * @param delta 报告文本增量（已由收集器累积，此处仅用于占位创建）
         * @return 进行中的报告消息
         */
        public DialogueMessage appendAssistantDelta(String delta) {
            synchronized (this) {
                if (assistantMessage == null) {
                    assistantMessage = DialogueMessage.inProgressMessage(
                            nextMessageNumber(), MessageRole.ASSISTANT, MessageType.MESSAGE);
                    messages.add(assistantMessage);
                }
                return assistantMessage;
            }
        }

        /**
         * 完成进行中的 ASSISTANT 报告消息
         * <p>以最终报告文本覆盖内容并标记 COMPLETED，随后清空持有引用，消息保留在列表中。
         * 若不存在进行中消息则返回 null，由调用方决定是否新建最终消息。</p>
         *
         * @param finalText 最终报告文本（null 或空白时保留已累积内容）
         * @return 收敛后的报告消息；无进行中消息时返回 null
         */
        public DialogueMessage completeAssistant(String finalText) {
            synchronized (this) {
                if (assistantMessage == null) {
                    return null;
                }
                DialogueMessage message = assistantMessage;
                assistantMessage = null;
                if (finalText != null && !finalText.isBlank()) {
                    message.setContent(DialogueContent.text(finalText));
                }
                message.complete();
                return message;
            }
        }

        /**
         * 取出进行中的 ASSISTANT 消息并清空持有引用（不移除列表中的消息）
         * <p>AGENT_END 兜底路径使用：用于读取累积的助手文本并收敛状态。</p>
         *
         * @return 进行中的助手消息；不存在时返回 null
         */
        public DialogueMessage takeAssistantMessage() {
            synchronized (this) {
                DialogueMessage message = assistantMessage;
                assistantMessage = null;
                return message;
            }
        }

        /**
         * 将未完成的助手叙述消息转换为 THINKING 消息并收敛为 COMPLETED
         * <p>TOOL_CALL_START 时调用：工具调用前的 TEXT_BLOCK 是过程叙述，
         * 应作为 THINKING 消息保留执行说明，而非混入最终报告。
         * 叙述文本从收集器缓冲（单一累积源）flush 后一次性写入消息内容。</p>
         *
         * @return 转换后的思考消息；无未完成的助手消息时返回 null
         */
        public DialogueMessage convertAssistantToThinking() {
            synchronized (this) {
                if (assistantMessage == null) {
                    return null;
                }
                DialogueMessage message = assistantMessage;
                assistantMessage = null;
                message.setRole(MessageRole.ASSISTANT);
                message.setMessageType(MessageType.THINKING);
                String narrative = collector.flushAssistantStep();
                if (narrative != null) {
                    message.setContent(DialogueContent.text(narrative));
                }
                message.complete();
                return message;
            }
        }

        /**
         * 创建进行中的 TOOL_CALL 消息并按 toolCallId 登记映射
         * <p>TOOL_CALL_START 时调用：立即创建消息占号，后续 DELTA 事件通过
         * {@link #appendToolInputDelta} 实时回填入参，TOOL_CALL_END 时由 {@link #completeToolCall} 收敛；
         * 工具结果由独立的 TOOL_RESULT 消息（{@link #appendToolResultDelta} 惰性创建）承载。</p>
         *
         * @param toolCallId 工具调用 ID
         * @param toolName   工具名
         * @return 新建的进行中 TOOL_CALL 消息
         */
        public DialogueMessage createToolCallMessage(String toolCallId, String toolName) {
            synchronized (this) {
                DialogueMessage message = DialogueMessage.inProgressMessage(
                        nextMessageNumber(), MessageRole.TOOL, MessageType.TOOL_CALL);
                message.setContent(DialogueContent.toolCall(toolName, "", null, toolCallId));
                messages.add(message);
                if (toolCallId != null) {
                    toolCallMessageMap.put(toolCallId, message);
                }
                return message;
            }
        }

        /**
         * 返回进行中的 TOOL_CALL 消息（工具调用入参增量）
         * <p>入参增量由 {@link AnalysisSnapshotCollector#appendToolCallInput} 统一累积到 ToolCallItem（单一累积源），
         * 此处仅返回消息引用触发落库判定，内容在 {@link #completeToolCall} 或落库快照
         * （{@link #getPersistenceSnapshot}）时一次性写入，避免逐 delta 全量复制字符串。</p>
         *
         * @param toolCallId 工具调用 ID
         * @param delta      入参增量文本（已由收集器累积，此处不重复使用）
         * @return 进行中的 TOOL_CALL 消息；未找到对应消息时返回 null
         */
        public DialogueMessage appendToolInputDelta(String toolCallId, String delta) {
            synchronized (this) {
                return toolCallMessageMap.get(toolCallId);
            }
        }

        /**
         * 追加工具结果增量到进行中的 TOOL_RESULT 消息（首次到达时惰性创建）
         * <p>结果增量由 {@link AnalysisSnapshotCollector#appendToolResultDelta} 统一累积（单一累积源），
         * 此处确保存在独立的结果消息占位：TOOL_CALL 消息已在 TOOL_CALL_END 收敛并移出映射，
         * 首次增量时新建 role=TOOL、type=TOOL_RESULT 的消息（工具名取自收集器 ToolCallItem）并按
         * toolCallId 登记映射；内容在 {@link #completeToolResult} 或落库快照
         * （{@link #getPersistenceSnapshot}）时一次性写入，避免逐 delta 全量复制字符串。</p>
         *
         * @param toolCallId 工具调用 ID
         * @param delta      结果增量文本（已由收集器累积，此处仅用于触发惰性创建）
         * @return 进行中的 TOOL_RESULT 消息；未找到对应工具调用项时返回 null
         */
        public DialogueMessage appendToolResultDelta(String toolCallId, String delta) {
            synchronized (this) {
                if (toolCallId == null) {
                    return null;
                }
                DialogueMessage message = toolCallMessageMap.get(toolCallId);
                if (message == null) {
                    ToolCallItem tc = collector.getToolCallById(toolCallId);
                    if (tc == null) {
                        return null;
                    }
                    message = DialogueMessage.inProgressMessage(
                            nextMessageNumber(), MessageRole.TOOL, MessageType.TOOL_RESULT);
                    message.setContent(DialogueContent.toolResult(tc.name(), "", toolCallId));
                    messages.add(message);
                    toolCallMessageMap.put(toolCallId, message);
                }
                return message;
            }
        }

        /**
         * 收敛 TOOL_CALL 消息为终态
         * <p>TOOL_CALL_END 时调用：以最终累积入参一次性写入消息内容并标记 COMPLETED，
         * 随后从 toolCallId 映射移除（结果由独立的 TOOL_RESULT 消息按同一 toolCallId 承接）。</p>
         *
         * @param toolCallId 工具调用 ID
         * @param finalInput 最终入参文本（来自收集器累积的 ToolCallItem）
         * @return 收敛后的 TOOL_CALL 消息；未找到对应消息时返回 null
         */
        public DialogueMessage completeToolCall(String toolCallId, String finalInput) {
            synchronized (this) {
                DialogueMessage message = toolCallMessageMap.remove(toolCallId);
                if (message == null) {
                    return null;
                }
                String name = message.getContent() != null ? message.getContent().title() : "";
                message.setContent(DialogueContent.toolCall(name, finalInput != null ? finalInput : "", null, toolCallId));
                message.complete();
                return message;
            }
        }
    }
}