package com.linkroa.deepdataagent.rag.infrastructure.client;

import java.time.Duration;

/**
 * 模型端点 HTTP 通道薄接口（包内可见，隔离传输实现并支撑单测注入）。
 * <p>生产实现为 {@link AgentscopeModelTransport}（基于 AgentScope OpenAI 扩展的
 * {@code OpenAIClient.callApi}）；向量化与重排客户端只依赖本接口的
 * 「POST JSON → 原始响应文本」语义，报文组装与响应解析仍由调用方负责
 * （保住真批量 {@code /embeddings} 与 Cohere 式 {@code /rerank} 协议语义）。</p>
 *
 * @author DeepDataAgent
 */
interface ModelTransport {

    /**
     * 向模型端点资源路径 POST JSON 请求体。
     *
     * @param endpoint     已解析的模型端点（{@code baseUrl} 为版本化 base，如 {@code https://host/v1}）
     * @param resourcePath 资源路径（如 {@code /embeddings}、{@code /rerank}，不含版本段）
     * @param jsonBody     请求体 JSON 文本
     * @param timeout      单次请求超时
     * @return 原始响应体文本
     * @throws RuntimeException HTTP 非 2xx、网络异常或超时（消息含状态码与截断的响应体摘要，不含凭证）
     */
    String post(ModelProfileAccess.ResolvedEndpoint endpoint, String resourcePath,
                String jsonBody, Duration timeout);
}
