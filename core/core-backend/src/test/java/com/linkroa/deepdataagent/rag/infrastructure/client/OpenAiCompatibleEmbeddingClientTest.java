package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
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
 * 捕获请求体。核心断言：真批量（按单请求批量上限 16 切片提交、片内 index 归位、
 * 片间按入参顺序拼接）、维度校验、协议异常与参数非法分支。</p>
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

    /** 测试侧请求体解析器（从出站 JSON 的 input 数组生成镜像响应） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString()))
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
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString()))
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
        verify(modelTransport, times(1)).post(any(), eq(EMBEDDINGS_PATH), bodyCaptor.capture());
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
        when(modelTransport.post(any(), anyString(), anyString()))
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
        when(modelTransport.post(any(), anyString(), anyString()))
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
        when(modelTransport.post(any(), anyString(), anyString()))
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
        when(modelTransport.post(any(), anyString(), anyString())).thenReturn("not-a-json");

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
        when(modelTransport.post(any(), anyString(), anyString()))
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
     * 场景（提供方批量上限回归）：35 条文本批量向量化（单请求上限缺省 16）。
     * 预期：切片为 16/16/3 三次通道调用；每片响应按片内 index 归位，
     * 片间按入参顺序拼接——返回列表与入参下标一一对应（DashScope 兼容端上限 20，
     * 整表直发会 400 batch size is invalid）。
     */
    @Test
    void should_partitionByBatchSizeAndKeepIndexOrder_when_embedBatch_given_35Texts() throws Exception {
        // given：镜像桩——回显每片 input 数组（embedding 值=文本数值），index 乱序回传以同时钉归位
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString())).thenAnswer(invocation -> {
            JsonNode input = OBJECT_MAPPER.readTree(invocation.<String>getArgument(2)).path("input");
            List<String> lines = new ArrayList<>(input.size());
            for (int i = input.size() - 1; i >= 0; i--) {
                lines.add("{\"index\":" + i + ",\"embedding\":[" + input.get(i).asText() + "]}");
            }
            return "{\"data\":[" + String.join(",", lines) + "]}";
        });
        List<String> texts = new ArrayList<>(35);
        for (int i = 1; i <= 35; i++) {
            texts.add(String.valueOf(i));
        }

        // when
        List<float[]> vectors = embeddingClient.embedBatch(PROFILE_ID, texts);

        // then：三次调用、片大小 16/16/3，全表下标一一对应
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.captor();
        verify(modelTransport, times(3)).post(any(), eq(EMBEDDINGS_PATH), bodyCaptor.capture());
        List<Integer> batchSizes = new ArrayList<>();
        for (String body : bodyCaptor.getAllValues()) {
            batchSizes.add(OBJECT_MAPPER.readTree(body).path("input").size());
        }
        assertEquals(List.of(16, 16, 3), batchSizes, "应按单请求批量上限 16 切片");
        assertEquals(35, vectors.size());
        for (int i = 0; i < 35; i++) {
            assertEquals(i + 1F, vectors.get(i)[0], DELTA, "第 " + i + " 条向量必须与入参下标对应");
        }
    }

    /**
     * 场景：切片中途某片远程失败（第二片 503）。
     * 预期：fail-fast 原样上抛，不回退逐条重试、不以成功片的结果当作完整返回。
     */
    @Test
    void should_failFastOnAnySlice_when_embedBatch_given_secondSliceThrows() {
        // given：20 条文本 → 16+4 两片；第一片成功、第二片抛错
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint(null));
        String firstSliceData = "{\"data\":[" + String.join(",",
                java.util.stream.IntStream.rangeClosed(0, 15)
                        .mapToObj(i -> "{\"index\":" + i + ",\"embedding\":[0.5]}").toList()) + "]}";
        when(modelTransport.post(any(), eq(EMBEDDINGS_PATH), anyString()))
                .thenReturn(firstSliceData)
                .thenThrow(new RuntimeException("模型接口调用失败: status=503"));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add("T" + i);
        }

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> embeddingClient.embedBatch(PROFILE_ID, texts));
        assertTrue(exception.getMessage().contains("status=503"),
                "片级失败应原样上抛: " + exception.getMessage());
    }

    /**
     * 场景：文本列表为空。
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
        return new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", MODEL_NAME, vectorDimension, null);
    }
}
