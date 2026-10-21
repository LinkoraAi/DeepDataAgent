package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.port.LlmFailureKind;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LlmCallFailureClassifier} 单元测试。
 * <p>覆盖 HTTP 状态码优先级、类型判定、消息语义兜底、因果链递归与未知兜底。</p>
 *
 * @author DeepDataAgent
 */
class LlmCallFailureClassifierTest {

    /**
     * 场景：超时异常。
     * 预期：归类为 TIMEOUT。
     */
    @Test
    void should_returnTimeout_when_classify_given_timeoutException() {
        // given / when / then
        assertEquals(LlmFailureKind.TIMEOUT, LlmCallFailureClassifier.classify(new TimeoutException("t")));
    }

    /**
     * 场景：HTTP 429。
     * 预期：归类为 RATE_LIMIT。
     */
    @Test
    void should_returnRateLimit_when_classify_given_status429() {
        // given / when / then
        assertEquals(LlmFailureKind.RATE_LIMIT, LlmCallFailureClassifier.classify(
                new HttpTransportException("rate limited", 429, "{}")));
    }

    /**
     * 场景：HTTP 5xx。
     * 预期：归类为 SERVER_ERROR。
     */
    @Test
    void should_returnServerError_when_classify_given_status503() {
        // given / when / then
        assertEquals(LlmFailureKind.SERVER_ERROR, LlmCallFailureClassifier.classify(
                new HttpTransportException("unavailable", 503, "{}")));
    }

    /**
     * 场景：HTTP 4xx（非 429）。
     * 预期：归类为 CLIENT_ERROR（不可重试）。
     */
    @Test
    void should_returnClientError_when_classify_given_status400() {
        // given / when / then
        assertEquals(LlmFailureKind.CLIENT_ERROR, LlmCallFailureClassifier.classify(
                new HttpTransportException("bad request", 400, "{}")));
    }

    /**
     * 场景：连接失败。
     * 预期：归类为 CONNECTION。
     */
    @Test
    void should_returnConnection_when_classify_given_connectException() {
        // given / when / then
        assertEquals(LlmFailureKind.CONNECTION, LlmCallFailureClassifier.classify(
                new ConnectException("refused")));
        assertEquals(LlmFailureKind.CONNECTION, LlmCallFailureClassifier.classify(
                new IOException("socket closed")));
    }

    /**
     * 场景：状态码异常被包装在普通运行时异常中。
     * 预期：沿因果链递归命中 SERVER_ERROR。
     */
    @Test
    void should_walkCauseChain_when_classify_given_wrappedTransportException() {
        // given
        RuntimeException wrapped = new RuntimeException("模型接口调用失败",
                new HttpTransportException("bad gateway", 502, "{}"));

        // when / then
        assertEquals(LlmFailureKind.SERVER_ERROR, LlmCallFailureClassifier.classify(wrapped));
    }

    /**
     * 场景：无类型信息，仅消息含 timeout 语义。
     * 预期：归类为 TIMEOUT。
     */
    @Test
    void should_returnTimeout_when_classify_given_timeoutMessageOnly() {
        // given / when / then
        assertEquals(LlmFailureKind.TIMEOUT, LlmCallFailureClassifier.classify(
                new IllegalStateException("connect timed out")));
    }

    /**
     * 场景：无法归类的异常。
     * 预期：归类为 UNKNOWN（含 null 入参）。
     */
    @Test
    void should_returnUnknown_when_classify_given_unclassifiableError() {
        // given / when / then
        assertEquals(LlmFailureKind.UNKNOWN, LlmCallFailureClassifier.classify(
                new IllegalStateException("something else")));
        assertEquals(LlmFailureKind.UNKNOWN, LlmCallFailureClassifier.classify(null));
    }
}
