package com.linkroa.deepdataagent.rag.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link LlmCallLogSupport} 单元测试（最小化日志口径）。
 * <p>覆盖摘要标识、字符数、耗时非负，以及「字段组装/日志输出异常被吞掉」；
 * 端点主机、正文内联与输入正文段能力已随日志最小化契约移除，对应用例一并删除。</p>
 *
 * @author DeepDataAgent
 */
class LlmCallLogSupportTest {

    /**
     * 场景：摘要标识与字符数。
     * 预期：摘要为 8 位十六进制且稳定；空白摘要返回占位符；null 字符数为 0。
     */
    @Test
    void should_returnStableDigestAndChars_when_digestAndChars_given_text() {
        // given
        String text = "same prompt";

        // when / then
        assertEquals(LlmCallLogSupport.digest(text), LlmCallLogSupport.digest(text));
        assertEquals(8, LlmCallLogSupport.digest(text).length());
        assertEquals("-", LlmCallLogSupport.digest(""));
        assertEquals(0, LlmCallLogSupport.chars(null));
        assertEquals(text.length(), LlmCallLogSupport.chars(text));
    }

    /**
     * 场景：耗时计算。
     * 预期：非负毫秒。
     */
    @Test
    void should_returnNonNegativeMillis_when_elapsedMillis_given_startNanos() {
        // given / when / then
        long elapsed = LlmCallLogSupport.elapsedMillis(System.nanoTime());
        assertEquals(true, elapsed >= 0L);
    }

    /**
     * 场景：字段组装抛异常。
     * 预期：异常被吞掉，不向上传播。
     */
    @Test
    void should_swallowException_when_info_given_throwingSupplier() {
        // given
        Logger log = mock(Logger.class);
        Supplier<String> broken = () -> {
            throw new IllegalStateException("assemble failed");
        };

        // when / then
        assertDoesNotThrow(() -> LlmCallLogSupport.info(log, broken));
        assertDoesNotThrow(() -> LlmCallLogSupport.warn(log, broken, new RuntimeException("cause")));
    }

    /**
     * 场景：正常组装。
     * 预期：按原样输出日志。
     */
    @Test
    void should_emitMessage_when_info_given_validSupplier() {
        // given
        Logger log = mock(Logger.class);

        // when
        LlmCallLogSupport.info(log, () -> "llm.call | status=OK");

        // then
        verify(log).info("llm.call | status=OK");
    }
}
