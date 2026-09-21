package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalResult;
import com.linkroa.deepdataagent.rag.application.service.RetrievalApplicationService;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultRetrievalApi} 检索服务契约实现单测。
 * <p>覆盖薄委托三场景：①有效请求透传编排应用服务并原样返回结果；②知识库不可用（业务异常）
 * 时按原语义向消费方抛出；③请求为空时参数非法异常透传。核心验证「契约层零业务规则、逐调用委托」。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultRetrievalApiTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 7L;

    /** 检索问题 */
    private static final String QUERY = "如何配置知识库";

    /** 检索编排应用服务 Mock */
    @Mock
    private RetrievalApplicationService retrievalApplicationService;

    /** 被测检索契约实现 */
    @InjectMocks
    private DefaultRetrievalApi defaultRetrievalApi;

    /**
     * 主流程：有效请求应原样委托应用服务并透传其返回结果。
     */
    @Test
    void should_returnSameResult_when_search_given_validQuery() {
        // given
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, null, null, null);
        RetrievalResult expected = new RetrievalResult("答案", null, List.of(), Map.of(), List.of(), false);
        when(retrievalApplicationService.search(query)).thenReturn(expected);

        // when
        RetrievalResult actual = defaultRetrievalApi.search(query);

        // then
        assertSame(expected, actual);
        verify(retrievalApplicationService, times(1)).search(query);
    }

    /**
     * 依赖失败：知识库不可用时应用服务抛业务异常，契约层应原样透传、不吞没。
     */
    @Test
    void should_throwBusinessException_when_search_given_kbUnavailable() {
        // given
        RetrievalQuery query = new RetrievalQuery(KB_ID, QUERY, null, null,
                null, null, null, null, null);
        when(retrievalApplicationService.search(query))
                .thenThrow(new DeepDataAgentException("知识库不存在或不可用，拒绝检索"));

        // when & then
        assertThrows(DeepDataAgentException.class, () -> defaultRetrievalApi.search(query));
        verify(retrievalApplicationService, times(1)).search(query);
    }

    /**
     * 参数非法：请求为空时应用服务抛非法参数异常，契约层应透传给消费方。
     */
    @Test
    void should_throwIllegalArgument_when_search_given_nullQuery() {
        // given
        when(retrievalApplicationService.search(any()))
                .thenThrow(new IllegalArgumentException("检索请求不能为空"));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> defaultRetrievalApi.search(null));
        verify(retrievalApplicationService, times(1)).search(null);
    }
}
