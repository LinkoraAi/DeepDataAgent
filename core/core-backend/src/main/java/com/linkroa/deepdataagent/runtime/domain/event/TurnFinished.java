package com.linkroa.deepdataagent.runtime.domain.event;

import org.apache.commons.lang3.StringUtils;

/**
 * 轮次终局领域事件（fix-runtime-layering 5.1，design D4）。
 * <p>在终态事务内发布、由 AFTER_COMMIT 监听器消费——调度打标会话（{@code triggerType} 非空）
 * 的终局经监听器回写 agent BC 运行记录；回滚则事件不发，杜绝「回滚后仍回写」窗口。
 * 本事件为进程内领域事件，不跨网络、不入任何持久化与响应面。</p>
 *
 * @param sessionId   终局会话业务 ID（必填，前缀 sess_）
 * @param triggerType 会话触发打标（可空：非调度触发为 {@code null}，监听器据此忽略）
 * @param outcome     运行终局契约值（必填：{@code succeeded} / {@code failed} /
 *                    {@code terminated}，取 agent BC {@code DeploymentRunLifecycleApi} 常量）
 */
public record TurnFinished(String sessionId, String triggerType, String outcome) {

    public TurnFinished {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("终态事件会话 ID 不能为空");
        }
        if (StringUtils.isBlank(outcome)) {
            throw new IllegalArgumentException("终态事件 outcome 不能为空");
        }
    }
}
