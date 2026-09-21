package com.linkroa.deepdataagent.rag.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OpenAiCompatibleEmbeddingClient} 单元测试（补齐通道 Mock 基线）。
 * <p>真实 HTTP 不触达：以 Mock 的 {@link ModelTransport}（AgentScope 框架通道接口）编排响应并
 * 捕获请求体。核心断言：真批量（一次调用提交全部文本）、响应按 index 归位、维度校验、
 * 协议异常与参数非法分支。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleEmbeddingClientTest {

    /** 嵌入模型 profileId */
    private static final String PROFILE_ID = "model-embedding";

    /** 解析后的模型名称 */
    private static final String MODEL_NAME = "bge-m3";

    /** 期望的向量化资源路径 */
    private static final String EMBEDDINGS_PATH = "/embeddings";

    /** 断言浮点误差容忍度 */
    private static final float DELTA = 1E-6F;

    /** 模型配置解析端口 Mock */
    @Mock
    private ModelProfileAccess modelProfileAccess;

    /** 模型端点 HTTP 通道 Mock */
    @Mock
    private ModelTransport modelTransport;

    /** 被测向量化客户端 */
    @InjectMocks
    private OpenAiCompatibleEmbeddingClient embeddingClient;

    /**
     * 场景：单条文本向量化。
     * 预期：一次 /embeddings 调用，返回解析后的向量。
     */
    @Test
    void should_returnVector_when_embed_given_singleTextResponse() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString(), any(Duration.class)))
                .thenReturn("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");

        // when
        float[] vector = embeddingClient.embed(PROFILE_ID, "文本");

        // then
        assertEquals(2, vector.length);
        assertEquals(0.1F, vector[0], DELTA);
    }

    /**
     * 场景（核心）：三条文本批量向量化。
     * 预期：仅一次通道调用且请求体 input 为全量数组；响应乱序 index 按下标归位。
     */
    @Test
    void should_issueSingleBatchCallAndBackfillByIndex_when_embedBatch_given_threeTexts() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString(), any(Duration.class)))
                .thenReturn("{\"data\":["
                        + "{\"index\":2,\"embedding\":[0.3]},"
                        + "{\"index\":0,\"embedding\":[0.1]},"
                        + "{\"index\":1,\"embedding\":[0.2]}]}");

        // when
        List<float[]> vectors = embeddingClient.embedBatch(PROFILE_ID, List.of("甲", "乙", "丙"));

        // then
        assertEquals(3, vectors.size());
        assertEquals(0.1F, vectors.get(0)[0], DELTA);
        assertEquals(0.2F, vectors.get(1)[0], DELTA);
        assertEquals(0.3F, vectors.get(2)[0], DELTA);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(modelTransport, times(1)).post(any(), eq(EMBEDDINGS_PATH), bodyCaptor.capture(), any(Duration.class));
        assertTrue(bodyCaptor.getValue().contains("\"input\":[\"甲\",\"乙\",\"丙\"]"),
                "批量必须走单请求数组 input: " + bodyCaptor.getValue());
    }

    /**
     * 场景：模型配置声明维度 1024，响应向量长度为 2。
     * 预期：抛维度不一致异常（防止错误维度向量污染索引）。
     */
    @Test
    void should_throwRuntimeException_when_embed_given_dimensionMismatch() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(1024));
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> embeddingClient.embed(PROFILE_ID, "文本"));
        assertTrue(exception.getMessage().contains("维度"), "异常信息需指明维度不一致: " + exception.getMessage());
    }

    /**
     * 场景：批量响应 data 数量少于请求文本数。
     * 预期：抛协议异常，不以空向量补齐。
     */
    @Test
    void should_throwRuntimeException_when_embedBatch_given_dataSizeMismatch() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> embeddingClient.embedBatch(PROFILE_ID, List.of("甲", "乙")));
        assertTrue(exception.getMessage().contains("数量不符"), "异常信息需指明数量不符: " + exception.getMessage());
    }

    /**
     * 场景：批量响应 data 元素 index 越界。
     * 预期：抛协议异常而非错配到任意下标。
     */
    @Test
    void should_throwRuntimeException_when_embedBatch_given_indexOutOfRange() {
        // given：数量与请求一致但 index=5 越界，确保命中越界分支而非数量不符分支
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("{\"data\":["
                        + "{\"index\":5,\"embedding\":[0.1]},"
                        + "{\"index\":0,\"embedding\":[0.2]}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> embeddingClient.embedBatch(PROFILE_ID, List.of("甲", "乙")));
        assertTrue(exception.getMessage().contains("越界"), "异常信息需指明越界: " + exception.getMessage());
    }

    /**
     * 场景：通道返回非法 JSON。
     * 预期：折算为运行时异常（模型接口响应 JSON 解析失败）。
     */
    @Test
    void should_throwRuntimeException_when_embed_given_invalidJsonResponse() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class))).thenReturn("not-a-json");

        // when / then
        assertThrows(RuntimeException.class, () -> embeddingClient.embed(PROFILE_ID, "文本"));
    }

    /**
     * 场景：通道调用失败（框架折算的运行时异常）。
     * 预期：异常原样上抛，摄入任务按既有失败语义处理。
     */
    @Test
    void should_propagateFailure_when_embedBatch_given_transportThrows() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("模型接口调用失败: status=503, body=unavailable"));

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> embeddingClient.embedBatch(PROFILE_ID, List.of("甲")));
        assertTrue(exception.getMessage().contains("status=503"), "保持既有异常口径: " + exception.getMessage());
    }

    /**
     * 场景：profileId 空白。
     * 预期：抛 {@link IllegalArgumentException} 且不触达任何依赖。
     */
    @Test
    void should_throwIllegalArgument_when_embed_given_blankProfileId() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> embeddingClient.embed("  ", "文本"));
        verifyNoInteractions(modelProfileAccess, modelTransport);
    }

    /**
     * 场景：向量化文本为空白。
     * 预期：抛 {@link IllegalArgumentException} 且不解析端点。
     */
    @Test
    void should_throwIllegalArgument_when_embed_given_blankText() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> embeddingClient.embed(PROFILE_ID, " "));
        verifyNoInteractions(modelProfileAccess, modelTransport);
    }

    /**
     * 场景：批量列表为空。
     * 预期：抛 {@link IllegalArgumentException} 且不解析端点。
     */
    @Test
    void should_throwIllegalArgument_when_embedBatch_given_emptyTexts() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> embeddingClient.embedBatch(PROFILE_ID, List.of()));
        verifyNoInteractions(modelProfileAccess, modelTransport);
    }

    /**
     * 构造解析后的模型端点。
     *
     * @param vectorDimension 声明维度（null 表示不校验）
     */
    private static ModelProfileAccess.ResolvedEndpoint endpoint(Integer vectorDimension) {
        return new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", MODEL_NAME, vectorDimension);
    }
}
