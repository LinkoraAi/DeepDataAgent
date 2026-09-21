package com.linkroa.deepdataagent.rag.infrastructure.client;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentscopeModelTransport}（AgentScope 框架模型通道）单元测试。
 * <p>以 Mock 的框架 {@link HttpTransport} 捕获最终出站 {@link HttpRequest}，
 * 实测并钉死 URL 归一化行为：版本化 base（含 {@code /v1}）+ 无版本资源路径
 * （{@code /embeddings}、{@code /rerank}）拼接不重复版本段；无凭证端点不下发
 * {@code Authorization} 头；非 2xx 折算为既有口径
 * {@code RuntimeException("模型接口调用失败: status=..., body=<512 截断摘要>")}。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class ModelApiChannelTest {

    /** 版本化 base URL 端点（台账约定形态） */
    private static final ModelProfileAccess.ResolvedEndpoint VERSIONED_ENDPOINT =
            new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", "bge-m3", 1024);

    /** 无版本段 base URL 端点 */
    private static final ModelProfileAccess.ResolvedEndpoint PLAIN_ENDPOINT =
            new ModelProfileAccess.ResolvedEndpoint("https://api.example", "sk-x", "bge-m3", null);

    /** 无凭证端点 */
    private static final ModelProfileAccess.ResolvedEndpoint NO_KEY_ENDPOINT =
            new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", null, "local-rerank", null);

    /** 单次调用超时（生产口径：embedding/rerank 60s） */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** 框架 HTTP 传输 Mock（捕获出站请求并编排响应） */
    @Mock
    private HttpTransport httpTransport;

    /** 被测通道（客户端构造缝注入 Mock 传输） */
    private AgentscopeModelTransport transport;

    /**
     * 每个用例重建通道，避免超时缓存跨用例串扰。
     */
    @BeforeEach
    void setUp() {
        transport = new AgentscopeModelTransport(timeout -> new OpenAIClient(httpTransport));
    }

    /**
     * 场景：版本化 baseUrl 追加 /embeddings 资源路径。
     * 预期：最终 URL 为 https://api.example/v1/embeddings（版本段不重复），POST + Bearer 头 + 原样请求体。
     */
    @Test
    void should_appendEmbeddingsPathToVersionedBase_when_post_given_versionedBaseUrl() {
        // given
        stubResponse(HttpResponse.builder().statusCode(200).body("{\"data\":[]}").build());

        // when
        String raw = transport.post(VERSIONED_ENDPOINT, "/embeddings", "{\"model\":\"bge-m3\"}", TIMEOUT);

        // then
        assertEquals("{\"data\":[]}", raw);
        HttpRequest request = capturedRequest();
        assertEquals("https://api.example/v1/embeddings", request.getUrl());
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer sk-x", request.getHeaders().get("Authorization"));
        assertEquals("{\"model\":\"bge-m3\"}", request.getBody());
    }

    /**
     * 场景：版本化 baseUrl 追加非 OpenAI 标准的 /rerank 资源路径（Cohere 兼容端点）。
     * 预期：仅做路径追加得到 https://api.example/v1/rerank，框架不改写资源段。
     */
    @Test
    void should_appendRerankPath_when_post_given_versionedBaseUrl() {
        // given
        stubResponse(HttpResponse.builder().statusCode(200).body("{\"results\":[]}").build());

        // when
        transport.post(VERSIONED_ENDPOINT, "/rerank", "{}", TIMEOUT);

        // then
        assertEquals("https://api.example/v1/rerank", capturedRequest().getUrl());
    }

    /**
     * 场景：无版本段 baseUrl（自部署网关常见形态）。
     * 预期：URL 与旧裸通道拼接语义一致——base 直接追加资源路径。
     */
    @Test
    void should_concatenatePlainBase_when_post_given_baseUrlWithoutVersion() {
        // given
        stubResponse(HttpResponse.builder().statusCode(200).body("{}").build());

        // when
        transport.post(PLAIN_ENDPOINT, "/embeddings", "{}", TIMEOUT);

        // then
        assertEquals("https://api.example/embeddings", capturedRequest().getUrl());
    }

    /**
     * 场景：端点未配置凭证（apiKey 为空）。
     * 预期：出站请求不携带 Authorization 头（无鉴权端点可正常调用）。
     */
    @Test
    void should_omitAuthorizationHeader_when_post_given_endpointWithoutApiKey() {
        // given
        stubResponse(HttpResponse.builder().statusCode(200).body("{}").build());

        // when
        transport.post(NO_KEY_ENDPOINT, "/rerank", "{}", TIMEOUT);

        // then
        assertFalse(capturedRequest().getHeaders().containsKey("Authorization"),
                "无凭证端点不得下发 Authorization 头");
    }

    /**
     * 场景：端点返回 429 且响应体超长。
     * 预期：折算为运行时异常，消息含状态码与 512 截断摘要（脱敏长错误页），原始异常保留为 cause。
     */
    @Test
    void should_throwRuntimeExceptionWithTruncatedSummary_when_post_given_rateLimitedLongBody() {
        // given
        String longBody = "x".repeat(600);
        stubResponse(HttpResponse.builder().statusCode(429).body(longBody).build());

        // when
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> transport.post(VERSIONED_ENDPOINT, "/embeddings", "{}", TIMEOUT));

        // then
        String message = exception.getMessage();
        assertTrue(message.contains("模型接口调用失败: status=429"),
                "异常消息需含既有状态码口径: " + message);
        String body = message.substring(message.indexOf("body=") + "body=".length());
        assertEquals(512 + "...".length(), body.length(), "响应体摘要须截断到 512 + 省略号: " + body.length());
        assertTrue(body.endsWith("..."), "截断标记缺失");
    }

    /**
     * 场景：网络层失败（框架传输抛 HttpTransportException）。
     * 预期：折算为携带框架消息的运行时异常，供调用方按既有降级语义处理。
     */
    @Test
    void should_wrapTransportFailure_when_post_given_httpTransportThrows() {
        // given
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenThrow(new HttpTransportException("connect timed out"));

        // when
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> transport.post(VERSIONED_ENDPOINT, "/embeddings", "{}", TIMEOUT));

        // then
        assertTrue(exception.getMessage().startsWith("模型接口调用失败: "),
                "异常消息需保持既有前缀: " + exception.getMessage());
    }

    /**
     * 场景：2xx 但响应体为空（语义失败）。
     * 预期：折算为运行时异常而非静默返回空串。
     */
    @Test
    void should_throw_when_post_given_emptySuccessBody() {
        // given
        stubResponse(HttpResponse.builder().statusCode(200).body("").build());

        // when / then
        assertThrows(RuntimeException.class,
                () -> transport.post(VERSIONED_ENDPOINT, "/embeddings", "{}", TIMEOUT));
    }

    /**
     * 编排传输层返回指定响应。
     */
    private void stubResponse(HttpResponse response) {
        when(httpTransport.execute(any(HttpRequest.class))).thenReturn(response);
    }

    /**
     * 捕获唯一一次出站请求。
     */
    private HttpRequest capturedRequest() {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());
        return captor.getValue();
    }
}
