package com.linkroa.deepdataagent.runtime.domain.model;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 会话执行运行时（执行层核心领域类）—— 虚拟线程阻塞等待模型。
 * <p>与操作系统「逻辑线程组 M:N 调度」对齐：同一会话同时仅允许一个活跃执行，
 * 提交的任务在当前虚拟线程（由应用层 {@code sendMessageAsync} 经 {@code agentVirtualExecutor}
 * 创建）内同步阻塞等待事件流完成，阻塞时虚拟线程自动 unmount 让出载体线程。</p>
 * <p><b>串行守卫</b>：{@link #submit} 以 {@link AtomicReference#compareAndSet} 原子占据执行槽，
 * 已有活跃执行时抛 {@link IllegalStateException}；任务结束（含异常）经 {@code finally} 清除槽位。</p>
 * <p><b>取消语义</b>：{@link #cancel} 触发已注册的中断句柄（{@link com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent#interrupt}），
 * agent 事件流自然结束并经终态路径 {@code complete} 阻塞等待；不引入 {@code completeExceptionally}，
 * 以免把「中断」误判为「执行异常」。</p>
 */
@Slf4j
public final class AgentSessionExecution {

    /**
     * 执行现场上下文 —— 提供给 task 使用的控制句柄。
     * <p>task 通过 {@link #completion()} 阻塞等待异步流完成，通过 {@link #activate(Runnable)}
     * 注册可取消资源（中断句柄）；cancel 时统一触发。</p>
     */
    public interface ExecutionContext {

        /**
         * 本轮执行完成信号（task 阻塞等待，终态路径 {@code complete}）。
         *
         * @return 异步流完成 Future
         */
        CompletableFuture<Void> completion();

        /**
         * 注册可取消资源（中断句柄），cancel 时触发。
         * <p>register 后若 cancel 已先到达（竞态），立即触发一次避免泄漏。</p>
         *
         * @param interrupter 中断句柄（如 {@code agent::interrupt}）
         */
        void activate(Runnable interrupter);
    }

    /** 当前活跃执行槽（null=无在跑执行）。 */
    private final AtomicReference<ExecutionSlot> activeSlot = new AtomicReference<>();

    /**
     * 提交一轮执行任务（同步阻塞：调用方当前线程即虚拟线程）。
     * <p>同一会话已有活跃执行时抛 {@link IllegalStateException}；任务执行完毕（含异常）
     * 后在 {@code finally} 清除执行槽。</p>
     *
     * @param task 执行任务（内部应在 {@link ExecutionContext#completion()} 上阻塞等待）
     */
    public void submit(Consumer<ExecutionContext> task) {
        ExecutionSlot slot = new ExecutionSlot();
        if (!activeSlot.compareAndSet(null, slot)) {
            throw new IllegalStateException("会话已有活跃执行，拒绝重复提交");
        }
        try {
            task.accept(slot);
        } finally {
            activeSlot.compareAndSet(slot, null);
        }
    }

    /**
     * 取消当前活跃执行（幂等）：触发注册的中断句柄并清除执行槽。
     * <p>无活跃执行时为空操作；中断句柄触发后由 agent 事件流自然结束收尾。</p>
     */
    public void cancel() {
        ExecutionSlot slot = activeSlot.getAndSet(null);
        if (slot == null) {
            return;
        }
        slot.interrupt();
    }

    /**
     * 当前是否存在活跃执行。
     *
     * @return true=有在跑执行
     */
    public boolean isRunning() {
        return activeSlot.get() != null;
    }

    /**
     * 单次执行槽：绑定 completion 与可取消中断句柄。
     */
    private static final class ExecutionSlot implements ExecutionContext {

        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicReference<Runnable> interrupter = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public CompletableFuture<Void> completion() {
            return completion;
        }

        @Override
        public void activate(Runnable handler) {
            interrupter.set(handler);
            if (cancelled.get()) {
                // cancel 已先到：立即触发，处理 activate 与 cancel 的并发竞态
                runInterrupt(handler);
            }
        }

        /** 触发中断句柄（cancel 路径，异常不向上传播）。 */
        void interrupt() {
            cancelled.set(true);
            Runnable handler = interrupter.get();
            if (handler != null) {
                runInterrupt(handler);
            }
        }

        private static void runInterrupt(Runnable handler) {
            try {
                handler.run();
            } catch (RuntimeException ex) {
                log.warn("中断 agent 执行异常（忽略，交由事件流终态路径收尾）: {}", ex.getMessage());
            }
        }
    }
}