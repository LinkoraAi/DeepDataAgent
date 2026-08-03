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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件适配器
 * <p>负责将 AgentScope 的 {@link AgentEvent} 流适配为领域模型的 {@link DialogueMessage}，
 * 每个 SSE 事件（THINKING/TOOL_CALL/TOOL_RESULT/MESSAGE/ERROR）创建对应的消息并追加到
 * {@link CollectorContext} 的消息列表，供攒批持久化使用。</p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>按 sessionId 聚合 AgentEvent，每个事件映射为一条 {@link DialogueMessage} 追加到内存消息列表</li>
 *   <li>维护 {@link AnalysisSnapshotCollector} 收集流式推送所需的最小状态（思考缓冲、工具调用、结果缓冲）</li>
 *   <li>TEXT_DELTA 增量仅累积到 {@code assistantTextBuilder}，AGENT_END 时创建一条最终 ASSISTANT 消息</li>
 * </ul>
 *
 * <p>线程安全：每个 sessionId 对应独立的 CollectorContext，使用 ConcurrentHashMap 保证并发安全；
 * 追加消息与 flush 复制快照通过 {@code synchronized (context)} 保持一致。</p>
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
     * 用户消息（seq=1）已作为首条消息加入上下文，随攒批 flush 一并持久化，
     * 因此后续分析消息序号从 2 开始。</p>
     *
     * @param sessionId    会话 ID
     * @param userQuestion 用户问题
     * @return 新创建的收集器上下文
     */
    public CollectorContext registerContext(String sessionId, String userQuestion) {
        CollectorContext context = new CollectorContext(sessionId, userQuestion);
        contexts.put(sessionId, context);
        log.debug("EventAdapter: registered context for sessionId={}", sessionId);
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
        CollectorContext removed = contexts.remove(sessionId);
        if (removed != null) {
            log.debug("EventAdapter: unregistered context for sessionId={}", sessionId);
        }
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
     * <p>根据事件类型创建对应的 {@link DialogueMessage} 并追加到 CollectorContext 的消息列表，
     * 供攒批持久化使用。SSE 推送由调用方基于原始事件完成，因此本方法返回值不参与推送。</p>
     *
     * @param sessionId 会话 ID
     * @param event     AgentEvent 事件
     * @return 本次事件生成的对话消息列表（可能为空）
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
                // 思考过程增量事件，累积到缓冲区
                if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
                    collector.addThinkingStep(thinkingEvent.getDelta());
                }
                return List.of();

            case THINKING_BLOCK_END:
                // 思考块结束，flush 为完整思考消息
                String thinking = collector.flushThinkingStep();
                if (thinking != null) {
                    context.addMessage(buildThinkingMessage(context, thinking));
                }
                return List.of();

            case TOOL_CALL_START:
                // 工具调用开始，记录 toolCall 用于后续入参累积
                if (event instanceof ToolCallStartEvent toolCallEvent) {
                    collector.addToolCall(new ToolCallItem(
                            toolCallEvent.getToolCallName(), "",
                            System.currentTimeMillis(), toolCallEvent.getToolCallId()));
                }
                return List.of();

            case TOOL_CALL_DELTA:
                // 工具调用参数增量
                if (event instanceof ToolCallDeltaEvent toolCallDeltaEvent) {
                    collector.appendToolCallInput(toolCallDeltaEvent.getToolCallId(), toolCallDeltaEvent.getDelta());
                }
                return List.of();

            case TOOL_CALL_END:
                // 工具调用结束，创建 TOOL_CALL 消息
                if (event instanceof ToolCallEndEvent toolCallEndEvent) {
                    ToolCallItem tc = collector.getToolCallById(toolCallEndEvent.getToolCallId());
                    if (tc != null) {
                        context.addMessage(buildToolCallMessage(context, tc));
                    }
                }
                return List.of();

            case TOOL_RESULT_TEXT_DELTA:
                // 工具结果文本增量
                if (event instanceof ToolResultTextDeltaEvent deltaEvent) {
                    String toolCallId = deltaEvent.getToolCallId();
                    String delta = deltaEvent.getDelta();
                    if (toolCallId != null && delta != null) {
                        collector.appendToolResultDelta(toolCallId, delta);
                    }
                }
                return List.of();

            case TOOL_RESULT_END:
                // 工具调用完成，创建 TOOL_RESULT 消息
                if (event instanceof ToolResultEndEvent toolResultEvent) {
                    String toolName = toolResultEvent.getToolCallName();
                    String toolCallId = toolResultEvent.getToolCallId();
                    var state = toolResultEvent.getState();
                    boolean success = state != null && !"error".equalsIgnoreCase(state.getValue());
                    String resultContent = collector.getToolResult(toolCallId);
                    collector.setToolCallResult(toolCallId, resultContent, success);
                    context.addMessage(buildToolResultMessage(context, toolName, resultContent));
                }
                return List.of();

            case TEXT_BLOCK_DELTA:
                // 文本增量（Agent 最终回复），仅累积到 assistantTextBuilder，不创建消息
                if (event instanceof TextBlockDeltaEvent textEvent) {
                    context.appendAssistantText(textEvent.getDelta());
                }
                return List.of();

            case AGENT_RESULT:
                // Agent 最终结果
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
                // Agent 执行结束，创建最终 ASSISTANT 消息
                String report = context.getAssistantText();
                if (report == null || report.isBlank()) {
                    // 兜底：从最终响应提取
                    report = context.finalResponse();
                    if (report != null) {
                        report = TextCleaner.stripReasoningPreamble(report);
                    }
                }
                if (report != null && !report.isBlank()) {
                    context.addMessage(buildAssistantMessage(context, report));
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
                context.nextSequenceNumber(),
                MessageRole.THINKING,
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
                context.nextSequenceNumber(),
                MessageRole.TOOL,
                MessageType.TOOL_CALL,
                DialogueContent.toolCall(tc.name(), tc.input(), null),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 构建 TOOL_RESULT 消息
     *
     * @param context 收集器上下文
     * @param toolName 工具名
     * @param result   工具结果文本
     * @return TOOL_RESULT 消息
     */
    private DialogueMessage buildToolResultMessage(CollectorContext context, String toolName, String result) {
        return new DialogueMessage(
                context.nextSequenceNumber(),
                MessageRole.TOOL,
                MessageType.TOOL_RESULT,
                DialogueContent.toolCall(toolName != null ? toolName : "", null, result),
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
                context.nextSequenceNumber(),
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
                context.nextSequenceNumber(),
                MessageRole.ASSISTANT,
                MessageType.ERROR,
                DialogueContent.text(error),
                MessageStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * 收集器上下文
     * <p>封装单个会话的分析状态，包括消息列表、最终响应、序列号计数器等。
     * 追加消息与 flush 复制快照通过 {@code synchronized (this)} 保持一致。</p>
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
        /** 最终响应文本 */
        private String finalResponse;
        /** 助手文本增量累积器（TEXT_DELTA 累积，AGENT_END 时生成最终消息） */
        private final StringBuilder assistantTextBuilder = new StringBuilder();
        /** 序列号计数器：用户消息已持久化占 seq=1，因此从 2 开始 */
        private long sequenceNumberCounter = 2;

        /**
         * 构造方法
         *
         * @param sessionId    会话 ID
         * @param userQuestion 用户问题
         */
        public CollectorContext(String sessionId, String userQuestion) {
            this.sessionId = sessionId;
            this.userQuestion = userQuestion;
            this.collector = new AnalysisSnapshotCollector();
            // 用户消息作为第一条消息（seq=1）加入列表，随攒批 flush 一并持久化，
            // 避免 flush 覆盖整个 messages 列时丢失用户消息
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
         * 获取下一个序列号
         *
         * @return 序列号
         */
        public long nextSequenceNumber() {
            return sequenceNumberCounter++;
        }

        /**
         * 追加一条消息
         * <p>与 flush 复制快照共用同一把锁，保证并发安全。</p>
         *
         * @param message 对话消息
         */
        public void addMessage(DialogueMessage message) {
            synchronized (this) {
                messages.add(message);
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
         * 累积助手文本增量
         *
         * @param delta 文本增量
         */
        public void appendAssistantText(String delta) {
            if (delta != null) {
                assistantTextBuilder.append(delta);
            }
        }

        /**
         * 获取累积的助手完整文本
         *
         * @return 助手文本
         */
        public String getAssistantText() {
            return assistantTextBuilder.toString();
        }
    }
}