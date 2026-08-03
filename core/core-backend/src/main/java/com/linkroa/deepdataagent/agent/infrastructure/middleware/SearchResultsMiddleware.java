package com.linkroa.deepdataagent.agent.infrastructure.middleware;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * 搜索结果中间件（AgentScope v2 MiddlewareBase 实现）。
 * <p>替代 v1 的 {@code SearchResultsHook}，通过 {@code onActing} 钩子识别 {@code web_search}
 * 工具调用并记录日志。预留 {@link #pushSearchResults(String)} 扩展点供未来 SSE 子类实现
 * 搜索结果推送。</p>
 *
 * <p>当前实现保持与原 {@code SearchResultsHook} 一致的 log 行为，不实现 SSE 推送
 * （后续子项目）。</p>
 */
public class SearchResultsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SearchResultsMiddleware.class);

    /** web_search 工具名称常量 */
    public static final String WEB_SEARCH_TOOL_NAME = "web_search";

    /**
     * Acting 钩子：识别 web_search 工具调用并记录日志。
     * <p>遍历 {@code input.toolCalls()}，识别名为 {@code web_search} 的工具调用并
     * {@code log.debug}。异常 try-catch 不阻断 {@code next.apply(input)}。</p>
     *
     * @param agent Agent 实例
     * @param rc    RuntimeContext（当前未使用，保留以符合接口签名）
     * @param input Acting 输入（包含工具调用列表）
     * @param next  下一步执行函数
     * @return Agent 事件流（透传 next.apply(input)）
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        try {
            List<ToolUseBlock> toolCalls = input.toolCalls();
            if (toolCalls != null) {
                for (ToolUseBlock toolUse : toolCalls) {
                    if (toolUse != null && WEB_SEARCH_TOOL_NAME.equals(toolUse.getName())) {
                        log.debug("web_search 工具即将执行");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SearchResultsMiddleware: failed to inspect tool calls: {}", e.getMessage());
        }
        return next.apply(input);
    }

    /**
     * 推送搜索结果扩展点。
     * <p>预留供未来 SSE 子类实现搜索结果推送。当前基类实现为空操作。</p>
     *
     * @param resultText 搜索结果文本
     */
    protected void pushSearchResults(String resultText) {
        // 基类空实现，后续 SSE 子项目覆写
    }
}
