package com.linkroa.deepdataagent.rag.infrastructure.client;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIClient;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 AgentScope OpenAI 扩展的模型端点通道（{@link ModelTransport} 生产实现）。
 * <p>真实 HTTP 由框架 {@code OpenAIClient.callApi} 发起：URL 归一化（版本化 base 与
 * {@code /embeddings}、{@code /rerank} 资源路径的去重拼接）、Bearer 头、类型化异常
 * （非 2xx 抛 {@link OpenAIException}，携带状态码与响应体）均由框架承担。</p>
 * <p>单次请求超时统一取框架默认值（{@link HttpTransportConfig#defaults()}：连接 30 秒、
 * 响应 5 分钟），调用方不再传入自定义超时；客户端实例在通道内共享，连接池随之复用。</p>
 * <p>异常统一折算为既有口径 {@code RuntimeException("模型接口调用失败: status=..., body=<512 截断摘要>")}，
 * 摘要不落凭证，日志仅 WARN 级别。</p>
 *
 * @author DeepDataAgent
 */
final class AgentscopeModelTransport implements ModelTransport {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeModelTransport.class);

    /** 响应体摘要截断阈值（避免超长错误页污染日志与异常消息） */
    private static final int BODY_LOG_LIMIT = 512;

    /** 默认单例（线程安全；框架客户端不可变、可共享） */
    private static final AgentscopeModelTransport SHARED = new AgentscopeModelTransport(buildClient());

    /** 框架客户端（统一默认超时） */
    private final OpenAIClient client;

    /**
     * 测试缝构造器：注入指定框架客户端。
     *
     * @param client 框架客户端
     */
    AgentscopeModelTransport(OpenAIClient client) {
        this.client = client;
    }

    /**
     * 共享默认实例。
     *
     * @return 生产通道单例
     */
    static AgentscopeModelTransport shared() {
        return SHARED;
    }

    @Override
    public String post(ModelProfileAccess.ResolvedEndpoint endpoint, String resourcePath, String jsonBody) {
        try {
            return client.callApi(endpoint.apiKey(), endpoint.baseUrl(), resourcePath, jsonBody);
        } catch (OpenAIException e) {
            throw toCallFailure(e);
        }
    }

    /**
     * 构建框架客户端：传输配置全部取框架默认值（连接 30 秒、响应 5 分钟）。
     */
    private static OpenAIClient buildClient() {
        HttpTransport transport = JdkHttpTransport.builder()
                .config(HttpTransportConfig.defaults())
                .build();
        return new OpenAIClient(transport);
    }

    /**
     * 框架异常折算为既有失败口径：非 2xx 状态码时输出 {@code status=..., body=<摘要>}，
     * 无状态码或 2xx 语义失败（如空响应体）时输出框架消息。
     */
    private static RuntimeException toCallFailure(OpenAIException e) {
        Integer statusCode = e.getStatusCode();
        if (ObjectUtils.isEmpty(statusCode) || (statusCode >= 200 && statusCode < 300)) {
            return new RuntimeException("模型接口调用失败: " + e.getMessage(), e);
        }
        String summary = abbreviate(e.getResponseBody());
        log.warn("模型接口调用失败: status={}, body={}", statusCode, summary);
        return new RuntimeException("模型接口调用失败: status=" + statusCode + ", body=" + summary, e);
    }

    /**
     * 响应体摘要截断。
     */
    private static String abbreviate(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        return body.length() <= BODY_LOG_LIMIT ? body : body.substring(0, BODY_LOG_LIMIT) + "...";
    }
}
