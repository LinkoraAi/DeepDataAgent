package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorContentReconciler} 单元测试（纯领域离线测试，全端口 Mockito 模拟，不启动容器/数据库）。
 *
 * <p>覆盖场景（对齐 spec vector-content-consistency 与 design D2-D6）：
 * ①内容一致 → 零向量化、零更新；②内容不一致 → 补一次向量化并以期望内容窄更新（守卫取当前内容）；
 * ③守卫落空（窄更新返回 0）→ 视为跳过、不报错；④图行缺失 → 跳过；
 * ⑤超长描述经同一截断口径后一致 → 不进入收口（防「每次白付向量化」死循环回归）；
 * ⑥关系侧期望内容由关键词参与构造；⑦向量化抛异常 → 返回失败且不外抛（降级）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class VectorContentReconcilerTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1L;

    /** 向量化模型 profileId */
    private static final String EMBED_PROFILE = "model-embed";

    /** 向量内容 token 上限（须与写入路径同值） */
    private static final int TOKEN_LIMIT = 8192;

    /** 实体名 */
    private static final String ENTITY_ALICE = "Alice";

    /** 实体名（字典序较大端点） */
    private static final String ENTITY_BOB = "Bob";

    /** 向量化桩返回向量 */
    private static final float[] NEW_VECTOR = new float[]{0.7f, 0.8f};

    /** 实体图行仓储桩 */
    @Mock
    private EntityNodeGraphRepository entityNodeRepository;

    /** 关系图行仓储桩 */
    @Mock
    private RelationEdgeGraphRepository relationEdgeRepository;

    /** 实体向量仓储桩 */
    @Mock
    private EntityInfoVectorRepository entityInfoVectorRepository;

    /** 关系向量仓储桩 */
    @Mock
    private RelationInfoVectorRepository relationInfoVectorRepository;

    /** 向量化端口桩 */
    @Mock
    private EmbeddingClient embeddingClient;

    /** Token 计数器桩 */
    @Mock
    private TokenCounter tokenCounter;

    /** 被测原语 */
    @InjectMocks
    private VectorContentReconciler reconciler;

    /**
     * ①内容一致：期望内容与当前内容相同 → 零向量化、零更新。
     */
    @Test
    void should_returnConsistentWithoutEmbedAndWrite_when_reconcileEntity_given_contentMatchesCommittedDescription() {
        // given：truncate 恒等；图行描述与向量行内容派生自同一描述
        String committedDescription = "alice-desc";
        String content = EntityInfoVector.buildContent(ENTITY_ALICE, committedDescription);
        when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityNode.create(KB_ID, ENTITY_ALICE, "PERSON", committedDescription,
                        101L, "docs/a.txt")));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE, content,
                        List.of(101L), NEW_VECTOR)));

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then：一致 → 不向量化、不更新
        assertEquals(VectorContentReconciler.Result.CONSISTENT, result);
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verify(entityInfoVectorRepository, never())
                .updateContentIfUnchanged(any(), anyString(), anyString(), any(), anyString());
    }

    /**
     * ②内容不一致：以期望内容补一次向量化并窄更新（守卫取当前内容），受影响 1 行 → 已修复。
     */
    @Test
    void should_embedOnceAndNarrowUpdateWithExpected_when_reconcileEntity_given_contentDrifted() {
        // given：当前内容为过期快照派生的错位内容
        String committedDescription = "committed-desc";
        String expected = EntityInfoVector.buildContent(ENTITY_ALICE, committedDescription);
        String current = ENTITY_ALICE + "\nstale-desc";
        when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityNode.create(KB_ID, ENTITY_ALICE, "PERSON", committedDescription,
                        101L, "docs/a.txt")));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE, current,
                        List.of(101L), NEW_VECTOR)));
        when(embeddingClient.embed(EMBED_PROFILE, expected)).thenReturn(NEW_VECTOR);
        when(entityInfoVectorRepository.updateContentIfUnchanged(KB_ID, ENTITY_ALICE, expected, NEW_VECTOR, current))
                .thenReturn(1);

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then：向量化一次、以期望内容与当前内容作守卫窄更新一次
        assertEquals(VectorContentReconciler.Result.RECONCILED, result);
        verify(embeddingClient, times(1)).embed(EMBED_PROFILE, expected);
        verify(entityInfoVectorRepository, times(1))
                .updateContentIfUnchanged(KB_ID, ENTITY_ALICE, expected, NEW_VECTOR, current);
    }

    /**
     * ③守卫落空：窄更新返回 0（他人已改写内容）→ 视为跳过、不报错。
     */
    @Test
    void should_returnGuardMissedWithoutError_when_reconcileEntity_given_updateReturnsZero() {
        // given
        String committedDescription = "committed-desc";
        String expected = EntityInfoVector.buildContent(ENTITY_ALICE, committedDescription);
        String current = ENTITY_ALICE + "\nstale-desc";
        when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityNode.create(KB_ID, ENTITY_ALICE, "PERSON", committedDescription,
                        101L, "docs/a.txt")));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE, current,
                        List.of(101L), NEW_VECTOR)));
        when(embeddingClient.embed(EMBED_PROFILE, expected)).thenReturn(NEW_VECTOR);
        when(entityInfoVectorRepository.updateContentIfUnchanged(KB_ID, ENTITY_ALICE, expected, NEW_VECTOR, current))
                .thenReturn(0);

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then
        assertEquals(VectorContentReconciler.Result.GUARD_MISSED, result);
    }

    /**
     * ④图行缺失：无权威描述可派生期望内容 → 跳过，零向量化、零更新。
     */
    @Test
    void should_skipWithoutEmbedAndWrite_when_reconcileEntity_given_graphRowMissing() {
        // given
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE)).thenReturn(Optional.empty());

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then
        assertEquals(VectorContentReconciler.Result.SKIPPED, result);
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verify(entityInfoVectorRepository, never())
                .updateContentIfUnchanged(any(), anyString(), anyString(), any(), anyString());
    }

    /**
     * ⑤截断口径一致（防死循环回归）：超长描述经同一截断后期望内容 == 当前内容 → 一致，不进入收口。
     */
    @Test
    void should_returnConsistentWithoutEmbed_when_reconcileEntity_given_overlongDescriptionTruncatedEqual() {
        // given：描述超长，写入路径与收口路径均以 truncate 收敛到同一截断串
        String truncatedContent = ENTITY_ALICE + "\nTRUNCATED";
        when(tokenCounter.truncate(anyString(), eq(TOKEN_LIMIT))).thenReturn(truncatedContent);
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityNode.create(KB_ID, ENTITY_ALICE, "PERSON",
                        "x".repeat(100000), 101L, "docs/a.txt")));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE, truncatedContent,
                        List.of(101L), NEW_VECTOR)));

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then：截断后一致 → 不白付向量化（若误用未截断原串比对将永远判为不一致）
        assertEquals(VectorContentReconciler.Result.CONSISTENT, result);
        verify(embeddingClient, never()).embed(anyString(), anyString());
        verify(tokenCounter).truncate(anyString(), eq(TOKEN_LIMIT));
    }

    /**
     * ⑥关系侧关键词参与期望内容构造：期望内容由图行关键词 + 归一端点 + 描述共同派生。
     */
    @Test
    void should_includeKeywordsInExpectedContent_when_reconcileRelation_given_contentDrifted() {
        // given：图行关键词与描述与当前向量内容错位
        List<String> keywords = List.of("knows", "likes");
        String expected = RelationInfoVector.buildContent(keywords, ENTITY_ALICE, ENTITY_BOB, "rel-desc");
        String current = "stale-content";
        when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(relationEdgeRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(List.of(RelationEdge.create(KB_ID, ENTITY_ALICE, ENTITY_BOB, 1.0, "rel-desc",
                        keywords, 101L, "docs/a.txt")));
        when(relationInfoVectorRepository.findByKbIdAndUnorderedPair(KB_ID, ENTITY_ALICE, ENTITY_BOB))
                .thenReturn(Optional.of(RelationInfoVector.create(KB_ID, ENTITY_ALICE, ENTITY_BOB, current,
                        List.of(101L), NEW_VECTOR)));
        when(embeddingClient.embed(EMBED_PROFILE, expected)).thenReturn(NEW_VECTOR);
        when(relationInfoVectorRepository.updateContentIfUnchanged(KB_ID, ENTITY_ALICE, ENTITY_BOB, expected,
                NEW_VECTOR, current)).thenReturn(1);

        // when
        VectorContentReconciler.Result result = reconciler.reconcileRelation(KB_ID, ENTITY_ALICE, ENTITY_BOB,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then：期望内容含关键词，窄更新以期望内容/归一端点为参数命中
        assertEquals(VectorContentReconciler.Result.RECONCILED, result);
        verify(embeddingClient, times(1)).embed(eq(EMBED_PROFILE), eq(expected));
    }

    /**
     * ⑦向量化抛异常：原语内捕获并降级为失败结果，不外抛。
     */
    @Test
    void should_returnFailedWithoutThrowing_when_reconcileEntity_given_embeddingFailure() {
        // given
        String committedDescription = "committed-desc";
        String expected = EntityInfoVector.buildContent(ENTITY_ALICE, committedDescription);
        String current = ENTITY_ALICE + "\nstale-desc";
        when(tokenCounter.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(entityNodeRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityNode.create(KB_ID, ENTITY_ALICE, "PERSON", committedDescription,
                        101L, "docs/a.txt")));
        when(entityInfoVectorRepository.findByKbIdAndName(KB_ID, ENTITY_ALICE))
                .thenReturn(Optional.of(EntityInfoVector.create(KB_ID, ENTITY_ALICE, current,
                        List.of(101L), NEW_VECTOR)));
        when(embeddingClient.embed(EMBED_PROFILE, expected)).thenThrow(new RuntimeException("embed down"));

        // when
        VectorContentReconciler.Result result = reconciler.reconcileEntity(KB_ID, ENTITY_ALICE,
                EMBED_PROFILE, TOKEN_LIMIT);

        // then：返回失败、不外抛、不写入
        assertEquals(VectorContentReconciler.Result.FAILED, result);
        verify(entityInfoVectorRepository, never())
                .updateContentIfUnchanged(any(), anyString(), anyString(), any(), anyString());
    }
}
