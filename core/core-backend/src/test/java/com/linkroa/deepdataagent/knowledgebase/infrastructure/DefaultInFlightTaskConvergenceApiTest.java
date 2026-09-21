package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.service.InFlightTaskConvergenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultInFlightTaskConvergenceApi} 在飞任务收敛契约实现单测。
 * <p>本类只做契约适配、不含任何业务规则：三个收敛方法均为纯委托——解析收敛 / 文档清退收敛 /
 * 整库清退收敛全部委托窄组件 {@link InFlightTaskConvergenceService}。覆盖「委托目标正确（不误投）」与
 * 「返回值原样透传（true / false）」两类断言；状态一致性校验与条件置态的判定细节由被委托的窄组件单测覆盖。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultInFlightTaskConvergenceApiTest {

    /** 测试用文档ID */
    private static final Long DOC_ID = 100L;

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 在飞收敛的失败留痕（透传断言用） */
    private static final String CONVERGE_REASON = "[RESTART] 悬挂任务收敛";

    @Mock
    private InFlightTaskConvergenceService inFlightTaskConvergenceService;

    @InjectMocks
    private DefaultInFlightTaskConvergenceApi api;

    @Test
    void should_delegateAndReturnTrue_when_convergeIngestion_given_convergedDocument() {
        // given：解析侧状态一致且已置 FAILED
        when(inFlightTaskConvergenceService.convergeIngestion(DOC_ID, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = api.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then：只委托窄组件，返回值原样透传
        assertTrue(converged);
        verify(inFlightTaskConvergenceService).convergeIngestion(DOC_ID, CONVERGE_REASON);
    }

    @Test
    void should_delegateAndReturnFalse_when_convergeIngestion_given_inconsistentDocument() {
        // given：解析侧状态不一致（零写操作）
        when(inFlightTaskConvergenceService.convergeIngestion(DOC_ID, CONVERGE_REASON)).thenReturn(false);

        // when
        boolean converged = api.convergeIngestion(DOC_ID, CONVERGE_REASON);

        // then
        assertFalse(converged);
        verify(inFlightTaskConvergenceService).convergeIngestion(DOC_ID, CONVERGE_REASON);
    }

    @Test
    void should_delegateAndReturnTrue_when_convergeDocumentCleanup_given_convergedDocument() {
        // given：文档清退侧状态一致且已置 DELETE_FAILED
        when(inFlightTaskConvergenceService.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = api.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);

        // then
        assertTrue(converged);
        verify(inFlightTaskConvergenceService).convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);
    }

    @Test
    void should_delegateAndReturnFalse_when_convergeDocumentCleanup_given_inconsistentDocument() {
        // given：文档清退侧状态不一致（零写操作）
        when(inFlightTaskConvergenceService.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON)).thenReturn(false);

        // when
        boolean converged = api.convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);

        // then
        assertFalse(converged);
        verify(inFlightTaskConvergenceService).convergeDocumentCleanup(DOC_ID, CONVERGE_REASON);
    }

    @Test
    void should_delegateAndReturnTrue_when_convergeKbCleanup_given_convergedKnowledgeBase() {
        // given：整库清退侧状态一致且已置 DELETE_FAILED
        when(inFlightTaskConvergenceService.convergeKbCleanup(KB_ID, CONVERGE_REASON)).thenReturn(true);

        // when
        boolean converged = api.convergeKbCleanup(KB_ID, CONVERGE_REASON);

        // then：只委托窄组件，返回值原样透传
        assertTrue(converged);
        verify(inFlightTaskConvergenceService).convergeKbCleanup(KB_ID, CONVERGE_REASON);
    }

    @Test
    void should_delegateAndReturnFalse_when_convergeKbCleanup_given_inconsistentKnowledgeBase() {
        // given：整库清退侧状态不一致（零写操作）
        when(inFlightTaskConvergenceService.convergeKbCleanup(KB_ID, CONVERGE_REASON)).thenReturn(false);

        // when
        boolean converged = api.convergeKbCleanup(KB_ID, CONVERGE_REASON);

        // then
        assertFalse(converged);
        verify(inFlightTaskConvergenceService).convergeKbCleanup(KB_ID, CONVERGE_REASON);
    }
}