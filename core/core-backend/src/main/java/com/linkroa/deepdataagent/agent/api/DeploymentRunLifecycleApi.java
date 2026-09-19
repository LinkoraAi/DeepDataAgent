package com.linkroa.deepdataagent.agent.api;

/**
 * 调度运行终态回写服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>runtime BC 的调度打标会话（经 {@code SchedulerSessionApi.launch} 触发、{@code trigger_type} 非空）
 * 在轮次终局出口按本接口回写 {@code deployment_run} 运行记录的终局结果并刷新调度器 {@code last_status}
 * 快照，实现「运行记录反映触发 episode 的真实终局」。当前由 {@code DefaultDeploymentRunLifecycleApi}
 * 进程内实现，未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解并移除进程内实现，
 * 消费方（runtime BC）无需改动。</p>
 * <p>参数为基本类型字符串（轻量、可 JSON 序列化），符合 {@code api} 面口径。</p>
 */
public interface DeploymentRunLifecycleApi {

    /** 终局契约值：轮次正常收敛（fix-runtime-layering 5.3，outcome 发布语言常量化）。 */
    String OUTCOME_SUCCEEDED = "succeeded";
    /** 终局契约值：轮次执行失败 / 迭代上限终局。 */
    String OUTCOME_FAILED = "failed";
    /** 终局契约值：轮次中断 / 挂起作废 / 取消回退终局。 */
    String OUTCOME_TERMINATED = "terminated";

    /**
     * 按触发会话回写运行终态（幂等）。
     * <p>episode 口径：回写「自触发起首个终局出口轮」的终局——非调度会话（无 running 运行行）、
     * 挂起驻留后的重复收口、迟到回写均为幂等空操作（命中 0 行静默返回）。</p>
     *
     * @param sessionId 触发会话ID（deployment_run.session_id，与触发轮 1:1）
     * @param outcome   终局契约值：{@code succeeded} / {@code failed} / {@code terminated}
     * @throws IllegalArgumentException outcome 非法或为 {@code running}（进程内契约无 HTTP 语义，
     *                                  消费者侧 try/catch 仅告警）
     */
    void completeByTriggerSession(String sessionId, String outcome);
}
