package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * {@link OpenAiCompatibleRerankClient} 单元测试。
 * <p>与同包 {@code OpenAiCompatibleLlmClientTest} 一致：真实 HTTP 不在单测触达，报文组装与响应
 * 解析走包级可见静态方法（{@code buildRequestBody} / {@code parseScores}）直接断言；
 * 仅「一次批量调用」这一核心目标以 Mock 的 {@link ModelTransport} 通道验证：
 * 通道由裸 HTTP 静态工具改为可注入的 AgentScope 框架通道接口。
 * 覆盖场景：请求体形态、端点原样直调（空资源路径）、按 index 回填对齐、
 * 双响应形态解包（顶层 results 与 DashScope 原生 output.results）、
 * 协议异常分支（缺 results / 数量不符 / index 越界 /
 * 缺 index / 重复 index / 分数非数值）、空白 profileId 与空候选列表边界。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleRerankClientTest {

    /** 重排模型 profileId */
    private static final String PROFILE_ID = "model-rerank";

    /** 解析后的模型名称（请求体 model 字段） */
    private static final String MODEL_NAME = "bge-reranker-v2";

    /** 测试用查询文本 */
    private static final String QUERY = "重排模型怎么配置";

    /** 候选正文1 */
    private static final String PASSAGE_1 = "正文一";

    /** 候选正文2 */
    private static final String PASSAGE_2 = "正文二";

    /** 期望的重排资源路径（端点原样直调，追加空路径） */
    private static final String RERANK_PATH = "";

    /** 断言浮点分数误差容忍度 */
    private static final double DELTA = 1E-9;

    /** 响应报文解析器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模型配置解析端口 Mock */
    @Mock
    private ModelProfileAccess modelProfileAccess;

    /** 模型端点 HTTP 通道 Mock */
    @Mock
    private ModelTransport modelTransport;

    /** 被测重排客户端 */
    @InjectMocks
    private OpenAiCompatibleRerankClient rerankClient;

    /**
     * 场景：两条候选的请求体组装。
     * 预期：报文为 {@code {model, query, documents}}，documents 顺序与入参一致且不含 top_n。
     */
    @Test
    void should_produceModelQueryDocumentsBody_when_buildRequestBody_given_twoPassages() {
        // given / when
        String body = OpenAiCompatibleRerankClient.buildRequestBody(endpoint(), QUERY,
                List.of(PASSAGE_1, PASSAGE_2));

        // then
        assertEquals("{\"model\":\"bge-reranker-v2\",\"query\":\"重排模型怎么配置\","
                + "\"documents\":[\"正文一\",\"正文二\"]}", body);
    }

    /**
     * 场景：查询文本为 null。
     * 预期：query 字段归一为空串，请求体不出现 null 字面量。
     */
    @Test
    void should_putEmptyQuery_when_buildRequestBody_given_nullQuery() {
        // given / when
        String body = OpenAiCompatibleRerankClient.buildRequestBody(endpoint(), null, List.of(PASSAGE_1));

        // then
        assertTrue(body.contains("\"query\":\"\""), "null 查询应归一为空串: " + body);
    }

    /**
     * 场景：响应 results 乱序（index=1 在前）。
     * 预期：按 index 回填，返回列表与请求 documents 同序等长。
     */
    @Test
    void should_backfillScoresByIndex_when_parseScores_given_shuffledResults() {
        // given
        JsonNode response = json("{\"results\":["
                + "{\"index\":1,\"relevance_score\":0.7},"
                + "{\"index\":0,\"relevance_score\":0.9}]}");

        // when
        List<Double> scores = OpenAiCompatibleRerankClient.parseScores(response, 2);

        // then
        assertEquals(0.9D, scores.get(0), DELTA);
        assertEquals(0.7D, scores.get(1), DELTA);
    }

    /**
     * 场景：单候选响应解析（批量契约退化边界）。
     * 预期：返回长度为 1 的分数列表。
     */
    @Test
    void should_returnSingleScore_when_parseScores_given_singleResult() {
        // given
        JsonNode response = json("{\"results\":[{\"index\":0,\"relevance_score\":0.42}]}");

        // when
        List<Double> scores = OpenAiCompatibleRerankClient.parseScores(response, 1);

        // then
        assertEquals(1, scores.size());
        assertEquals(0.42D, scores.get(0), DELTA);
    }

    /**
     * 场景：DashScope 原生响应形态（results 包在 output 下）且乱序。
     * 预期：解包 output.results 后按 index 回填，返回列表与请求 documents 同序等长。
     */
    @Test
    void should_backfillScoresByIndex_when_parseScores_given_nativeOutputWrappedResults() {
        // given
        JsonNode response = json("{\"output\":{\"results\":["
                + "{\"index\":1,\"relevance_score\":0.68},"
                + "{\"index\":0,\"relevance_score\":0.91}]},"
                + "\"usage\":{\"total_tokens\":155},\"request_id\":\"req-1\"}");

        // when
        List<Double> scores = OpenAiCompatibleRerankClient.parseScores(response, 2);

        // then
        assertEquals(0.91D, scores.get(0), DELTA);
        assertEquals(0.68D, scores.get(1), DELTA);
    }

    /**
     * 场景：响应同时带顶层 results 与 output.results。
     * 预期：解包优先取顶层 results（OpenAI 兼容形态优先，行为对存量服务零漂移）。
     */
    @Test
    void should_preferTopLevelResults_when_resolveResultsNode_given_bothFormsPresent() {
        // given
        JsonNode response = json("{\"results\":[{\"index\":0,\"relevance_score\":0.5}],"
                + "\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}");

        // when
        JsonNode results = OpenAiCompatibleRerankClient.resolveResultsNode(response);

        // then
        assertTrue(results.isArray());
        assertEquals(1, results.size());
        assertEquals(0.5D, results.get(0).path("relevance_score").asDouble(), DELTA);
    }

    /**
     * 场景：响应顶层与 output 下均没有 results 字段。
     * 预期：抛协议异常，不静默返回空列表。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_missingResultsNode() {
        // given
        JsonNode response = json("{\"model\":\"bge-reranker-v2\"}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
        assertTrue(exception.getMessage().contains("results"), "异常信息需指明缺失字段: " + exception.getMessage());
    }

    /**
     * 场景：results 长度小于请求候选数（如服务端默认只返回 top_n）。
     * 预期：抛协议异常，不以零分补齐缺失候选。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_resultSizeMismatch() {
        // given
        JsonNode response = json("{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
        assertTrue(exception.getMessage().contains("数量不符"), "异常信息需指明数量不符: " + exception.getMessage());
    }

    /**
     * 场景：results 元素的 index 超出请求候选下标范围。
     * 预期：抛协议异常而非丢弃该条分数。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_indexOutOfRange() {
        // given
        JsonNode response = json("{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.9},"
                + "{\"index\":5,\"relevance_score\":0.1}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
        assertTrue(exception.getMessage().contains("index 越界"), "异常信息需指明越界: " + exception.getMessage());
    }

    /**
     * 场景：results 元素缺少 index 字段（无法确定归属候选）。
     * 预期：抛协议异常，不得把分数错配到任意候选。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_absentIndexField() {
        // given
        JsonNode response = json("{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.9},"
                + "{\"relevance_score\":0.1}]}");

        // when / then
        assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
    }

    /**
     * 场景：两条 results 声明同一个 index（另一条候选实际无分）。
     * 预期：抛协议异常，不返回含 null 的列表。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_duplicateIndex() {
        // given
        JsonNode response = json("{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.8}]}");

        // when / then
        assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
    }

    /**
     * 场景：relevance_score 为字符串（非数值）。
     * 预期：抛协议异常而非按 0 分处理（0 分会被相似阈值静默过滤）。
     */
    @Test
    void should_throwRuntimeException_when_parseScores_given_nonNumericRelevanceScore() {
        // given
        JsonNode response = json("{\"results\":["
                + "{\"index\":0,\"relevance_score\":\"high\"},"
                + "{\"index\":1,\"relevance_score\":0.7}]}");

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleRerankClient.parseScores(response, 2));
        assertTrue(exception.getMessage().contains("relevance_score"),
                "异常信息需指明非法字段: " + exception.getMessage());
    }

    /**
     * 场景：profileId 为空白。
     * 预期：抛 {@link IllegalArgumentException} 且不触达模型解析端口。
     */
    @Test
    void should_throwIllegalArgument_when_scoreBatch_given_blankModelProfileId() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> rerankClient.scoreBatch("  ", QUERY, List.of(PASSAGE_1)));
        verifyNoInteractions(modelProfileAccess);
    }

    /**
     * 场景：候选列表为空。
     * 预期：直接返回空列表，不解析端点也不发起远程调用。
     */
    @Test
    void should_returnEmptyListWithoutRemoteCall_when_scoreBatch_given_emptyPassages() {
        // given / when
        List<Double> scores = rerankClient.scoreBatch(PROFILE_ID, QUERY, List.of());

        // then
        assertTrue(scores.isEmpty());
        verifyNoInteractions(modelProfileAccess);
    }

    /**
     * 场景（核心）：两条候选走一次真实批量打分流程。
     * 预期：仅发生一次框架通道调用（资源路径为空，端点原样直调），且响应乱序分数按请求顺序回填。
     */
    @Test
    void should_issueSingleTransportCall_when_scoreBatch_given_multiplePassages() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("{\"results\":["
                        + "{\"index\":1,\"relevance_score\":0.7},"
                        + "{\"index\":0,\"relevance_score\":0.9}]}");

        // when
        List<Double> scores = rerankClient.scoreBatch(PROFILE_ID, QUERY, List.of(PASSAGE_1, PASSAGE_2));

        // then
        assertEquals(0.9D, scores.get(0), DELTA);
        assertEquals(0.7D, scores.get(1), DELTA);
        verify(modelTransport, times(1)).post(any(), eq(RERANK_PATH), anyString(), any(Duration.class));
    }

    /**
     * 场景：通道调用失败（端点不可用折算的运行时异常）。
     * 预期：异常原样上抛，由精排调用方按既有降级策略直出粗排。
     */
    @Test
    void should_propagateFailure_when_scoreBatch_given_transportThrows() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        when(modelTransport.post(any(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("模型接口调用失败: status=500, body=oops"));

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> rerankClient.scoreBatch(PROFILE_ID, QUERY, List.of(PASSAGE_1)));
        assertTrue(exception.getMessage().contains("status=500"), "降级依赖既有异常口径: " + exception.getMessage());
    }

    /**
     * 构造解析后的模型端点（api_endpoint_url 原样直调，此处即最终完整请求地址）。
     */
    private static ModelProfileAccess.ResolvedEndpoint endpoint() {
        return new ModelProfileAccess.ResolvedEndpoint("https://api.example/rerank", "sk-x", MODEL_NAME, null);
    }

    /**
     * 解析测试用响应 JSON。
     */
    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("测试响应报文非法 JSON: " + raw, e);
        }
    }
}
