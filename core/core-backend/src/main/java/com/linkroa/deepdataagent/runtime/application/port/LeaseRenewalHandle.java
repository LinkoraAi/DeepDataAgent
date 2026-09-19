package com.linkroa.deepdataagent.runtime.application.port;

/**
 * 续约任务停止句柄（{@link LeaseRenewalScheduler#schedule} 返回）。
 *
 * <p>幂等语义：重复 {@link #cancel()} MUST NOT 抛异常（轮次出口与优雅停机可能并发收口）。
 * 以函数式接口表达，令两种承载（平台池 {@code ScheduledFuture#cancel} / 虚拟线程置停标志 +
 * {@code interrupt}）各自以内联实现收敛。</p>
 */
@FunctionalInterface
public interface LeaseRenewalHandle {

    /** 停止续约任务（不再触发后续周期；已在执行中的单次任务不强行打断）。 */
    void cancel();
}
