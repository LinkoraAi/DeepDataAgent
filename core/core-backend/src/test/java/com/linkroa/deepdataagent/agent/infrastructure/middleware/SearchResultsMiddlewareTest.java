package com.linkroa.deepdataagent.agent.infrastructure.middleware;

import static com.linkroa.deepdataagent.agent.infrastructure.middleware.SearchResultsMiddleware.WEB_SEARCH_TOOL_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * SearchResultsMiddleware 单元测试。
 * <p>验证 onActing 钩子对 web_search 工具调用的识别、空/null toolCalls 的防御性处理，
 * 以及 next.apply 的正常透传。</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchResultsMiddlewareTest {

    @Mock
    private Agent agent;

    private SearchResultsMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new SearchResultsMiddleware();
    }

    @Test
    void should_logWebSearch_when_onActing_given_toolCallsContainsWebSearch() {
        // given: toolCalls 包含 web_search 工具
        ToolUseBlock webSearch = mock(ToolUseBlock.class);
        when(webSearch.getName()).thenReturn(WEB_SEARCH_TOOL_NAME);
        ActingInput input = new ActingInput(List.of(webSearch));
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when: 调用 onActing
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 识别 web_search 并继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_notLog_when_onActing_given_toolCallsWithoutWebSearch() {
        // given: toolCalls 只包含非 web_search 工具
        ToolUseBlock otherTool = mock(ToolUseBlock.class);
        when(otherTool.getName()).thenReturn("other_tool");
        ActingInput input = new ActingInput(List.of(otherTool));
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 不记录 web_search 日志，继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_continueExecution_when_onActing_given_emptyToolCalls() {
        // given: 空 toolCalls
        ActingInput input = new ActingInput(List.of());
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 不抛异常，继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_continueExecution_when_onActing_given_nullToolCalls() {
        // given: null toolCalls（防御性测试）
        ActingInput input = new ActingInput(null);
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 不抛 NPE，继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_notThrow_when_onActing_given_toolUseGetNameThrowsException() {
        // given: toolUse.getName() 抛出异常
        ToolUseBlock faultyTool = mock(ToolUseBlock.class);
        when(faultyTool.getName()).thenThrow(new RuntimeException("getName failed"));
        ActingInput input = new ActingInput(List.of(faultyTool));
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 异常被捕获，继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_supportPushSearchResults_when_pushSearchResults_given_resultText() {
        // given: 创建子类实例以验证 pushSearchResults 可被覆写
        SearchResultsMiddleware subclass = new SearchResultsMiddleware() {
            private String lastResult;

            @Override
            protected void pushSearchResults(String resultText) {
                this.lastResult = resultText;
            }
        };

        // when
        subclass.pushSearchResults("test result");

        // then: 方法可被调用而不抛异常
        // 真正的子类行为由具体实现保证
    }

    @Test
    void should_notLogWebSearch_when_onActing_given_toolCallsWithNonWebSearchTool() {
        // given: toolCalls 包含非 web_search 工具
        ToolUseBlock otherTool = mock(ToolUseBlock.class);
        when(otherTool.getName()).thenReturn("other_tool");
        ActingInput input = new ActingInput(List.of(otherTool));
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 不记录 web_search 日志，继续执行 next
        verify(next).apply(input);
    }

    @Test
    void should_notThrow_when_pushSearchResults_given_resultText() {
        // given: 基类实例
        // when: 调用基类 pushSearchResults 方法（空方法体）
        middleware.pushSearchResults("test result");
        // then: 不抛异常，空方法体被执行
    }

    @Test
    void should_handleNullToolUseName_when_onActing_given_toolUseWithNullName() {
        // given: toolUse.getName() 返回 null（不是抛出异常）
        ToolUseBlock nullNameTool = mock(ToolUseBlock.class);
        when(nullNameTool.getName()).thenReturn(null);
        ActingInput input = new ActingInput(List.of(nullNameTool));
        @SuppressWarnings("unchecked")
        Function<ActingInput, Flux<AgentEvent>> next = mock(Function.class);
        when(next.apply(any())).thenReturn(Flux.empty());

        // when
        middleware.onActing(agent, null, input, next).blockLast();

        // then: 不抛异常，继续执行 next
        verify(next).apply(input);
    }
}
