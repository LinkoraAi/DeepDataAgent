package com.linkroa.deepdataagent.rag.controller.convert;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalChunkView;
import com.linkroa.deepdataagent.rag.controller.response.ChunkReferenceResponse;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalAnswerResponse;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalChunksResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalResponseConvert} 单元测试。
 * <p>覆盖：① 答案形态只投影 answer（降级 null 原样透传）；② 切片形态按相关性序投影明细，
 * 含正文全量、来源文件与多模态资源路径；③ 明细 null / 空表归一为空表非 null；
 * ④ 文本切片无媒体引用时 modalFile 为 null；⑤ 对象键（含中文与空格特殊字符）不外泄——
 * modalFile 仅按切片 ID 构造知识库预览端点（桶概念已退役，旧存储预览 URL 编码口径随之消失）；
 * ⑥ 契约面断言：两个响应 record 组件集合恰为约定字段，不含 references/degraded/
 * contextData/rawData 等不外露分量。</p>
 *
 * @author DeepDataAgent
 */
class RetrievalResponseConvertTest {

    /** 测试答案文本 */
    private static final String ANSWER = "按平台规则第 8 条处理";

    /** 多模态资源预览端点模板（modalFile 期望值：切片媒体在线预览，仅暴露切片 ID） */
    private static final String PREVIEW_URL_TEMPLATE = "/api/v1/knowledge-base/chunks/%s/media";

    /**
     * ① 答案形态：answer 原样透传。
     */
    @Test
    void should_carryAnswerOnly_when_toAnswerResponse_given_answerText() {
        // given // when
        RetrievalAnswerResponse response = RetrievalResponseConvert.INSTANCE.toAnswerResponse(ANSWER);

        // then
        assertEquals(ANSWER, response.answer());
    }

    /**
     * ① 答案生成降级（answer=null）：形态原样透传 null，不改写为空串。
     */
    @Test
    void should_keepNullAnswer_when_toAnswerResponse_given_stage5Degraded() {
        // given // when
        RetrievalAnswerResponse response = RetrievalResponseConvert.INSTANCE.toAnswerResponse(null);

        // then
        assertNull(response.answer());
    }

    /**
     * ② 切片形态：明细按相关性序逐条投影（正文全量、来源文件、资源路径、分值）。
     */
    @Test
    void should_mapOrderedChunkDetails_when_toChunksResponse_given_fullChunkViews() {
        // given：精排序 [102, 101]，首条携图片引用、次条为纯文本
        List<RetrievalChunkView> chunkViews = List.of(
                new RetrievalChunkView(102L, "超时赔付标准见第 8 条", "policy.pdf",
                        "rag/7/42/images/chart.png", 0.033D),
                new RetrievalChunkView(101L, "发货流程说明", "manual.pdf", null, 0.016D));

        // when
        RetrievalChunksResponse response = RetrievalResponseConvert.INSTANCE.toChunksResponse(chunkViews);

        // then
        List<ChunkReferenceResponse> chunks = response.chunks();
        assertEquals(List.of(102L, 101L), chunks.stream().map(ChunkReferenceResponse::chunkId).toList());
        assertEquals(List.of("超时赔付标准见第 8 条", "发货流程说明"),
                chunks.stream().map(ChunkReferenceResponse::chunkContent).toList());
        assertEquals(List.of("policy.pdf", "manual.pdf"),
                chunks.stream().map(ChunkReferenceResponse::sourceFile).toList());
        assertEquals(String.format(PREVIEW_URL_TEMPLATE, 102L), chunks.get(0).modalFile());
        assertEquals(0.033D, chunks.get(0).score());
        assertEquals(0.016D, chunks.get(1).score());
    }

    /**
     * ③ 明细为 null / 空列表：chunks 归一空表而非 null，且响应体本身不为 null。
     */
    @Test
    void should_returnEmptyChunks_when_toChunksResponse_given_nullOrEmptyChunkViews() {
        // given // when
        RetrievalChunksResponse fromNull = RetrievalResponseConvert.INSTANCE.toChunksResponse(null);
        RetrievalChunksResponse fromEmpty = RetrievalResponseConvert.INSTANCE.toChunksResponse(List.of());

        // then
        assertNotNull(fromNull);
        assertNotNull(fromNull.chunks());
        assertTrue(fromNull.chunks().isEmpty());
        assertNotNull(fromEmpty.chunks());
        assertTrue(fromEmpty.chunks().isEmpty());
    }

    /**
     * ③ 明细列表含 null 元素（防御性口径）：过滤后不产生空条目，顺序保持。
     */
    @Test
    void should_skipNullElements_when_toChunksResponse_given_listWithNullView() {
        // given：可含 null 的可变列表构造器（List.of 拒收 null）
        List<RetrievalChunkView> views = Arrays.asList(
                new RetrievalChunkView(9L, "切片正文", "doc.pdf", null, 0.5D), null,
                new RetrievalChunkView(10L, "第二条", "doc2.pdf", null, 0.4D));

        // when
        RetrievalChunksResponse response = RetrievalResponseConvert.INSTANCE.toChunksResponse(views);

        // then
        assertEquals(List.of(9L, 10L),
                response.chunks().stream().map(ChunkReferenceResponse::chunkId).toList());
        assertEquals("切片正文", response.chunks().get(0).chunkContent());
    }

    /**
     * ④ 文本切片（无媒体引用）：modalFile 为 null，供前端判定不可点击查看。
     */
    @Test
    void should_returnNullModalFile_when_toChunksResponse_given_textChunkView() {
        // given
        List<RetrievalChunkView> views = List.of(
                new RetrievalChunkView(5L, "纯文本正文", "text.pdf", null, 0.7D));

        // when
        RetrievalChunksResponse response = RetrievalResponseConvert.INSTANCE.toChunksResponse(views);

        // then
        assertNull(response.chunks().get(0).modalFile());
        assertEquals("纯文本正文", response.chunks().get(0).chunkContent());
    }

    /**
     * ⑤ 对象键含中文与空格：URL 不再承载对象键，modalFile 恒为按切片 ID 构造的预览端点，
     * 对象键与存储形态（bucket / objectKey）一律不外泄，由服务端按切片登记的引用定位。
     */
    @Test
    void should_notLeakObjectKey_when_toChunksResponse_given_specialCharacterObjectKey() {
        // given
        List<RetrievalChunkView> views = List.of(new RetrievalChunkView(6L, "图说", "图 册.pdf",
                "rag/7/销售 图.png", 0.6D));

        // when
        RetrievalChunksResponse response = RetrievalResponseConvert.INSTANCE.toChunksResponse(views);

        // then：仅切片 ID 可见，对象键字符零出现
        String modalFile = response.chunks().get(0).modalFile();
        assertEquals(String.format(PREVIEW_URL_TEMPLATE, 6L), modalFile);
        assertFalse(modalFile.contains("销售"), "对象键不得外泄到预览 URL");
        assertFalse(modalFile.contains("bucket"), "桶概念已退役，URL 不得携带桶参数");
    }

    /**
     * ⑥ 契约面断言：两个响应 record 组件集合恰为约定的单字段形态，
     * 引用列表、降级标记与内部结构（contextData / rawData）一律不在对外组件中。
     */
    @Test
    void should_exposeOnlyAgreedComponents_when_inspectBothResponseRecordComponents() {
        // given // when
        Set<String> answerNames = Arrays.stream(RetrievalAnswerResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        Set<String> chunksNames = Arrays.stream(RetrievalChunksResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        Set<String> chunkNames = Arrays.stream(ChunkReferenceResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // then
        assertEquals(Set.of("answer"), answerNames);
        assertEquals(Set.of("chunks"), chunksNames);
        assertEquals(Set.of("chunkId", "chunkContent", "sourceFile", "modalFile", "score"), chunkNames);
    }
}
