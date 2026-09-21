package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalConfigValidator} 单元测试。
 */
class RetrievalConfigValidatorTest {

    /** 构造 ObjectNode 入口测试数据用 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_blankJson() {
        // given
        String strategyJson = " ";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("不能为空"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_invalidJson() {
        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize("{invalid"));
        assertTrue(exception.getMessage().contains("合法的JSON"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_nonObjectJson() {
        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize("[1,2]"));
        assertTrue(exception.getMessage().contains("JSON对象"));
    }

    @Test
    void should_normalizeStrategyType_when_validateAndNormalize_given_lowercaseNaive() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\"}";

        // when
        String normalized = RetrievalConfigValidator.validateAndNormalize(strategyJson);

        // then
        assertTrue(normalized.contains("\"strategyType\":\"NAIVE\""));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_unknownStrategyType() {
        // given
        String strategyJson = "{\"strategyType\":\"hybrid\"}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("仅支持naive与mix"));
        assertTrue(exception.getMessage().contains("hybrid"));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_validMinimalConfig() {
        // given
        String strategyJson = "{\"strategyType\":\"mix\"}";

        // when // then
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_resultChunkCountZero() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"resultChunkCount\":0}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("resultChunkCount"));
        assertTrue(exception.getMessage().contains("1到100"));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_resultChunkCountBoundaries() {
        // given
        String lower = "{\"strategyType\":\"naive\",\"resultChunkCount\":1}";
        String upper = "{\"strategyType\":\"naive\",\"resultChunkCount\":100}";

        // when // then
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(lower));
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(upper));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_resultChunkCountOverMax() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"resultChunkCount\":101}";

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_similarThresholdAboveOne() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"similarThreshold\":1.5}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("similarThreshold"));
        assertTrue(exception.getMessage().contains("0到1"));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_similarThresholdBoundaries() {
        // given
        String lower = "{\"strategyType\":\"naive\",\"similarThreshold\":0}";
        String upper = "{\"strategyType\":\"naive\",\"similarThreshold\":1}";

        // when // then
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(lower));
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(upper));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_rerankEnabledWithoutModelProfileId() {
        // given
        String strategyJson = "{\"strategyType\":\"mix\",\"rerankConfig\":{\"enabled\":true,\"topK\":20}}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("modelProfileId"));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_rerankEnabledWithUnregisteredModelProfileId() {
        // given：仅结构校验，不查模型注册表，未注册标识可落库
        String strategyJson = "{\"strategyType\":\"mix\","
                + "\"rerankConfig\":{\"enabled\":true,\"modelProfileId\":\"not-registered-v1\"}}";

        // when
        String normalized = RetrievalConfigValidator.validateAndNormalize(strategyJson);

        // then
        assertTrue(normalized.contains("not-registered-v1"));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_rerankDisabledWithoutModelProfileId() {
        // given
        String strategyJson = "{\"strategyType\":\"mix\",\"rerankConfig\":{\"enabled\":false}}";

        // when // then
        assertDoesNotThrow(() -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
    }

    @Test
    void should_defaultRrfK_when_validateAndNormalize_given_rrfWithoutK() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"fusionConfig\":{\"fusionType\":\"rrf\"}}";

        // when
        String normalized = RetrievalConfigValidator.validateAndNormalize(strategyJson);

        // then（融合类型回写枚举名、缺省 rrfK 回写 60）
        assertTrue(normalized.contains("\"fusionType\":\"RRF\""));
        assertTrue(normalized.contains("\"rrfK\":60"));
    }

    @Test
    void should_keepProvidedRrfK_when_validateAndNormalize_given_rrfWithPositiveK() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"fusionConfig\":{\"fusionType\":\"RRF\",\"rrfK\":80}}";

        // when
        String normalized = RetrievalConfigValidator.validateAndNormalize(strategyJson);

        // then
        assertTrue(normalized.contains("\"rrfK\":80"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_rrfKNotPositive() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"fusionConfig\":{\"fusionType\":\"RRF\",\"rrfK\":0}}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("rrfK必须大于0"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_unknownFusionType() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"fusionConfig\":{\"fusionType\":\"SIMPLE\"}}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("仅支持RRF与WEIGHTED_SUM"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_weightedSumDenseWeightOne() {
        // given：稠密权重 1.0 落在开区间 (0,1) 之外
        String strategyJson = "{\"strategyType\":\"mix\",\"fusionConfig\":{\"fusionType\":\"WEIGHTED_SUM\","
                + "\"channelDenseWeight\":{\"VECTOR\":1.0}}}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
        assertTrue(exception.getMessage().contains("VECTOR"));
        assertTrue(exception.getMessage().contains("开区间(0,1)"));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_weightedSumDenseWeightZero() {
        // given：开区间不含 0
        String strategyJson = "{\"strategyType\":\"mix\",\"fusionConfig\":{\"fusionType\":\"WEIGHTED_SUM\","
                + "\"channelDenseWeight\":{\"GRAPH\":0}}}";

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
    }

    @Test
    void should_pass_when_validateAndNormalize_given_weightedSumWeightsInOpenInterval() {
        // given：未知通道键宽容忽略
        String strategyJson = "{\"strategyType\":\"mix\",\"fusionConfig\":{\"fusionType\":\"weighted_sum\","
                + "\"channelDenseWeight\":{\"VECTOR\":0.5,\"BM25\":0.3,\"UNKNOWN_CHANNEL\":9.9}}}";

        // when
        String normalized = RetrievalConfigValidator.validateAndNormalize(strategyJson);

        // then
        assertTrue(normalized.contains("\"fusionType\":\"WEIGHTED_SUM\""));
    }

    @Test
    void should_throwBadRequest_when_validateAndNormalize_given_nonObjectFusionConfig() {
        // given
        String strategyJson = "{\"strategyType\":\"naive\",\"fusionConfig\":\"rrf\"}";

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> RetrievalConfigValidator.validateAndNormalize(strategyJson));
    }

    @Test
    void should_returnSameNode_when_validateAndNormalize_given_objectNodeEntry() {
        // given
        ObjectNode strategy = MAPPER.createObjectNode();
        strategy.put("strategyType", "MIX");

        // when
        ObjectNode result = RetrievalConfigValidator.validateAndNormalize(strategy);

        // then（ObjectNode 入口原地回写并返回同一节点）
        assertSame(strategy, result);
        assertNotNull(result.toString());
    }
}
