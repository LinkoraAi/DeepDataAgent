package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.port.LlmFailureKind;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 模型调用失败分类器（观测与归因用）。
 * <p>沿异常因果链自外向内匹配，首个可判定节点即为结果：HTTP 状态码优先
 * （429 → 限流、5xx → 服务端、其余 4xx → 客户端），其次类型（超时、连接、IO），
 * 最后以消息中的 {@code timeout/timed out} 语义兜底。未命中任何规则归入
 * {@link LlmFailureKind#UNKNOWN}。</p>
 * <p>只做只读判定，不改变异常传播；不读取凭证或提示词内容。</p>
 *
 * @author DeepDataAgent
 */
final class LlmCallFailureClassifier {

    /** 限流状态码 */
    private static final int STATUS_RATE_LIMIT = 429;

    /** 服务端错误状态码下界 */
    private static final int STATUS_SERVER_ERROR_FROM = 500;

    /** 客户端错误状态码下界 */
    private static final int STATUS_CLIENT_ERROR_FROM = 400;

    /**
     * 工具类私有构造器。
     */
    private LlmCallFailureClassifier() {
    }

    /**
     * 判定失败分类。
     *
     * @param error 原始异常（可空）
     * @return 失败分类（非空，无法判定时 {@link LlmFailureKind#UNKNOWN}）
     */
    static LlmFailureKind classify(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = nextCause(cursor)) {
            LlmFailureKind byStatus = classifyByStatus(statusOf(cursor));
            if (byStatus != null) {
                return byStatus;
            }
            if (cursor instanceof TimeoutException || cursor instanceof HttpTimeoutException) {
                return LlmFailureKind.TIMEOUT;
            }
            if (cursor instanceof ConnectException) {
                return LlmFailureKind.CONNECTION;
            }
            if (cursor instanceof IOException) {
                return LlmFailureKind.CONNECTION;
            }
            if (containsTimeoutSemantic(cursor.getMessage())) {
                return LlmFailureKind.TIMEOUT;
            }
        }
        return LlmFailureKind.UNKNOWN;
    }

    /**
     * 提取异常携带的 HTTP 状态码（框架传输异常与模型 HTTP 异常共用口径）。
     *
     * @param error 异常节点
     * @return 状态码；不可得返回 {@code null}
     */
    private static Integer statusOf(Throwable error) {
        if (error instanceof HttpTransportException transportException) {
            return transportException.getStatusCode();
        }
        if (error instanceof ModelHttpException httpException) {
            return httpException.getStatusCode();
        }
        return null;
    }

    /**
     * 按 HTTP 状态码分类。
     *
     * @param statusCode 状态码（可空）
     * @return 分类；状态码缺失或不在错误区间返回 {@code null}
     */
    private static LlmFailureKind classifyByStatus(Integer statusCode) {
        if (statusCode == null || statusCode < STATUS_CLIENT_ERROR_FROM) {
            return null;
        }
        if (statusCode == STATUS_RATE_LIMIT) {
            return LlmFailureKind.RATE_LIMIT;
        }
        if (statusCode >= STATUS_SERVER_ERROR_FROM) {
            return LlmFailureKind.SERVER_ERROR;
        }
        return LlmFailureKind.CLIENT_ERROR;
    }

    /**
     * 消息是否表达超时语义（兜底规则，覆盖被包装成普通运行时异常的超时）。
     *
     * @param message 异常消息（可空）
     * @return 命中返回 true
     */
    private static boolean containsTimeoutSemantic(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out");
    }

    /**
     * 取下一层 cause（自引用时终止，避免死循环）。
     *
     * @param error 当前异常
     * @return 下一层 cause；无或自引用返回 {@code null}
     */
    private static Throwable nextCause(Throwable error) {
        Throwable cause = error.getCause();
        return cause == error ? null : cause;
    }
}
