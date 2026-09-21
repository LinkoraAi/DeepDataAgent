package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.application.contract.ChunkDraft;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalMetaKeys;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkPersistenceService} 单元测试：验证向量化<b>预趟分批</b>（embedBatch 切批、
 * 下标对齐、批大小夹逼）、pgvector/tsvector 字面量格式、空白画像降级、异常传播，
 * 以及单文档切片数量<b>不设上限</b>（历史 1000 上限已移除）。
 */
@ExtendWith(MockitoExtension.class)
class ChunkPersistenceServiceTest {

    /** 测试目标文档主键 */
    private static final Long DOCUMENT_ID = 42L;

    /** 测试操作人ID */
    private static final Long OPERATOR_ID = 9L;

    /** 测试向量模型配置ID */
    private static final String PROFILE_ID = "embed-profile-1";

    /** 默认单批条数（与 @Value 缺省一致，逐用例可覆写） */
    private static final int DEFAULT_BATCH_SIZE = 32;

    @Mock
    private ChunkBatchWriter chunkBatchWriter;

    @Mock
    private EmbeddingClient embeddingClient;

    @InjectMocks
    private ChunkPersistenceService chunkPersistenceService;

    /**
     * 显式设定单批条数缺省值（@InjectMocks 不处理 @Value，须手动注入保证确定性）。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chunkPersistenceService, "embedBatchSize", DEFAULT_BATCH_SIZE);
    }

    @Test
    void should_persistDraftsWithVectorOnly_when_replaceForDocument_given_validProfileId() {
        // given
        ChunkVO chunk = new ChunkVO(1, "Alice met Bob", 5,
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "Alice met Bob", Map.of("page", 3)));
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("Alice met Bob")))
                .thenReturn(List.of(new float[]{0.5f, 0.25f}));

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), PROFILE_ID, OPERATOR_ID);

        // then
        ChunkDraft draft = captureSingleDraft();
        assertEquals(1, draft.sequence());
        assertEquals("Alice met Bob", draft.content());
        assertEquals(5, draft.tokens());
        assertEquals("{\"page\":3}", draft.metadata());
        assertEquals("TEXT", draft.chunkContentType());
        assertEquals("[0.5,0.25]", draft.embeddingVector());
        // 草稿不再携带预分词字面量（tsvector 由知识库落库 SQL 现算，
        // 原文即 content 分量已在上行断言）
        verify(embeddingClient).embedBatch(PROFILE_ID, List.of("Alice met Bob"));
    }

    @Test
    void should_returnIdentityMapFromWriter_when_replaceForDocument_given_writerReturnsMapping() {
        // given：契约（落库事务内）回传「序号→主键」映射；画像空白跳过向量化，聚焦透传语义
        ChunkVO chunk = new ChunkVO(1, "Alice met Bob", 5, null);
        Map<Integer, Long> identityMap = Map.of(1, 9001L);
        when(chunkBatchWriter.replaceForDocument(eq(DOCUMENT_ID), anyList(), eq(OPERATOR_ID)))
                .thenReturn(identityMap);

        // when
        Map<Integer, Long> result = chunkPersistenceService.replaceForDocument(
                DOCUMENT_ID, List.of(chunk), null, OPERATOR_ID);

        // then：落库回传映射原样透传给摄入管线，消费方无需回查数据库
        assertEquals(identityMap, result);
    }

    @Test
    void should_embedAllChunksInSingleBatch_when_replaceForDocument_given_chunksWithinBatchSize() {
        // given：2 片 ≤ 批大小 32 → 一次批量调用，返回按入参下标对齐
        ChunkVO first = new ChunkVO(1, "alpha", 1, null);
        ChunkVO second = new ChunkVO(2, "beta", 1, null);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("alpha", "beta")))
                .thenReturn(List.of(new float[]{1.5f}, new float[]{-0.25f}));

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(first, second), PROFILE_ID, OPERATOR_ID);

        // then：草案向量与切片顺序一一对应
        List<ChunkDraft> drafts = captureDrafts();
        assertEquals(2, drafts.size());
        assertEquals("[1.5]", drafts.get(0).embeddingVector());
        assertEquals("[-0.25]", drafts.get(1).embeddingVector());
        verify(embeddingClient).embedBatch(PROFILE_ID, List.of("alpha", "beta"));
    }

    @Test
    void should_splitIntoBatchesPreservingOrder_when_replaceForDocument_given_batchSizeSmallerThanChunks() {
        // given：5 片、批大小 2 → 应切 3 批 [2,2,1]；向量以文本尾数字编码，错位即断言失败
        ReflectionTestUtils.setField(chunkPersistenceService, "embedBatchSize", 2);
        List<ChunkVO> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(new ChunkVO(i, "c" + i, 1, null));
        }
        when(embeddingClient.embedBatch(eq(PROFILE_ID), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(new float[]{Float.parseFloat(text.substring(1))});
            }
            return vectors;
        });

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, chunks, PROFILE_ID, OPERATOR_ID);

        // then：按请求顺序分批发起，向量按下标回填不打乱
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(3)).embedBatch(eq(PROFILE_ID), textsCaptor.capture());
        assertEquals(List.of(List.of("c1", "c2"), List.of("c3", "c4"), List.of("c5")),
                textsCaptor.getAllValues());
        List<ChunkDraft> drafts = captureDrafts();
        assertEquals(5, drafts.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("[" + (i + 1) + "]", drafts.get(i).embeddingVector(), "第 " + (i + 1) + " 片向量错位");
        }
    }

    @Test
    void should_embedOneTextPerCall_when_replaceForDocument_given_batchSizeBelowOne() {
        // given：批大小被夹逼为 1 → 逐条批量调用（等价旧逐条语义，不发起空批）
        ReflectionTestUtils.setField(chunkPersistenceService, "embedBatchSize", 0);
        ChunkVO first = new ChunkVO(1, "c1", 1, null);
        ChunkVO second = new ChunkVO(2, "c2", 1, null);
        when(embeddingClient.embedBatch(eq(PROFILE_ID), anyList()))
                .thenReturn(List.of(new float[]{1f}), List.of(new float[]{2f}));

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(first, second), PROFILE_ID, OPERATOR_ID);

        // then
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(2)).embedBatch(eq(PROFILE_ID), textsCaptor.capture());
        assertEquals(List.of(List.of("c1"), List.of("c2")), textsCaptor.getAllValues());
    }

    @Test
    void should_renderVectorComponentsAsPlainDecimal_when_replaceForDocument_given_extremeFloatValues() {
        // given：0.25 / 2^-11 等可被二进制精确表示的分量，含超大与超小数量级
        ChunkVO chunk = new ChunkVO(1, "extreme text", 2, null);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("extreme text")))
                .thenReturn(List.of(new float[]{0.5f, 0.25f, 0.0f, 1.0E10f, 0.00048828125f}));

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), PROFILE_ID, OPERATOR_ID);

        // then：分量不得出现科学计数法（E/e），全部为定点十进制
        String vectorLiteral = captureSingleDraft().embeddingVector();
        assertEquals("[0.5,0.25,0,10000000000,0.00048828125]", vectorLiteral);
        assertFalse(vectorLiteral.contains("E"));
        assertFalse(vectorLiteral.contains("e"));
    }

    @Test
    void should_skipEmbeddingAndPersistNullVector_when_replaceForDocument_given_blankProfileId() {
        // given
        ChunkVO chunk = new ChunkVO(1, "Alice met Bob", 5, null);

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), "   ", OPERATOR_ID);

        // then：不发起远程向量化，向量降级为 null；全文表示与向量降级无关（原文随草稿携带，DB 现算）
        ChunkDraft draft = captureSingleDraft();
        assertNull(draft.embeddingVector());
        assertEquals("Alice met Bob", draft.content());
        assertNull(draft.chunkContentType());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void should_skipEmbeddingAndPersistNullVector_when_replaceForDocument_given_nullProfileId() {
        // given
        ChunkVO chunk = new ChunkVO(1, "Alice met Bob", 5, null);

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), null, OPERATOR_ID);

        // then
        ChunkDraft draft = captureSingleDraft();
        assertNull(draft.embeddingVector());
        assertEquals("Alice met Bob", draft.content());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void should_propagateExceptionAndPersistNothing_when_replaceForDocument_given_embedBatchThrows() {
        // given：一批失败即整篇失败（爆炸半径与逐条时代一致），事务外无部分落库
        ChunkVO chunk = new ChunkVO(1, "boom text", 3, null);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("boom text")))
                .thenThrow(new IllegalStateException("embedding 服务不可用"));

        // when & then
        assertThrows(IllegalStateException.class,
                () -> chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), PROFILE_ID, OPERATOR_ID));
        verifyNoInteractions(chunkBatchWriter);
    }

    @Test
    void should_throwIllegalState_when_replaceForDocument_given_embedBatchSizeMismatch() {
        // given：端口违约——返回数量少于请求数量（按下标对齐的前提被破坏）
        ChunkVO chunk = new ChunkVO(1, "short result", 2, null);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("short result"))).thenReturn(List.of());

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), PROFILE_ID, OPERATOR_ID));
        assertTrue(exception.getMessage().contains("不一致"));
        verifyNoInteractions(chunkBatchWriter);
    }

    @Test
    void should_persistAllDrafts_when_replaceForDocument_given_1001ChunksWithoutCap() {
        // given：1001 片（历史 1000 上限会整批拒绝；现已无上限）→ 按 32/批共 32 次调用
        List<ChunkVO> chunks = new ArrayList<>();
        for (int i = 1; i <= 1001; i++) {
            chunks.add(new ChunkVO(i, "chunk " + i, 1, null));
        }
        when(embeddingClient.embedBatch(eq(PROFILE_ID), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{1f});
            }
            return vectors;
        });

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, chunks, PROFILE_ID, OPERATOR_ID);

        // then：31 个满批 + 1 个 9 条尾批；全部草案照常落库
        verify(embeddingClient, times(32)).embedBatch(eq(PROFILE_ID), anyList());
        List<ChunkDraft> drafts = captureDrafts();
        assertEquals(1001, drafts.size());
        assertEquals("[1]", drafts.get(1000).embeddingVector());
    }

    @Test
    void should_throwDeepDataAgentException_when_replaceForDocument_given_emptyChunks() {
        // given & when & then：空集合与 null 均整批拒绝，不发起任何远程与落库交互
        assertThrows(DeepDataAgentException.class,
                () -> chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(), PROFILE_ID, OPERATOR_ID));
        assertThrows(DeepDataAgentException.class,
                () -> chunkPersistenceService.replaceForDocument(DOCUMENT_ID, null, PROFILE_ID, OPERATOR_ID));
        verifyNoInteractions(chunkBatchWriter);
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void should_persistMediaImageRefsInOriginalItem_when_replaceForDocument_given_blockWithMediaRefs() {
        // given：多模态块 meta 携带 Stage 1a 注入的对象存储引用（仅 mediaObjectKey 一键，桶概念已退役）
        ChunkVO chunk = new ChunkVO(1, "图1", 3, new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1",
                Map.of("img_path", "images/a.png",
                        MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY, "rag/7/42/images/a.png")));
        when(embeddingClient.embedBatch(PROFILE_ID, List.of("图1"))).thenReturn(List.of(new float[]{0.1f}));

        // when
        chunkPersistenceService.replaceForDocument(DOCUMENT_ID, List.of(chunk), PROFILE_ID, OPERATOR_ID);

        // then：块 meta 全量透传落 original_item，媒体引用键入列；仅存引用不含图片字节
        String originalItem = captureSingleDraft().metadata();
        assertTrue(originalItem.contains("\"mediaObjectKey\":\"rag/7/42/images/a.png\""),
                "mediaObjectKey 应进入 original_item，实际：" + originalItem);
        assertFalse(originalItem.contains("Bucket"), "媒体桶引用键已随桶概念退役，不得再落库");
        assertTrue(originalItem.contains("\"img_path\":\"images/a.png\""));
        assertFalse(originalItem.contains("base64"), "original_item 不得携带图片 base64/字节载荷");
    }

    /**
     * 捕获并返回唯一一条落库草案。
     *
     * @return 单条草案
     */
    private ChunkDraft captureSingleDraft() {
        List<ChunkDraft> drafts = captureDrafts();
        assertEquals(1, drafts.size());
        return drafts.get(0);
    }

    /**
     * 捕获 {@link ChunkBatchWriter#replaceForDocument} 收到的草案列表。
     *
     * @return 草案列表
     */
    @SuppressWarnings("unchecked")
    private List<ChunkDraft> captureDrafts() {
        ArgumentCaptor<List<ChunkDraft>> draftsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkBatchWriter).replaceForDocument(eq(DOCUMENT_ID), draftsCaptor.capture(), eq(OPERATOR_ID));
        return draftsCaptor.getValue();
    }
}
