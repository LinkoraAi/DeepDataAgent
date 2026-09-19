package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.event.ArtifactDeliveredSignal;

/**
 * 产物交付信号上抛出站端口（进程内依赖倒置，5.4）。
 * <p>沙箱交付目标（{@code infrastructure.client.SessionArtifactDeliveryTarget}）登记成功后经本端口
 * 上抛 {@link ArtifactDeliveredSignal}，由应用层装配并落库 / 推送 {@code agent.artifact_delivered}
 * 事件——基础设施层不做任何事件装配 / 持久化 / 广播决策（与 {@code AgentRunExecutor} 同一分层口径）。</p>
 * <p>与 SDK 事件流无关：交付发生在工具执行内部、不经过 {@code AgentStreamSignal} 流，
 * 故不走 {@code execution.SignalHandlerRegistry} 分发，而由本专用端口承接。</p>
 */
public interface ArtifactDeliverySignalPort {

    /**
     * 上抛一条「已交付」信号（登记成功后调用；失败交付 MUST NOT 调用）。
     *
     * @param signal 交付事实信号（file_id / 原始文件名 / 字节数 / 内容类型）
     */
    void publish(ArtifactDeliveredSignal signal);
}