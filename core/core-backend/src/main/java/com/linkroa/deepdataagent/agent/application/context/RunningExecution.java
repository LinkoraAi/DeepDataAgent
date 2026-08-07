package com.linkroa.deepdataagent.agent.application.context;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.infrastructure.collector.AnalysisEventBuffer;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.Disposable;

/**
 * 运行中分析的执行句柄
 * <p>聚合一次进行中分析所需的全部运行时资源，供停止与恢复操作使用。</p>
 *
 * @param dialogueId   对话轮次 ID（用于 finalFlush 写库）
 * @param agent        HarnessAgent 实例（用于 interrupt 中断）
 * @param subscription 事件流订阅句柄（dispose 触发 doOnCancel → finalFlush(CANCELLED)）
 * @param command      分析命令（保留备查，供恢复时参考）
 * @param eventBuffer  分析事件缓冲（累积已推送事件，供刷新恢复时回放）
 */
public record RunningExecution(
        Long dialogueId,
        HarnessAgent agent,
        Disposable subscription,
        DataAnalysisCommand command,
        AnalysisEventBuffer eventBuffer) {
}