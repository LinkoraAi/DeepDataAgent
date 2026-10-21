package com.linkroa.deepdataagent.rag.controller.stream;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 检索答案流的事件编解码（SSE {@code event} 名与 {@code data} JSON 的唯一装配点）。
 * <p>事件名同时出现在 SSE 的 {@code event} 字段与 {@code data.type} 字段：前者供
 * {@code EventSource} 语义分发，后者让帧自描述、便于客户端统一解析与日志留痕。</p>
 * <p>终止事件只有两种：{@link #EVENT_COMPLETED}（正常结束，载荷只含完整答案）
 * 与 {@link #EVENT_ERROR}（异常结束）。一次流 MUST 恰好以其中之一收尾。</p>
 *
 * @author DeepDataAgent
 */
public final class AnswerStreamEventCodec {

    /** 事件：检索链路已进入答案生成阶段 */
    public static final String EVENT_STARTED = "answer.started";

    /** 事件：答案增量 */
    public static final String EVENT_DELTA = "answer.delta";

    /** 事件：正常终结（载荷只含完整答案） */
    public static final String EVENT_COMPLETED = "answer.completed";

    /** 事件：异常终结 */
    public static final String EVENT_ERROR = "answer.error";

    /** 错误码：服务端内部异常（建流后检索或作答失败且无法降级） */
    public static final String ERROR_CODE_INTERNAL = "internal_error";

    /** JSON 编解码器（无状态，可安全共享） */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private AnswerStreamEventCodec() {
    }

    /**
     * 单个 SSE 帧（事件名 + data JSON）。
     *
     * @param event SSE {@code event} 名
     * @param data  SSE {@code data} JSON 文本
     */
    public record Frame(String event, String data) {
    }

    /**
     * 构造开始事件。
     *
     * @return 开始帧
     */
    public static Frame started() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", EVENT_STARTED);
        return new Frame(EVENT_STARTED, node.toString());
    }

    /**
     * 构造增量事件。
     *
     * @param delta 本次新增文本（空值归一为空串）
     * @return 增量帧
     */
    public static Frame delta(String delta) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", EVENT_DELTA);
        node.put("delta", StringUtils.defaultString(delta));
        return new Frame(EVENT_DELTA, node.toString());
    }

    /**
     * 构造正常终结事件。
     *
     * @param answer 完整答案文本（空值归一为空串；引用信息由模型在正文 References 段中生成）
     * @return 终结帧
     */
    public static Frame completed(String answer) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", EVENT_COMPLETED);
        node.put("answer", StringUtils.defaultString(answer));
        return new Frame(EVENT_COMPLETED, node.toString());
    }

    /**
     * 构造异常终结事件。
     *
     * @param code    错误码（见本类常量）
     * @param message 可读错误信息
     * @return 异常帧
     */
    public static Frame error(String code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", EVENT_ERROR);
        node.put("code", StringUtils.defaultString(code));
        node.put("message", StringUtils.defaultString(message));
        return new Frame(EVENT_ERROR, node.toString());
    }
}