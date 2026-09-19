package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.DeploymentOutcome;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalEventKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalNoOpReason;

import java.util.List;

/**
 * 终态决策输出（纯值，无 IO）：要么给出<b>有序的迁移尝试链</b>，要么给出 no-op 判定。
 * <p>尝试链是「首选 + 回退」的声明式表达：应用层按序执行各尝试的 CAS，<b>首个命中（受影响行数
 * &gt; 0）即为实际迁入终态</b>，其事件集与调度终局语义随之采纳；全部未命中即真 no-op
 * （会话被并发归档 / 终止，显式指令胜出）。CAS 始终是终态资格的仲裁者，决策表不做任何写操作。</p>
 * <p>之所以是「链」而非单一目标态：迭代上限（{@code MAX_ITERATIONS}）与取消存在竞态——
 * 首选 {@code TO_TERMINATED}（前置仅 processing）未命中时必须同链回退 {@code TO_IDLE}
 * （前置含 canceling），否则 {@code canceling} 会成为静默死路（2026-09-07 终态事故根因）。</p>
 *
 * @param attempts   有序尝试链（no-op 时为空）
 * @param noOpReason no-op 原因（可执行决策时为 null）
 */
public record Decision(List<Attempt> attempts, TerminalNoOpReason noOpReason) {

    public Decision {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (noOpReason != null) {
            if (!attempts.isEmpty()) {
                throw new IllegalArgumentException("no-op 决策不得携带迁移尝试链");
            }
        } else if (attempts.isEmpty()) {
            throw new IllegalArgumentException("可执行决策必须至少含一次迁移尝试");
        }
    }

    /**
     * no-op 决策（不迁移状态、不落库、不广播、不发终局事件）。
     *
     * @param reason no-op 原因（租约丢失 / HITL 挂起驻留 / 显式终态胜出）
     */
    public static Decision noOp(TerminalNoOpReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("no-op 决策必须声明原因");
        }
        return new Decision(List.of(), reason);
    }

    /**
     * 可执行决策。
     *
     * @param attempts 有序尝试链（首个为首选分支，其余为竞态回退分支）
     */
    public static Decision of(List<Attempt> attempts) {
        return new Decision(attempts, null);
    }

    /** 是否 no-op 决策。 */
    public boolean noOp() {
        return noOpReason != null;
    }

    /**
     * 首选目标状态（供日志与断言使用；实际迁入态由尝试链首个命中的分支决定）。
     *
     * @return 首选迁移的目标状态
     * @throws IllegalStateException no-op 决策无目标状态
     */
    public AgentSessionStatus primaryTarget() {
        if (noOp()) {
            throw new IllegalStateException("no-op 决策（" + noOpReason + "）不存在目标状态");
        }
        return attempts.get(0).transition().to();
    }

    /**
     * 单次迁移尝试：一条状态机迁移声明 + 命中后应落的终态事件集 + 调度运行终局语义。
     *
     * @param transition 迁移声明（前置集合由 {@code SessionStateMachine} 单一事实源生成 CAS 条件）
     * @param events     命中后同事务落库的终态事件集（顺序即落库顺序，末条必为状态事件）
     * @param outcome    调度运行终局语义（应用层映射为 agent BC 发布语言值）
     */
    public record Attempt(Transition transition, List<TerminalEventSpec> events, DeploymentOutcome outcome) {

        public Attempt {
            if (transition == null) {
                throw new IllegalArgumentException("迁移尝试必须声明状态迁移");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("迁移尝试必须声明调度运行终局语义");
            }
            events = events == null ? List.of() : List.copyOf(events);
            if (events.isEmpty()) {
                throw new IllegalArgumentException("迁移尝试必须声明终态事件集");
            }
            // 不变量：事件集与实际迁入状态不可能错位——状态事件一律指向本迁移目标，且以状态事件收尾
            TerminalEventSpec last = events.get(events.size() - 1);
            if (last.kind() != TerminalEventKind.SESSION_STATUS || last.status() != transition.to()) {
                throw new IllegalArgumentException(
                        "终态事件集必须以指向迁移目标状态的会话状态事件收尾: " + transition.to());
            }
            events.stream()
                    .filter(event -> event.kind() == TerminalEventKind.SESSION_STATUS)
                    .filter(event -> event.status() != transition.to())
                    .findFirst()
                    .ifPresent(event -> {
                        throw new IllegalArgumentException("终态事件集内的状态事件与迁移目标状态不一致");
                    });
        }
    }
}
