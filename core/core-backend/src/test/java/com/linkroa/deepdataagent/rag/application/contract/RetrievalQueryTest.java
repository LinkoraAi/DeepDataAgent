package com.linkroa.deepdataagent.rag.application.contract;

import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.service.RetrievalConstants;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalQuery} 与 {@link RetrievalMultimodalOptions} 契约单元测试
 * <p>覆盖：① 九参/十参兼容构造器多模态开关归一为默认全开；② 全参构造器显式 false 保留；
 * ③ multimodal 组件 null 回落 defaults；④ Integer 预算 null 归默认值基线口径不漂移；
 * ⑤ images null 归不可变空表；⑥ 必填缺失拒绝；⑦ defaults() 工厂双 true；
 * ⑧ 图谱召回上限（graphEdgeTop / graphEdgeChunkLimit）null 归 RetrievalConstants 默认值、
 * 显式正值保留、非正值拒绝。</p>
 *
 * @author DeepDataAgent
 */
class RetrievalQueryTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试问题 */
    private static final String QUERY = "订单发货超时的处理规则是什么";

    /** 测试附图载荷 */
    private static final LlmImage IMAGE = new LlmImage("image/png",
            "PNG-BYTES".getBytes(StandardCharsets.UTF_8));

    /**
     * 构造九参纯文本请求（引入 images 组件前的基线形态）。
     *
     * @return 检索请求
     */
    private static RetrievalQuery nineArgQuery() {
        return new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null);
    }

    @Test
    void should_returnDoubleTrueOptions_when_defaults_given_anyCall() {
        // given // when
        RetrievalMultimodalOptions defaults = RetrievalMultimodalOptions.defaults();

        // then：双开关全开与基线无条件触发等价
        assertTrue(defaults.queryImageTranscribe());
        assertTrue(defaults.answerImageDirectRead());
    }

    @Test
    void should_defaultOptionsToTrue_when_constructor_given_nineArgConvenienceForm() {
        // given // when
        RetrievalQuery query = nineArgQuery();

        // then：九参兼容构造器落默认全开 options，附图归空表
        assertNotNull(query.multimodal());
        assertTrue(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
        assertTrue(query.images().isEmpty());
    }

    @Test
    void should_defaultOptionsToTrue_when_constructor_given_tenArgWithImagesForm() {
        // given
        List<LlmImage> images = List.of(IMAGE);

        // when
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null, images);

        // then：十参兼容构造器（基线带图形态）同样落默认全开 options
        assertTrue(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
        assertEquals(1, query.images().size());
    }

    @Test
    void should_keepExplicitFlags_when_constructor_given_falseOptions() {
        // given
        RetrievalMultimodalOptions options = new RetrievalMultimodalOptions(false, true);

        // when
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(IMAGE), options, null, null);

        // then：显式 false 原样保留，不被归一篡改
        assertFalse(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
    }

    @Test
    void should_fallbackToDefaults_when_constructor_given_nullMultimodalComponent() {
        // given // when
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(IMAGE), null, null, null);

        // then：紧凑构造器 null→defaults() 归一，契约内开关非空无三态
        assertNotNull(query.multimodal());
        assertTrue(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
    }

    @Test
    void should_normalizeIntegerBudgets_when_constructor_given_nullFields() {
        // given // when
        RetrievalQuery query = nineArgQuery();

        // then：Integer 预算 null 归默认值口径与基线一致（chunkTopK 回退 topK）
        assertEquals(RetrievalQuery.DEFAULT_TOP_K, query.topK());
        assertEquals(RetrievalQuery.DEFAULT_TOP_K, query.chunkTopK());
        assertEquals(RetrievalQuery.DEFAULT_MAX_TOTAL_TOKENS, query.maxTotalTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_ENTITY_TOKENS, query.maxEntityTokens());
        assertEquals(RetrievalQuery.DEFAULT_MAX_RELATION_TOKENS, query.maxRelationTokens());
    }

    @Test
    void should_returnEmptyImages_when_constructor_given_nullImages() {
        // given // when
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                null, RetrievalMultimodalOptions.defaults(), null, null);

        // then：images 空值归一为不可变空列表
        assertNotNull(query.images());
        assertTrue(query.images().isEmpty());
    }

    @Test
    void should_normalizeGraphLimitsToConstants_when_constructor_given_nullGraphFields() {
        // given // when
        RetrievalQuery query = nineArgQuery();

        // then：图谱召回上限 null 回落 RetrievalConstants 默认值（与常量化时代行为一致）
        assertEquals(RetrievalConstants.GRAPH_EDGE_TOP, query.graphEdgeTop());
        assertEquals(RetrievalConstants.GRAPH_EDGE_CHUNK_LIMIT, query.graphEdgeChunkLimit());
    }

    @Test
    void should_keepExplicitGraphLimits_when_constructor_given_positiveValues() {
        // given // when
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null, null, null, null, null, null,
                List.of(), RetrievalMultimodalOptions.defaults(), 7, 3);

        // then：显式正值原样保留，供 Stage 1 图谱通道消费
        assertEquals(7, query.graphEdgeTop());
        assertEquals(3, query.graphEdgeChunkLimit());
    }

    @Test
    void should_throwIllegalArgumentException_when_constructor_given_nonPositiveGraphLimit() {
        // given：零与负数均直下 SQL LIMIT，须在契约层快速失败
        List<LlmImage> images = List.of();
        RetrievalMultimodalOptions options = RetrievalMultimodalOptions.defaults();

        // when & then
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, null, null, null, images, options, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, null, null, null, images, options, null, -1));
    }

    @Test
    void should_throwIllegalArgumentException_when_constructor_given_blankQuery() {
        // given
        String blankQuery = "  ";

        // when & then：必填口径不因新增组件变化
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery(KB_ID, blankQuery,
                null, null, null, null, null, null, null, List.of(), RetrievalMultimodalOptions.defaults(),
                null, null));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery(null, QUERY,
                null, null, null, null, null, null, null, List.of(), RetrievalMultimodalOptions.defaults(),
                null, null));
    }
}
