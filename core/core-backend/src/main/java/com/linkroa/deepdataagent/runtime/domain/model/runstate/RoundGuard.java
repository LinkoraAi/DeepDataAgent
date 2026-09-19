package com.linkroa.deepdataagent.runtime.domain.model.runstate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轮次守卫组件（吸收原 {@code AgentRunState} 的五位 AtomicBoolean + leaseLost 中止句柄）。
 * <p>承载本轮的四类中止 / 终态资格位：外部中断、租约丢失（fail-closed）、已终态化、HITL 挂起；
 * 外加 {@code exceedMaxIters}（超最大迭代轮次，AGENT_END 提前终态判定用）与
 * {@code leaseLostAbort} 中止句柄（续约失败时取消事件流订阅 + 放行完成信号）。</p>
 * <p>全部字段以原子类型实现，跨线程可见性由原子语义保证（续约线程置位 → 流线程读取）。</p>
 */
public final class RoundGuard {

    /** 外部中断标记（取消 / 定向中断收敛时置位） */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    /** 租约丢失标记（续约失败即 fail-closed 中止在途执行，不写终态 / 不迁移状态 / 不广播） */
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);
    /** 终态化资格（{@link #tryAcquireFinalization()} 领取成功即本轮生成结束，防重复终态） */
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    /** HITL 挂起标记（等待人工确认：本轮不算终态，终态化资格不领取） */
    private final AtomicBoolean confirmationPending = new AtomicBoolean(false);
    /** 超最大迭代轮次标记（提前终态判定） */
    private final AtomicBoolean exceedMaxIters = new AtomicBoolean(false);
    /** fail-closed 中止句柄（续约失败 → 取消流订阅 + 放行完成信号；置位早于注册时立即触发） */
    private final AtomicReference<Runnable> leaseLostAbort = new AtomicReference<>();

    /** 置位外部中断标记（取消 / 定向中断收敛路径）；置位后终态路径表达为 {@code session.interrupted}。 */
    public void markInterrupted() {
        interrupted.set(true);
    }

    /** 本轮是否已被外部中断。 */
    public boolean interrupted() {
        return interrupted.get();
    }

    /**
     * 标记租约丢失并按需触发中止句柄。
     *
     * @return true=本次置位（首次）；false=已置位（幂等短路）
     */
    public boolean markLeaseLost() {
        if (!leaseLost.compareAndSet(false, true)) {
            return false;
        }
        runLeaseLostAbort(leaseLostAbort.get());
        return true;
    }

    /** 本轮是否已确证租约丢失（fail-closed 事实位，续约线程置位、流线程读取）。 */
    public boolean leaseLost() {
        return leaseLost.get();
    }

    /**
     * 注册 fail-closed 中止句柄：注册时标记已置位（续约失败早于句柄建立）则立即触发，中止信号不丢失。
     */
    public void attachLeaseLostAbort(Runnable abort) {
        leaseLostAbort.set(abort);
        if (leaseLost.get()) {
            runLeaseLostAbort(abort);
        }
    }

    /** 置位 HITL 挂起标记（等待人工确认期间不构成终态，不领取终态化资格）。 */
    public void markConfirmationPending() {
        confirmationPending.set(true);
    }

    /** 本轮是否处于 HITL 挂起（等待人工确认）。 */
    public boolean confirmationPending() {
        return confirmationPending.get();
    }

    /** 置位超最大迭代轮次标记（供 AGENT_END 提前终态判定）。 */
    public void markExceedMaxIters() {
        exceedMaxIters.set(true);
    }

    /** 本轮是否已达最大迭代轮次。 */
    public boolean exceededMaxIters() {
        return exceedMaxIters.get();
    }

    /**
     * 原子领取终态化资格。
     *
     * @return true=取得资格（本轮生成结束）；false=已终态化（幂等短路）
     */
    public boolean tryAcquireFinalization() {
        return finalized.compareAndSet(false, true);
    }

    /** 本轮是否已领取终态化资格（生成已结束）。 */
    public boolean finalized() {
        return finalized.get();
    }

    /** 触发中止句柄（异常吞掉：收轮信号由句柄自身的 finally 分支必达，MUST NOT 因 dispose 异常丢失）。 */
    private static void runLeaseLostAbort(Runnable abort) {
        if (abort == null) {
            return;
        }
        try {
            abort.run();
        } catch (RuntimeException ignored) {
            // 中止句柄内部已带 finally 收口；此处吞异常防续约线程被反噬
        }
    }
}
