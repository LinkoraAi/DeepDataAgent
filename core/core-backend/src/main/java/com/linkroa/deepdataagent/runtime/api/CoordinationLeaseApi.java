package com.linkroa.deepdataagent.runtime.api;

/**
 * 协调层租约跨 BC 服务契约（调度触发防重能力面，供消费方按「依赖契约、提供方实现」使用）。
 *
 * <p>当前由 {@code runtime} BC 的 {@code DefaultCoordinationLeaseApi} 进程内实现；未来接入
 * Feign 时仅需在本接口追加 {@code @FeignClient} 注解并移除进程内实现，消费方无需改动。</p>
 *
 * <p>语义（调度器触发窗口防重）：触发流程开始前获取租约，成功才可进入执行编排；
 * 触发流程结束（含异常）必须释放。异常退出未释放时，由租约 TTL 过期自动失效兜底。</p>
 */
public interface CoordinationLeaseApi {

    /**
     * 尝试获取调度器触发防重租约（窗口内仅一个触发可进入执行编排）。
     *
     * @param schedulerId 调度器业务 ID
     * @return true=获取成功；false=窗口内已有触发在途（重复触发应被拒绝）
     */
    boolean tryAcquireFireLease(String schedulerId);

    /**
     * 释放调度器触发防重租约（幂等）。
     *
     * @param schedulerId 调度器业务 ID
     */
    void releaseFireLease(String schedulerId);
}
