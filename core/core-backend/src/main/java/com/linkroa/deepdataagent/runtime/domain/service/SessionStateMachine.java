package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 会话状态机：四态对外状态 + 四态内部相位的合法迁移<b>唯一事实源</b>
 * （邻接表 + 合法性判定，纯 Java、无框架）。
 * <p>邻接表直接由 {@link Transition} 的命名常量派生——迁移声明只写一次（在 {@code Transition}），
 * 本类只负责「按目标态聚合入边」与「合法性判定」两件事，不重复声明任何状态字面量。</p>
 * <p><b>双列模型</b>：对外 {@code status}（{@code idle/running/rescheduling/terminated}）与
 * 内部 {@code turn_phase}（{@code idle/running/awaiting_confirmation/cancelling}）独立维护。
 * 归档已移出状态迁移集合——{@code archived_at} 是独立正交维度，可与任意状态组合。</p>
 * <p><b>「任意」语义</b>：某迁移的 {@code statusFrom} / {@code phaseFrom} 为空集时，
 * 表示该维度不拼条件（任意值皆可命中），见 {@link Transition} 的说明。</p>
 */
public final class SessionStateMachine {

    /**
     * 全部合法迁移声明（引用 {@link Transition} 命名常量，状态字面量零重复）。
     * <p>归档迁移（原 {@code ARCHIVE}）不在此列——归档只写 {@code archived_at}，
     * 不经状态机迁移。</p>
     */
    private static final List<Transition> TRANSITIONS = List.of(
            Transition.BEGIN_TURN,
            Transition.PHASE_AWAIT,
            Transition.PHASE_RESUME,
            Transition.PHASE_CANCEL,
            Transition.ABANDON_WAITING_CONFIRMATION,
            Transition.FINISH_TURN,
            Transition.TERMINATE,
            Transition.RESCHEDULE,
            Transition.RESUME_FROM_RESCHEDULE,
            Transition.ABANDON_ORPHAN_EXECUTION
    );

    /** 目标对外状态 → 指向该状态的迁移声明（一个目标态可能有多条入边，如 running / idle）。 */
    private static final Map<AgentSessionStatus, List<Transition>> INCOMING_BY_STATUS = buildIncomingByStatus();

    private SessionStateMachine() {
    }

    /**
     * 全部合法迁移声明（只读）。
     *
     * @return 迁移常量列表
     */
    public static List<Transition> transitions() {
        return TRANSITIONS;
    }

    /**
     * 迁移在当前双列取值下是否合法（对外状态 + 内部相位两维守卫同时满足）。
     *
     * @param fromStatus 当前对外状态（null 视为不匹配任何带状态守卫的迁移）
     * @param fromPhase  当前内部相位（null 视为不匹配任何带相位守卫的迁移）
     * @param transition 迁移声明（非空）
     * @return true=两维守卫均满足
     */
    public static boolean isLegal(AgentSessionStatus fromStatus, TurnPhase fromPhase, Transition transition) {
        if (transition == null) {
            throw new IllegalArgumentException("迁移声明不能为空");
        }
        return matchesStatus(fromStatus, transition) && matchesPhase(fromPhase, transition);
    }

    /**
     * 目标对外状态的入边迁移声明（只统计显式改写了 status 列的迁移）。
     *
     * @param target 目标对外状态（非空）
     * @return 迁移列表（不可变，无入边时为空列表）
     */
    public static List<Transition> incomingTransitions(AgentSessionStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("目标状态不能为空");
        }
        return INCOMING_BY_STATUS.getOrDefault(target, List.of());
    }

    /**
     * 对外状态 {@code from} 可达的目标状态集合（含相位守卫迁移），用于排障与测试。
     *
     * @param from      当前对外状态（非空）
     * @param fromPhase 当前内部相位（非空）
     * @return 可达目标状态集合（不可变）
     */
    public static Collection<AgentSessionStatus> reachableFrom(AgentSessionStatus from, TurnPhase fromPhase) {
        List<AgentSessionStatus> targets = new ArrayList<>();
        for (Transition transition : TRANSITIONS) {
            if (transition.touchesStatus() && isLegal(from, fromPhase, transition)
                    && !targets.contains(transition.to())) {
                targets.add(transition.to());
            }
        }
        return Collections.unmodifiableList(targets);
    }

    /**
     * 便于测试与排障：以集合视图暴露全部迁移（只读）。
     *
     * @return 迁移声明集合（不可变）
     */
    public static Collection<Transition> allTransitions() {
        return Collections.unmodifiableList(TRANSITIONS);
    }

    private static boolean matchesStatus(AgentSessionStatus fromStatus, Transition transition) {
        if (!transition.guardsStatus()) {
            return true;
        }
        return fromStatus != null && transition.statusFrom().contains(fromStatus);
    }

    private static boolean matchesPhase(TurnPhase fromPhase, Transition transition) {
        if (!transition.guardsPhase()) {
            return true;
        }
        return fromPhase != null && transition.phaseFrom().contains(fromPhase);
    }

    private static Map<AgentSessionStatus, List<Transition>> buildIncomingByStatus() {
        Map<AgentSessionStatus, List<Transition>> table = new EnumMap<>(AgentSessionStatus.class);
        for (Transition transition : TRANSITIONS) {
            if (transition.touchesStatus()) {
                table.computeIfAbsent(transition.to(), k -> new ArrayList<>()).add(transition);
            }
        }
        Map<AgentSessionStatus, List<Transition>> frozen = new EnumMap<>(AgentSessionStatus.class);
        table.forEach((target, list) -> frozen.put(target, Collections.unmodifiableList(list)));
        return Collections.unmodifiableMap(frozen);
    }
}