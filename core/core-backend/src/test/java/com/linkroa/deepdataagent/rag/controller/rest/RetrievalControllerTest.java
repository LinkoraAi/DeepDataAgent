package com.linkroa.deepdataagent.rag.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.AnswerStream;
import com.linkroa.deepdataagent.rag.application.contract.QueryImageDTO;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalChunkView;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.service.RetrievalApplicationService;
import com.linkroa.deepdataagent.rag.controller.request.RetrievalRequest;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalChunksResponse;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.infrastructure.config.RetrievalStreamProperties;
import com.linkroa.deepdataagent.shared.exception.InvalidQueryImageException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RetrievalController} 单元测试。
 * <p>对齐仓库纯 Mockito 直调先例（无 MockMvc）：{@code @Mock} 应用服务 + 同步执行器直构
 * controller（答案端点在专用线程执行，测试以 {@code Runnable::run} 内联执行便于断言）。
 * 附图校验器为静态纯函数，直接以真实合法/非法数据驱动（无需静态 mock）。覆盖：</p>
 * <ul>
 *   <li>答案端点：建流前闸门先于建流（越权抛 404 且不创建流）、DTO→契约组装口径
 *       （开关缺省归一 true、附图归空表、可选字段透传）、非法附图整请求拒绝；</li>
 *   <li>切片端点：只投影 chunks 且走 {@code retrieveChunks}；</li>
 *   <li>端点隔离：答案端点不触碰切片与完整形态入口。</li>
 * </ul>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class RetrievalControllerTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试问题 */
    private static final String QUERY = "订单发货超时的处理规则是什么";

    /** PNG 魔数 + 载荷（校验器合法样本） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};

    /** 多模态资源预览端点模板（modalFile 期望值：仅暴露切片 ID，桶概念已退役） */
    private static final String PREVIEW_URL_TEMPLATE = "/api/v1/knowledge-base/chunks/%s/media";

    /** 检索编排应用服务 Mock */
    @Mock
    private RetrievalApplicationService retrievalApplicationService;

    /** 被测控制器（同步执行器：答案流在调用线程内联执行） */
    private RetrievalController controller;

    /**
     * 每个用例重建控制器，并打桩「知识库可检索」这一夹具前置条件。
     */
    @BeforeEach
    void setUp() {
        controller = new RetrievalController(retrievalApplicationService, new RetrievalStreamProperties(),
                Runnable::run);
        lenient().when(retrievalApplicationService.assertRetrievable(KB_ID)).thenReturn(nearestStrategyConfig());
    }

    /**
     * 构造仅含必填字段的纯文本请求体。
     *
     * @return 请求 DTO
     */
    private RetrievalRequest textOnlyRequest() {
        return new RetrievalRequest(KB_ID, QUERY, null, null, null, null, null, null, null,
                null, null, null);
    }

    /**
     * 构造含图片引用与纯文本各一条的切片明细（供切片端点响应投影断言）。
     *
     * @return 切片明细列表
     */
    private List<RetrievalChunkView> chunkViews() {
        return List.of(
                new RetrievalChunkView(102L, "超时按第 8 条赔付", "manual.pdf",
                        "rag/7/42/images/a.png", 0.82D),
                new RetrievalChunkView(101L, "发货流程说明", "manual.pdf", null, 0.61D));
    }

    /**
     * 构造流式答案桩（单增量 + 非降级）。
     *
     * @return 答案流
     */
    private AnswerStream answerStream() {
        return new AnswerStream(Flux.just("按平台规则第 8 条处理"));
    }

    /**
     * 构造库级策略快照（NAIVE、无重排与融合配置）。
     *
     * @return 策略配置
     */
    private static RetrievalStrategyConfig nearestStrategyConfig() {
        return new RetrievalStrategyConfig(RetrievalStrategyType.NAIVE, Boolean.FALSE, 5, 0.2F, null, null);
    }

    // ==================== 答案端点 ====================

    @Test
    void should_defaultSwitchesToTrueAndEmptyImages_when_retrieveAnswer_given_textOnlyRequest() {
        // given：请求体缺省全部可选字段（含两开关）
        when(retrievalApplicationService.generateAnswerStream(any(RetrievalQuery.class), any()))
                .thenReturn(answerStream());

        // when
        controller.retrieveAnswer(textOnlyRequest());

        // then：开关缺省归一为 true（维持基线自动触发），附图归空表，必填字段透传
        RetrievalQuery query = captureAnswerQuery();
        assertEquals(KB_ID, query.kbId());
        assertEquals(QUERY, query.query());
        assertTrue(query.images().isEmpty());
        assertTrue(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
    }

    @Test
    void should_createSseEmitterAndStartStream_when_retrieveAnswer_given_successRequest() {
        // given
        when(retrievalApplicationService.generateAnswerStream(any(RetrievalQuery.class), any()))
                .thenReturn(answerStream());

        // when
        SseEmitter emitter = controller.retrieveAnswer(textOnlyRequest());

        // then：返回 SSE 连接，流式入口被调用一次（不触碰同步答案入口）
        assertNotNull(emitter);
        verify(retrievalApplicationService).generateAnswerStream(any(RetrievalQuery.class),
                eq(nearestStrategyConfig()));
        verify(retrievalApplicationService, never()).generateAnswer(any(RetrievalQuery.class));
    }

    @Test
    void should_rejectBeforeCreatingStream_when_retrieveAnswer_given_notOwner() {
        // given：建流前闸门判定越权（与不存在折叠为同一 404）
        when(retrievalApplicationService.assertRetrievable(KB_ID))
                .thenThrow(new ResourceNotFoundException("知识库不存在"));

        // when & then：异常直接抛出（由全局异常处理映射真实 HTTP 404），流与检索链路均未创建
        assertThrows(ResourceNotFoundException.class, () -> controller.retrieveAnswer(textOnlyRequest()));
        verify(retrievalApplicationService, never()).generateAnswerStream(any(RetrievalQuery.class), any());
    }

    @Test
    void should_keepExplicitFlags_when_retrieveAnswer_given_falseQueryImageTranscribe() {
        // given：显式 queryImageTranscribe=false、answerImageDirectRead 缺省
        RetrievalRequest request = new RetrievalRequest(KB_ID, QUERY, null, null, null, null, null, null, null,
                null, Boolean.FALSE, null);
        when(retrievalApplicationService.generateAnswerStream(any(RetrievalQuery.class), any()))
                .thenReturn(answerStream());

        // when
        controller.retrieveAnswer(request);

        // then：显式 false 原样下传，缺省开关仍归一 true
        RetrievalQuery query = captureAnswerQuery();
        assertFalse(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
    }

    @Test
    void should_passBudgetKeywordsAndDecodedImageToContract_when_retrieveAnswer_given_allOptionalFields() {
        // given：预算/关键词/附图全量携带
        List<QueryImageDTO> images = List.of(
                new QueryImageDTO("image/png", Base64.getEncoder().encodeToString(PNG_BYTES)));
        RetrievalRequest request = new RetrievalRequest(KB_ID, QUERY, List.of("发货"), List.of("订单"),
                15, 8, 6000, 2500, 3500, images, Boolean.TRUE, Boolean.FALSE);
        when(retrievalApplicationService.generateAnswerStream(any(RetrievalQuery.class), any()))
                .thenReturn(answerStream());

        // when
        controller.retrieveAnswer(request);

        // then：可选字段透传，合法附图以解码形态进契约（校验通过 → LlmImage 字节一致）
        RetrievalQuery query = captureAnswerQuery();
        assertEquals(List.of("发货"), query.hlKeywords());
        assertEquals(List.of("订单"), query.llKeywords());
        assertEquals(15, query.topK());
        assertEquals(8, query.chunkTopK());
        assertEquals(6000, query.maxTotalTokens());
        assertEquals(2500, query.maxEntityTokens());
        assertEquals(3500, query.maxRelationTokens());
        assertEquals(1, query.images().size());
        LlmImage decoded = query.images().get(0);
        assertEquals("image/png", decoded.contentType());
        assertArrayEquals(PNG_BYTES, decoded.content());
        assertFalse(query.multimodal().answerImageDirectRead());
    }

    @Test
    void should_throwRealBadRequestExceptionAndNotTouchService_when_retrieveAnswer_given_illegalImage() {
        // given：附图 base64 非法（校验器规则：任一非法整请求拒绝）
        RetrievalRequest request = illegalImageRequest();

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> controller.retrieveAnswer(request));

        // then：错误信息含第几张定位，且校验先于检索链路（服务零交互、无模型调用开销）
        assertTrue(exception.getMessage().contains("第 1 张附图"));
        verifyNoInteractions(retrievalApplicationService);
    }

    // ==================== 切片端点 ====================

    @Test
    void should_carryChunksOnlyInRelevanceOrder_when_retrieveChunks_given_chunkViews() {
        // given
        when(retrievalApplicationService.retrieveChunks(any(RetrievalQuery.class))).thenReturn(chunkViews());

        // when
        ApiResponse<RetrievalChunksResponse> response = controller.retrieveChunks(textOnlyRequest());

        // then：数据体只含切片，按相关性序投影（含正文与可点击查看的资源路径）
        assertTrue(response.success());
        assertEquals(2, response.data().chunks().size());
        assertEquals(102L, response.data().chunks().get(0).chunkId());
        assertEquals("超时按第 8 条赔付", response.data().chunks().get(0).chunkContent());
        assertEquals("manual.pdf", response.data().chunks().get(0).sourceFile());
        assertEquals(String.format(PREVIEW_URL_TEMPLATE, 102L),
                response.data().chunks().get(0).modalFile());
        assertEquals(0.82D, response.data().chunks().get(0).score());
        assertNull(response.data().chunks().get(1).modalFile());
    }

    @Test
    void should_buildSameContractAndSkipAnswerStage_when_retrieveChunks_given_textOnlyRequest() {
        // given
        when(retrievalApplicationService.retrieveChunks(any(RetrievalQuery.class))).thenReturn(List.of());

        // when
        ApiResponse<RetrievalChunksResponse> response = controller.retrieveChunks(textOnlyRequest());

        // then：与答案端点同一组装口径（开关归一 true、附图空表），空命中归一空表非 null
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalApplicationService).retrieveChunks(captor.capture());
        RetrievalQuery query = captor.getValue();
        assertEquals(KB_ID, query.kbId());
        assertEquals(QUERY, query.query());
        assertTrue(query.images().isEmpty());
        assertTrue(query.multimodal().queryImageTranscribe());
        assertTrue(query.multimodal().answerImageDirectRead());
        assertTrue(response.data().chunks().isEmpty());
    }

    @Test
    void should_throwRealBadRequestExceptionAndNotTouchService_when_retrieveChunks_given_illegalImage() {
        // given：附图非法（与答案端点共用同一校验口径）
        RetrievalRequest request = illegalImageRequest();

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> controller.retrieveChunks(request));

        // then
        assertTrue(exception.getMessage().contains("第 1 张附图"));
        verifyNoInteractions(retrievalApplicationService);
    }

    // ==================== 端点隔离 ====================

    @Test
    void should_notTouchOtherShapeEntry_when_retrieveAnswer_given_successRequest() {
        // given：答案端点成功路径
        when(retrievalApplicationService.generateAnswerStream(any(RetrievalQuery.class), any()))
                .thenReturn(answerStream());

        // when
        controller.retrieveAnswer(textOnlyRequest());

        // then：不触发切片形态与既有完整形态入口（明细回取被裁剪）
        verify(retrievalApplicationService, never()).retrieveChunks(any(RetrievalQuery.class));
        verify(retrievalApplicationService, never()).search(any(RetrievalQuery.class));
    }

    @Test
    void should_notTouchAnswerEntry_when_retrieveChunks_given_successRequest() {
        // given：切片端点成功路径
        when(retrievalApplicationService.retrieveChunks(any(RetrievalQuery.class))).thenReturn(List.of());

        // when
        controller.retrieveChunks(textOnlyRequest());

        // then：不触发答案形态与既有完整形态入口（上下文构建与作答被裁剪）
        verify(retrievalApplicationService, never()).generateAnswerStream(any(RetrievalQuery.class), any());
        verify(retrievalApplicationService, never()).search(any(RetrievalQuery.class));
    }

    /**
     * 构造附图 base64 非法的请求体。
     *
     * @return 请求 DTO
     */
    private RetrievalRequest illegalImageRequest() {
        List<QueryImageDTO> images = List.of(new QueryImageDTO("image/png", "非法@base64!字符"));
        return new RetrievalRequest(KB_ID, QUERY, null, null, null, null, null, null, null,
                images, null, null);
    }

    /**
     * 捕获答案端点下传的检索执行契约。
     *
     * @return 捕获到的契约对象
     */
    private RetrievalQuery captureAnswerQuery() {
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalApplicationService).generateAnswerStream(captor.capture(), any());
        return captor.getValue();
    }
}