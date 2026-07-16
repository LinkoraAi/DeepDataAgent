package com.linkroa.deepdataagent.agent.infrastructure.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果 Hook
 * <p>拦截 Agent 的工具调用事件，识别 web_search 工具的结果并发送到前端。</p>
 */
public class SearchResultsHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SearchResultsHook.class);
    private static final String WEB_SEARCH_TOOL_NAME = "web_search";

    /**
     * 处理 Hook 事件
     * <p>拦截 PreActingEvent 和 PostActingEvent，识别 web_search 工具调用。</p>
     *
     * @param event Hook 事件
     * @param <T> 事件类型
     * @return 处理后的事件
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            handlePreActing(preActing);
        } else if (event instanceof PostActingEvent postActing) {
            handlePostActing(postActing);
        }
        return Mono.just(event);
    }

    /**
     * 处理工具执行前事件
     *
     * @param event 执行前事件
     */
    private void handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse != null && WEB_SEARCH_TOOL_NAME.equals(toolUse.getName())) {
            log.debug("web_search 工具即将执行");
        }
    }

    /**
     * 处理工具执行后事件
     * <p>记录 web_search 工具的执行结果。</p>
     *
     * @param event 执行后事件
     */
    private void handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock toolResult = event.getToolResult();

        if (toolUse != null && WEB_SEARCH_TOOL_NAME.equals(toolUse.getName())) {
            log.debug("web_search 工具执行完成");

            if (toolResult != null && toolResult.getOutput() != null) {
                List<ContentBlock> output = toolResult.getOutput();
                StringBuilder sb = new StringBuilder();
                for (ContentBlock block : output) {
                    if (block instanceof TextBlock) {
                        sb.append(((TextBlock) block).getText());
                    }
                }
                String resultText = sb.toString();
                log.debug("web_search 结果: {}", resultText);
            }
        }
    }
}
