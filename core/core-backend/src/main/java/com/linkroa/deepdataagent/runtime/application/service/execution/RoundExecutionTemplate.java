package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.LeaseRenewalHandle;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.TurnControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 单轮执行模板（application 层组件，变更 decompose-turn-pipeline R10）。
 * <p>固定骨架，两条执行路径（新一轮 {@code streamEvents} / HITL 续跑 {@code resumeConfirmation}）
 * 共用，唯一差异为流供给函数 {@code fluxSupplier}（design D1）：</p>
 * <pre>{@code
 *  run（运行于虚拟线程，withTurn 已置入本轮控制面）
 *    ├─ agentFactory.build(assembler.apply(session))              // 装配（失败 → 运行时异常出口）
 *    ├─ turn.activate(() -> agent.interrupt(userId, sessionId))   // 定向中断句柄（与执行下发会话身份同源 D7）
 *    ├─ runStream(...)                                            // 下述订阅骨架
 *    ├─ catch InterruptedException → 中断出口（finalizeInterrupted）+ turn.finish
 *    ├─ catch RuntimeException     → 失败出口（finalizeAsFailure(ex, !built)）
 *    └─ finally: agent.close()                                    // 无论成败均释放 SDK 句柄
 *
 *  runStream
 *    定时续约任务启动（TTL/3 周期 owner-scoped；失败 → leaseLost fail-closed 中止）
 *    冷流 ─ publishOn(blockingScheduler) ─▶ doOnNext(signalHandler)
 *        ├─ 逐信号：seq 分配 / payload 装配 / 落库 / SSE 广播 / 终态判定（编排回调，模板不介入）
 *        └─ AGENT_END → 提前终态（EXCEED_MAX_ITERS / HITL 等待态后 defer 到 onComplete）
 *    subscribe(onNext=noop, onError=onStreamError, onComplete=onStreamComplete)
 *    订阅建立后复检两源取消谓词 → 命中补触发定向中断（竞态补触发 D8）
 *    runState.attachLeaseLostAbort(dispose 订阅 + finish 放行)      // fail-closed 静默中止句柄
 *    turn.awaitFinish()                                             // 阻塞等待终局（虚拟线程让出载体线程）
 *    finally：cancel 续约任务（正常 / 异常 / 中断 / HITL 挂起 / fail-closed 全出口收敛）
 * }</pre>
 * <p><b>出口顺序保持</b>：{@code runStream} 的 finally（停止续约）先于 {@code run} 的
 * InterruptedException catch（中断出口）执行，与拆分前 {@code runAgent → runStream} 嵌套 finally
 * 语义逐字一致——续约取消严格发生在终态化之前，杜绝毒续约。</p>
 * <p>模板无状态、不持有 Spring 组件（{@code RoundContext} 显式注入依赖面），由执行链服务静态持有单例复用。</p>
 * <p><b>可见性</b>：包私有——模板的唯一持有者是同包的 {@code TurnExecutionService}（静态单例），
 * 不出现在任何子包外的签名上（5.1 归位复核后由 public 收紧回包私有）。</p>
 */
final class RoundExecutionTemplate {

    private static final Logger log = LoggerFactory.getLogger(RoundExecutionTemplate.class);

    /**
     * 续约存储故障的容忍周期数（design D5①）：连续达到该数量的续约周期始终取不到成功确证，
     * 才按失权处理（每周期 {@code TTL/3}，两个周期约 6.7min，仍短于 10min TTL 的安全余量内）——
     * 既不让瞬断误杀在途轮次，也不让长期失联的实例越过 TTL 继续执行。
     */
    static final int LEASE_FAULT_TOLERANT_CYCLES = 2;

    /**
     * 执行一轮：<b>启动续约</b> → 装配 → 激活中断句柄 → 订阅事件流并阻塞 → 四出口收轮 → 停续约 + 释放 Agent。
     *
     * <p>续约先于装配启动：MCP 建连与工具枚举在 {@code agentFactory.build()} 内<b>同步</b>执行，
     * 多服务器场景（上限 20 台）可耗时数分钟；若等装配完成才起续约，这段窗口内无人续约，
     * 10min TTL 可能先到期而被他实例接管（D5③ 容量预案）。</p>
     *
     * @param ctx         本轮显式执行上下文（数据 + 端口 + 编排回调）
     * @param fluxSupplier 流供给（给定已装配 Agent 产出冷流：新一轮 streamEvents / 续跑轮 resumeConfirmation）
     */
    public void run(RoundContext ctx, Function<BuiltAgent, Flux<AgentStreamSignal>> fluxSupplier) {
        BuiltAgent agent = null;
        boolean built = false;
        TurnControl turn = ctx.turn();
        LeaseRenewalHandle leaseRenewal = startLeaseRenewal(ctx);
        try {
            AgentAssemblySpec spec = ctx.assembler().apply(ctx.session());
            // 开跑前登记本轮挂载保管库凭据明文至轮次运行态：工具结果落库 / SSE 广播与错误终态前
            // 经 SecretMasker.maskExactValues 精确掩码已知秘密回显（明文仅内存瞬态，不进日志 / 沙箱）
            ctx.runState().registerMountedVaultSecrets(spec.vaultCredentials());
            // MCP 工具权限策略登记（D15 事件投影）：agent.mcp_tool_use 载荷的 evaluated_permission
            // 与工厂下发框架的权限规则同源（同一份 mcp_toolset 策略、同一运行时实名约定）
            ctx.runState().registerMcpToolPolicies(spec.toolPolicies());
            agent = ctx.agentFactory().build(spec);
            built = true;
            // 定向中断：槽位键与流供给下发运行时的 (userId, sessionId) 同源（D7）
            BuiltAgent target = agent;
            Runnable interrupter = () -> target.interrupt(ctx.userId(), ctx.sessionId());
            turn.activate(interrupter);
            runStream(ctx, fluxSupplier.apply(agent), turn, interrupter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("{}等待终态被中断: sessionId={}", ctx.roundLabel(), ctx.sessionId());
            // 强制中断（executor 级外部中断）未走事件流收尾路径：显式中断终态化（幂等）
            // 并放行完成信号，避免会话停留在 running / turn 租约泄漏
            ctx.onAwaitInterrupted().run();
            turn.finish();
        } catch (RuntimeException ex) {
            log.error("{}执行异常: sessionId={}", ctx.roundLabel(), ctx.sessionId(), ex);
            ctx.onRunFailure().accept(ex, !built);
        } finally {
            // 轮次全部出口（正常 / 异常 / 中断 / HITL 挂起 / fail-closed / 装配失败）统一停止续约，杜绝虚续约
            leaseRenewal.cancel();
            if (agent != null) {
                try {
                    agent.close();
                } catch (RuntimeException ex) {
                    ctx.onAgentCloseFailure().accept(ex);
                }
            }
        }
    }

    /**
     * 事件流阶段订阅骨架（订阅 → 取消竞态补触发 → 挂中止句柄 → 阻塞）。
     * <p>续约句柄由 {@link #run(RoundContext, Function)} 持有并在其 {@code finally} 统一停止，
     * 故此处不再承担续约生命周期。</p>
     *
     * @param ctx         本轮执行上下文
     * @param signals     事件流冷流（{@code fluxSupplier} 产出）
     * @param turn        本轮执行控制面（终态 / 挂起路径 finish 放行、awaitFinish 阻塞）
     * @param interrupter 定向中断句柄（与 {@code activate} 注册的同源句柄，补触发复用）
     */
    private void runStream(RoundContext ctx, Flux<AgentStreamSignal> signals,
                           TurnControl turn, Runnable interrupter) throws InterruptedException {
        Disposable subscription = signals.publishOn(ctx.blockingScheduler())
                .doOnNext(ctx.signalHandler())
                .subscribe(
                        ignored -> {},
                        ctx.onStreamError(),
                        ctx.onStreamComplete());
        // 取消竞态补触发（D8）：订阅建立前取消已先到（activate 触发被框架入口 reset 抹掉）时，
        // 在入口复位之后的窗口补打一次定向中断；异常仅记日志，不干扰流终态收敛
        if (ctx.cancelRequested().getAsBoolean()) {
            fireInterrupterQuietly(ctx, interrupter, "订阅建立后补触发");
        }
        // 注册 fail-closed 中止句柄：续约失败 → 取消流订阅切断在途执行 + 放行完成信号收轮；
        // 标记已先置位（续约失败早于订阅建立）时句柄立即触发，中止信号不丢失。
        // finish 置 finally：dispose 异常被 runLeaseLostAbort 吞掉时收轮信号仍必达，
        // 避免 awaitFinish() 永久悬挂（控制面 / agent 句柄泄漏）
        ctx.runState().attachLeaseLostAbort(() -> {
            try {
                subscription.dispose();
            } finally {
                turn.finish();
            }
        });
        turn.awaitFinish();
    }

    /**
     * 启动 turn 租约定时续约任务（周期 {@code TTL/3}，留 2/3 TTL 重试余量）。
     * <p><b>失败二分类（move-coordination-leases-to-redis D5①）</b>：存储以「返回 false」表达
     * 确证失权（Lua 返回 0：租约过期被回收或已被他实例接管）——立即置
     * {@code leaseLost} 触发 fail-closed 静默中止；以「抛运行时异常」表达存储连接 / 命令故障——
     * 当周期重试 1 次，仍失败则等下一周期，<b>连续 {@value #LEASE_FAULT_TOLERANT_CYCLES} 个周期</b>
     * （约 6.7min，TTL 余量内）始终取不到确证成功才 fail-closed，禁止把抖动当失权。</p>
     * <p>任务体 MUST NOT 抛出——平台池承载下 {@code ScheduledThreadPoolExecutor} 会静默取消抛异常的
     * 周期任务（虚拟线程承载下循环保留，但两种承载均以「不外抛」为契约，见
     * {@code LeaseRenewalScheduler}）。</p>
     *
     * @return 续约停止句柄（轮次出口统一 cancel）
     */
    private LeaseRenewalHandle startLeaseRenewal(RoundContext ctx) {
        String sessionId = ctx.sessionId();
        var runState = ctx.runState();
        Duration interval = Duration.ofMillis(CoordinationLeaseService.TURN_LEASE_TTL.toMillis() / 3);
        // 连续「确证故障」周期计数（续约成功即归零；瞬断容忍据此判定是否达到 fail-closed 门槛）
        AtomicInteger faultCycles = new AtomicInteger();
        return ctx.leaseRenewalScheduler().schedule(() -> {
            try {
                if (runState.leaseLost()) {
                    return;
                }
                RenewOutcome outcome = renewOnce(ctx.leaseService(), sessionId);
                if (outcome == RenewOutcome.RENEWED) {
                    faultCycles.set(0);
                    return;
                }
                if (outcome == RenewOutcome.FAULT) {
                    // 存储故障：当周期重试 1 次（重试确证失权则按确证处理，不再容忍）
                    outcome = renewOnce(ctx.leaseService(), sessionId);
                    if (outcome == RenewOutcome.RENEWED) {
                        faultCycles.set(0);
                        return;
                    }
                }
                if (outcome == RenewOutcome.FAULT) {
                    if (faultCycles.incrementAndGet() < LEASE_FAULT_TOLERANT_CYCLES) {
                        log.warn("turn 租约续约连续异常（存储故障，等待下一周期重试）: sessionId={}, 连续周期={}/{}",
                                sessionId, faultCycles.get(), LEASE_FAULT_TOLERANT_CYCLES);
                        return;
                    }
                    log.warn("turn 租约续约连续 {} 周期存储故障，fail-closed 中止在途执行"
                            + "（不写终态 / 不迁移状态 / 不广播）: sessionId={}", faultCycles.get(), sessionId);
                } else {
                    log.warn("turn 租约续约失败（执行权已丧失：过期被回收或已被他实例接管），"
                            + "fail-closed 中止在途执行（不写终态 / 不迁移状态 / 不广播）: sessionId={}", sessionId);
                }
                runState.markLeaseLost();
            } catch (RuntimeException ex) {
                // 兜底收口：上方分类逻辑已消化存储异常，此处仅防未知异常逃逸取消周期调度
                log.error("turn 租约续约任务未预期异常（本次跳过，等待下一周期）: sessionId={}", sessionId, ex);
            }
        }, interval);
    }

    /**
     * 执行一次续约并归类结果（二分类判据的唯一落点：{@code false}=确证失权、异常=存储故障）。
     */
    private static RenewOutcome renewOnce(CoordinationLeaseService leaseService, String sessionId) {
        try {
            return leaseService.renewTurnLease(sessionId) ? RenewOutcome.RENEWED : RenewOutcome.LOST;
        } catch (RuntimeException ex) {
            log.warn("turn 租约续约异常（存储连接 / 命令故障，非确证失权）: sessionId={}", sessionId, ex);
            return RenewOutcome.FAULT;
        }
    }

    /**
     * 单轮续约结果三态（design D5①二分类的内部表达）。
     */
    private enum RenewOutcome {
        /** 续约成功（租约仍由本实例持有） */
        RENEWED,
        /** 确证失权（存储返回失败：不存在 / 已过期 / 已易主）→ 立即 fail-closed */
        LOST,
        /** 存储故障（连接 / 命令异常）→ 容忍重试，连续超阈值才 fail-closed */
        FAULT
    }

    /**
     * 静默触发定向中断句柄（补触发路径专用）：框架中断本身幂等，句柄异常仅记日志，
     * MUST NOT 向上传播打断事件流编排（终态由取消谓词两源求值照常收敛）。
     */
    private void fireInterrupterQuietly(RoundContext ctx, Runnable interrupter, String occasion) {
        try {
            interrupter.run();
            log.info("{}定向中断成功: sessionId={}", occasion, ctx.sessionId());
        } catch (RuntimeException ex) {
            log.warn("{}定向中断异常（忽略，交由终态路径收敛）: sessionId={}", occasion, ctx.sessionId(), ex);
        }
    }
}
