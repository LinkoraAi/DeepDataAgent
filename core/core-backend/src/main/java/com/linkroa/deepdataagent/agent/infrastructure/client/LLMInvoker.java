package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.exception.AnalysisCancelledException;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 通用调用器
 * <p>封装流式 LLM 调用的公共能力：消息构建、文本累积、markdown 清理、空响应校验，
 * 以及带指数退避的瞬时错误重试与用户取消语义。
 * 与具体业务提示词解耦，供各业务客户端（NL2SQL/图表/标题）复用。</p>
 */
@Component
public class LLMInvoker {

    private static final Logger log = LoggerFactory.getLogger(LLMInvoker.class);

    /** 最大重试次数 */
    private static final int MAX_LLM_RETRIES = 5;

    private final ChatModelManager chatModelManager;

    /**
     * 构造方法
     *
     * @param chatModelManager ChatModel 实例管理器
     */
    public LLMInvoker(ChatModelManager chatModelManager) {
        this.chatModelManager = chatModelManager;
    }

    /**
     * 调用 LLM 获取文本结果（无流式回调）
     *
     * @param modelConfigId 模型配置 ID
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @return LLM 返回的文本
     */
    public String invoke(Long modelConfigId, String systemPrompt, String userPrompt) {
        return invoke(modelConfigId, systemPrompt, userPrompt, null);
    }

    /**
     * 调用 LLM 获取文本结果
     * <p>支持瞬时网络错误自动重试（指数退避），用户取消时抛出 {@link AnalysisCancelledException}。</p>
     *
     * @param modelConfigId 模型配置 ID
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param sessionId     会话 ID（保留参数，供后续流式回调扩展，可为 null）
     * @return LLM 返回的文本
     */
    public String invoke(Long modelConfigId, String systemPrompt, String userPrompt, String sessionId) {
        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(systemPrompt)
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userPrompt)
                        .build()
        );

        Exception lastException = null;
        int retried = 0;
        for (int attempt = 1; attempt <= MAX_LLM_RETRIES; attempt++) {
            try {
                // 每次重试都重新获取 model，瞬时错误后缓存已清除会重建新实例
                ChatModelBase model = chatModelManager.getChatModel(modelConfigId);

                StringBuilder resultBuilder = new StringBuilder();

                model.stream(messages, null, null)
                        .doOnNext(response -> {
                            response.getContent().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .forEach(resultBuilder::append);
                        })
                        .blockLast();

                String result = resultBuilder.toString().strip();
                if (result.isEmpty()) {
                    throw new DeepDataAgentException("LLM 返回空响应");
                }
                // 清理可能的 markdown 代码块标记
                if (result.startsWith("```")) {
                    result = result.replaceAll("^```[a-zA-Z]*\\n?", "")
                            .replaceAll("\\n?```$", "")
                            .strip();
                }
                return result;
            } catch (DeepDataAgentException e) {
                throw e;
            } catch (Exception e) {
                // 数据分析被用户主动取消：恢复中断标志，记录 info 日志，以取消语义抛出，不重试
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    log.info("LLM 调用被用户取消，停止分析: {}", LogMasker.mask(e.getMessage()));
                    throw new AnalysisCancelledException("LLM 调用被取消: " + e.getMessage());
                }
                lastException = e;
                if (attempt < MAX_LLM_RETRIES && isTransientError(e)) {
                    // 清除缓存的 model 实例，下次重试时重建新连接
                    chatModelManager.evictCache(modelConfigId);
                    retried++;
                    long backoffMs = (long) Math.pow(2, attempt) * 1000L;
                    log.warn("LLM 调用失败（第 {}/{} 次尝试），清除缓存并重建连接，{}ms 后重试: {}",
                            attempt, MAX_LLM_RETRIES, backoffMs,
                            LogMasker.mask(e.getMessage()));
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        log.warn("LLM 调用失败（已重试 {} 次）: {}", retried,
                lastException != null ? LogMasker.mask(lastException.getMessage()) : "unknown");
        throw new DeepDataAgentException(
                "LLM 调用失败: " + (lastException != null ? lastException.getMessage() : "unknown"));
    }

    /**
     * 判断异常是否为瞬时网络错误（可重试）
     * <p>包括连接关闭、超时、连接重置等场景。</p>
     *
     * @param e 异常
     * @return true 表示可重试的瞬时错误
     */
    private boolean isTransientError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return message.contains("closed")
                || message.contains("timeout")
                || message.contains("connection reset")
                || message.contains("connection refused")
                || message.contains("broken pipe")
                || message.contains("eof")
                || message.contains("premature")
                || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException;
    }
}