package com.linkroa.deepdataagent.shared.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErrorEnvelope} 契约不变量单测（6.1 统一错误信封）。
 */
class ErrorEnvelopeTest {

    @Test
    void should_buildErrorEnvelopeShape_when_of_given_conflictType() {
        // given
        ErrorType type = ErrorType.CONFLICT_ERROR;

        // when
        ErrorEnvelope envelope = ErrorEnvelope.of(type, "会话正在执行中");

        // then（顶层 type 恒 error、error.type snake_case 值、request_id 自动生成）
        assertEquals("error", envelope.type());
        assertEquals("conflict_error", envelope.error().type());
        assertEquals("会话正在执行中", envelope.error().message());
        assertNotNull(envelope.requestId());
        assertTrue(envelope.requestId().length() > 10);
    }

    @Test
    void should_fillDefaults_when_construct_given_blankRequestIdAndType() {
        // given（request_id / type 空白 → 紧凑构造器补默认）
        ErrorEnvelope envelope = new ErrorEnvelope(
                new ErrorEnvelope.ErrorBody("api_error", "boom"), " ", null);

        // when & then
        assertNotNull(envelope.requestId());
        assertFalseBlank(envelope.requestId());
        assertEquals(ErrorEnvelope.ENVELOPE_TYPE, envelope.type());
    }

    @Test
    void should_rejectNull_when_construct_given_nullErrorBody() {
        // given & when & then（error 体必填）
        assertThrows(NullPointerException.class,
                () -> new ErrorEnvelope(null, "req-1", "error"));
        assertThrows(NullPointerException.class,
                () -> new ErrorEnvelope(new ErrorEnvelope.ErrorBody(null, "m"), "req-1", "error"));
    }

    private static void assertFalseBlank(String value) {
        assertTrue(!value.isBlank());
    }
}
