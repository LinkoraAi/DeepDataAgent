package com.linkroa.deepdataagent.runtime.domain.model;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮执行控制面（进程内「阻塞等待 + 中断传递」载体）——由原执行槽类拍平而来。
 * <p>仅承载三类不可删状态；<b>不做任何执行互斥拒止</b>——跨进程 / 跨实例独占权威始终在 DB
 * （{@code Transition.BEGIN_TURN} CAS 只守 status=idle + turn 租约 owner CAS + 事件 {@code (session_id, seq)} 全序 +
 * 终态 CAS），进程内视图对极窄竞态窗口采 fail-open 显式取舍（见变更 design D5）。</p>
 * <ul>
 *   <li><b>完成信号</b>：{@link #awaitFinish()} 供虚拟线程阻塞等待事件流终局，{@link #finish()}
 *       由终态 / 挂起 / fail-closed 路径放行（替代仅作门控使用、从不 {@code completeExceptionally} 的
 *       {@code CompletableFuture}）；</li>
 *   <li><b>中断句柄登记</b>：{@link #activate(Runnable)} 注册 {@code BuiltAgent#interrupt} 定向句柄
 *       （装配完成后才登记，而取消可能先到）；</li>
 *   <li><b>activate / cancel 竞态补偿</b>：{@link #cancel()} 先到时置位 {@code cancelled}，
 *       {@link #activate(Runnable)} 命中已置位则注册即触发一次，防止执行中的模型流失去停止句柄。</li>
 * </ul>
 * <p><b>取消语义</b>：{@link #cancel()} 仅触发已登记中断句柄令 agent 事件流自然收流，终局由
 * {@link #finish()} 收敛；不引入「执行异常」通道，以免把「中断」误判为「执行异常」。
 * 句柄触发异常仅告警不向上传播。</p>
 */
@Slf4j
public final class TurnControl {

    /** 本轮完成门控（终态 / 挂起 / fail-closed 路径 {@link #countDown}，{@link #awaitFinish()} 放行）。 */
    private final CountDownLatch finished = new CountDownLatch(1);

    /** 取消标志（cancel 先到时供 {@link #activate(Runnable)} 补偿触发；置位后 {@link #cancel()} 幂等）。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 定向中断句柄（装配完成后登记；cancel 与 activate 共享同一引用，保证竞态补偿基于同一对象）。 */
    private final AtomicReference<Runnable> interrupter = new AtomicReference<>();

    /**
     * 注册定向中断句柄；若 cancel 已先到达（竞态），注册即立即触发一次，避免执行中的模型流失去停止句柄。
     *
     * @param handler 中断句柄（{@code () -> agent.interrupt(userId, sessionId)}——
     *                定向形式，槽位键须与执行下发运行时的会话身份同源）
     */
    public void activate(Runnable handler) {
        interrupter.set(handler);
        if (cancelled.get()) {
            // cancel 已先到：立即触发，处理 activate 与 cancel 的并发竞态
            runInterrupt(handler);
        }
    }

    /**
     * 取消本轮（幂等）：置位取消标志并触发已登记的中断句柄；句柄异常仅告警不向上传播。
     * <p>无中断句柄登记时为空操作（仅置位标志，供后续 activate 补偿触发）。</p>
     */
    public void cancel() {
        cancelled.set(true);
        Runnable handler = interrupter.get();
        if (handler != null) {
            runInterrupt(handler);
        }
    }

    /** 放行完成信号（终态 / 挂起 / fail-closed 路径调用，解除 {@link #awaitFinish()} 阻塞）。 */
    public void finish() {
        finished.countDown();
    }

    /**
     * 阻塞等待本轮终局（虚拟线程阻塞时自动 unmount 让出载体线程）。
     *
     * @throws InterruptedException 等待被外部中断（调用方须恢复中断标记并走中断终态）
     */
    public void awaitFinish() throws InterruptedException {
        finished.await();
    }

    /** 触发中断句柄（异常吞掉只告警，交由事件流终态路径收尾）。 */
    private static void runInterrupt(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException ex) {
            log.warn("中断 agent 执行异常（忽略，交由事件流终态路径收尾）: {}", ex.getMessage());
        }
    }
}
