package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultChunkEmbeddingApi} 契约适配器单测。
 * <p>覆盖：pgvector 字面量编码口径（定点十进制、去尾零、禁科学计数法）、单条正文请求形态、
 * 未配置嵌入模型时零远程调用返回「无向量」、上游返回数量不符抛可归因异常、
 * 上游异常原样上抛、入参非法 Fail-fast、上游返回空向量的降级。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultChunkEmbeddingApiTest {

    /** 测试目标知识库主键 */
    private static final Long KB_ID = 7L;

    /** 测试切片正文 */
    private static final String CHUNK_CONTENT = "Alice met Bob";

    /** 测试嵌入模型配置ID */
    private static final String PROFILE_ID = "embed-profile-1";

    /** 知识库服务契约 Mock（嵌入模型 profileId 解析） */
    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    /** 向量计算端口 Mock */
    @Mock
    private EmbeddingClient embeddingClient;

    /** 被测契约实现 */
    @InjectMocks
    private DefaultChunkEmbeddingApi chunkEmbeddingApi;

    /**
     * 场景：知识库已配置嵌入模型，上游返回单个向量。
     * 预期：返回去尾零的定点十进制字面量，且按单条正文调用一次 embedBatch。
     */
    @Test
    void should_returnPlainVectorLiteral_when_embedLiteral_given_configuredProfileId() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT)))
                .thenReturn(List.of(new float[]{0.5f, 0.25f, 0.0f}));

        // when
        String literal = chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT);

        // then
        assertEquals("[0.5,0.25,0]", literal, "字面量应为去尾零、逗号拼接、方括号包裹的定点十进制");
        verify(embeddingClient).embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT));
    }

    /**
     * 场景：上游返回含超大与超小数量级的分量（可被二进制精确表示）。
     * 预期：全部渲染为定点十进制，不出现科学计数法（E/e）。
     */
    @Test
    void should_renderPlainDecimalWithoutScientificNotation_when_embedLiteral_given_extremeComponents() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT)))
                .thenReturn(List.of(new float[]{0.5f, 0.25f, 0.0f, 1.0E10f, 0.00048828125f}));

        // when
        String literal = chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT);

        // then
        assertEquals("[0.5,0.25,0,10000000000,0.00048828125]", literal);
        assertFalse(literal.contains("E"), "字面量不得出现科学计数法大写 E");
        assertFalse(literal.contains("e"), "字面量不得出现科学计数法小写 e");
    }

    /**
     * 场景：知识库未配置嵌入模型（profileId 为 null）。
     * 预期：返回 null（「无向量」），不发起任何远程调用。
     */
    @Test
    void should_returnNullWithoutRemoteCall_when_embedLiteral_given_nullProfileId() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(null);

        // when
        String literal = chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT);

        // then
        assertNull(literal, "未配置嵌入模型应按「无向量」降级返回 null");
        verifyNoInteractions(embeddingClient);
    }

    /**
     * 场景：知识库嵌入模型 profileId 为空白字符串。
     * 预期：返回 null（「无向量」），不发起任何远程调用。
     */
    @Test
    void should_returnNullWithoutRemoteCall_when_embedLiteral_given_blankProfileId() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn("   ");

        // when
        String literal = chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT);

        // then
        assertNull(literal, "空白 profileId 应视同未配置，按「无向量」降级返回 null");
        verifyNoInteractions(embeddingClient);
    }

    /**
     * 场景：上游返回空列表（数量 0，与请求的 1 条不符）。
     * 预期：抛 IllegalStateException，异常信息携带数量对比。
     */
    @Test
    void should_throwIllegalStateWithCountComparison_when_embedLiteral_given_emptyVectors() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT))).thenReturn(List.of());

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT));

        // then
        assertTrue(exception.getMessage().contains("不一致"), "异常信息应说明数量不一致");
        assertTrue(exception.getMessage().contains("0/1"), "异常信息应携带数量对比 0/1");
    }

    /**
     * 场景：上游返回 2 个向量（数量与请求的 1 条不符）。
     * 预期：抛 IllegalStateException，异常信息携带数量对比。
     */
    @Test
    void should_throwIllegalStateWithCountComparison_when_embedLiteral_given_twoVectors() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT)))
                .thenReturn(List.of(new float[]{0.5f}, new float[]{0.25f}));

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT));

        // then
        assertTrue(exception.getMessage().contains("不一致"), "异常信息应说明数量不一致");
        assertTrue(exception.getMessage().contains("2/1"), "异常信息应携带数量对比 2/1");
    }

    /**
     * 场景：向量化上游抛出异常。
     * 预期：同一个异常实例原样上抛（可归因），不做任何包装或吞没。
     */
    @Test
    void should_propagateUpstreamExceptionAsIs_when_embedLiteral_given_embedBatchThrows() {
        // given
        IllegalStateException upstreamFailure = new IllegalStateException("embedding 服务不可用");
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT))).thenThrow(upstreamFailure);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT));

        // then
        assertSame(upstreamFailure, thrown, "上游异常应原样上抛，由调用方使本次切片变更整体失败");
    }

    /**
     * 场景：上游返回数量正确但首个向量长度为空数组。
     * 预期：返回 null（「无向量」降级），与摄入侧空向量口径一致。
     */
    @Test
    void should_returnNull_when_embedLiteral_given_emptyVectorArray() {
        // given
        when(knowledgeBaseApi.findEmbeddingModelProfileIdByKbId(KB_ID)).thenReturn(PROFILE_ID);
        when(embeddingClient.embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT))).thenReturn(List.of(new float[0]));

        // when
        String literal = chunkEmbeddingApi.embedLiteral(KB_ID, CHUNK_CONTENT);

        // then
        assertNull(literal, "空向量应按「无向量」降级返回 null");
        verify(embeddingClient).embedBatch(PROFILE_ID, List.of(CHUNK_CONTENT));
    }

    /**
     * 场景：kbId 为空入参。
     * 预期：抛 IllegalArgumentException，零依赖交互（Fail-fast，不静默丢失向量召回）。
     */
    @Test
    void should_throwIllegalArgument_when_embedLiteral_given_nullKbId() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> chunkEmbeddingApi.embedLiteral(null, CHUNK_CONTENT));

        // then
        assertTrue(exception.getMessage().contains("知识库ID"), "异常信息应指明非法入参为知识库ID");
        verifyNoInteractions(knowledgeBaseApi, embeddingClient);
    }

    /**
     * 场景：切片正文为空白入参。
     * 预期：抛 IllegalArgumentException，零依赖交互（Fail-fast，不发起无意义远程调用）。
     */
    @Test
    void should_throwIllegalArgument_when_embedLiteral_given_blankChunkContent() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> chunkEmbeddingApi.embedLiteral(KB_ID, "   "));

        // then
        assertTrue(exception.getMessage().contains("切片正文"), "异常信息应指明非法入参为切片正文");
        verifyNoInteractions(knowledgeBaseApi, embeddingClient);
    }
}