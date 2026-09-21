package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.rag.application.service.DocumentGraphConvergenceService;
import com.linkroa.deepdataagent.rag.application.service.DocumentGraphConvergenceService.ConvergencePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultDocumentGraphConvergenceApi} 契约适配器单测（组 6 两段式契约）。
 * <p>覆盖：prepare/apply/reconcilePendingVectorContent 三步对应用服务的原样委托（含全部入参
 * 透传）；句柄跨实现伪造（非 {@link ConvergencePlan} 类型）在触达服务前被非法参数异常拒绝；
 * null 句柄按契约语义放行到服务侧（apply 由服务防御、reconcile 零操作）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultDocumentGraphConvergenceApiTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试用审计锚点文档ID */
    private static final Long DOC_ID = 100L;

    /** 被清退的切片ID集合 */
    private static final List<Long> CHUNK_IDS = List.of(101L, 102L);

    /** 操作人 */
    private static final String OPERATOR = "tester";

    @Mock
    private DocumentGraphConvergenceService documentGraphConvergenceService;

    @InjectMocks
    private DefaultDocumentGraphConvergenceApi convergenceApi;

    /**
     * 场景：prepare 委托。预期：四参原样透传应用服务，返回其句柄实例（同一引用上抛）。
     */
    @Test
    void should_delegateAllArguments_when_prepare_given_nonEmptyBatch() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, CHUNK_IDS);
        when(documentGraphConvergenceService.prepare(KB_ID, DOC_ID, CHUNK_IDS, OPERATOR)).thenReturn(plan);

        // when
        DocumentGraphConvergencePlan result = convergenceApi.prepare(KB_ID, DOC_ID, CHUNK_IDS, OPERATOR);

        // then
        assertSame(plan, result);
        verify(documentGraphConvergenceService).prepare(KB_ID, DOC_ID, CHUNK_IDS, OPERATOR);
    }

    /**
     * 场景：apply 委托。预期：句柄类型合法时以同一实例交应用服务，返回其结果。
     */
    @Test
    void should_castAndDelegate_when_apply_given_validConvergencePlanHandle() {
        // given
        ConvergencePlan plan = new ConvergencePlan(KB_ID, CHUNK_IDS, null, null);
        DocumentGraphConvergenceResult expected = DocumentGraphConvergenceResult.empty();
        when(documentGraphConvergenceService.apply(plan)).thenReturn(expected);

        // when
        DocumentGraphConvergenceResult result = convergenceApi.apply(plan);

        // then
        assertSame(expected, result);
        verify(documentGraphConvergenceService).apply(plan);
    }

    /**
     * 场景（契约防御）：外部伪造的跨实现句柄进入 apply。
     * 预期：非法参数异常，MUST NOT 触达应用服务（不静默降级）。
     */
    @Test
    void should_rejectForeignHandleWithoutDelegation_when_apply_given_foreignPlanType() {
        // given：匿名实现伪造句柄
        DocumentGraphConvergencePlan foreign = new DocumentGraphConvergencePlan() {
        };

        // when & then
        assertThrows(IllegalArgumentException.class, () -> convergenceApi.apply(foreign));
        verifyNoInteractions(documentGraphConvergenceService);
    }

    /**
     * 场景：reconcilePendingVectorContent 委托。预期：句柄与结果配对透传应用服务。
     */
    @Test
    void should_delegatePair_when_reconcilePendingVectorContent_given_planAndResult() {
        // given
        ConvergencePlan plan = ConvergencePlan.inert(KB_ID, CHUNK_IDS);
        DocumentGraphConvergenceResult result = DocumentGraphConvergenceResult.empty();

        // when
        convergenceApi.reconcilePendingVectorContent(plan, result);

        // then
        verify(documentGraphConvergenceService).reconcilePendingVectorContent(plan, result);
    }

    /**
     * 场景：null 句柄收口。预期：适配器放行（castPlan 归 null），由服务侧零操作语义兜底。
     */
    @Test
    void should_passNullThrough_when_reconcilePendingVectorContent_given_nullPlan() {
        // when
        convergenceApi.reconcilePendingVectorContent(null, DocumentGraphConvergenceResult.empty());

        // then：透传服务（服务内判空短路，适配器不吞不抛）
        verify(documentGraphConvergenceService)
                .reconcilePendingVectorContent(any(), any(DocumentGraphConvergenceResult.class));
    }
}
