package com.linkroa.deepdataagent.rag.controller.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnswerStreamEventCodec} 单元测试。
 * <p>钉住事件名与 data JSON 的契约：事件名同时出现在 SSE {@code event} 与 {@code data.type}；
 * 增量与终结事件的字段名固定；终结帧只含 {@code type} 与 {@code answer}，
 * 不携带降级标记或结构化来源；文本内容按 JSON 规则转义（中文与引号不破坏报文）。</p>
 *
 * @author DeepDataAgent
 */
class AnswerStreamEventCodecTest {

    /**
     * 场景：开始事件。
     * 预期：事件名与 type 一致，且不携带业务字段。
     */
    @Test
    void should_emitTypeOnly_when_started() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.started();

        // then
        assertEquals(AnswerStreamEventCodec.EVENT_STARTED, frame.event());
        assertTrue(frame.data().contains("\"type\":\"answer.started\""));
        assertFalse(frame.data().contains("delta"));
    }

    /**
     * 场景：增量事件携带中文与引号。
     * 预期：delta 字段完整保留，引号被 JSON 转义而非截断报文。
     */
    @Test
    void should_escapeDeltaText_when_deltaGivenQuotes() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.delta("他说\"你好\"");

        // then
        assertEquals(AnswerStreamEventCodec.EVENT_DELTA, frame.event());
        assertTrue(frame.data().contains("\"type\":\"answer.delta\""));
        assertTrue(frame.data().contains("他说\\\"你好\\\""));
    }

    /**
     * 场景：正常终结事件。
     * 预期：载荷只含 type 与 answer，不含 degraded / references。
     */
    @Test
    void should_carryOnlyAnswer_when_completed() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.completed("完整答案\n### References\n* [1] LightRag1.pdf");

        // then
        assertEquals(AnswerStreamEventCodec.EVENT_COMPLETED, frame.event());
        assertEquals("{\"type\":\"answer.completed\",\"answer\":\"完整答案\\n### References\\n* [1] LightRag1.pdf\"}",
                frame.data());
        assertFalse(frame.data().contains("degraded"));
        assertFalse(frame.data().contains("references"));
    }

    /**
     * 场景：答案为 null。
     * 预期：归一为空串，不产出 JSON null。
     */
    @Test
    void should_normalizeNullAnswerToEmptyString_when_completedGivenNull() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.completed(null);

        // then
        assertEquals("{\"type\":\"answer.completed\",\"answer\":\"\"}", frame.data());
    }

    /**
     * 场景：异常终结事件。
     * 预期：携带错误码与可读信息。
     */
    @Test
    void should_carryCodeAndMessage_when_error() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.error(
                AnswerStreamEventCodec.ERROR_CODE_INTERNAL, "模型不可用");

        // then
        assertEquals(AnswerStreamEventCodec.EVENT_ERROR, frame.event());
        assertTrue(frame.data().contains("\"code\":\"internal_error\""));
        assertTrue(frame.data().contains("\"message\":\"模型不可用\""));
    }

    /**
     * 场景：增量文本为 null。
     * 预期：归一为空串，不产出 JSON null。
     */
    @Test
    void should_normalizeNullDeltaToEmptyString_when_deltaGivenNull() {
        // given // when
        AnswerStreamEventCodec.Frame frame = AnswerStreamEventCodec.delta(null);

        // then
        assertTrue(frame.data().contains("\"delta\":\"\""));
    }
}