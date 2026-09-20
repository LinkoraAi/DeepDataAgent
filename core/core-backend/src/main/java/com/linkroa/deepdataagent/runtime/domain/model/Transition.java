package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 会话状态迁移声明（值对象）：一次窄列 CAS 所需的全部事实——目标对外状态、目标内部相位、
 * 对外状态前置集合、内部相位前置集合。
 * <p>由 {@code SessionStateMachine} 邻接表派生出唯一合法迁移集合，
 * 仓储 {@code transition(sessionId, transition)} 据此生成窄列 guarded UPDATE
 * （双列模型：{@code status} + {@code turn_phase}），Mapper 内不再出现硬编码状态字面量。</p>
 * <p><b>四个可空维度</b>：</p>
 * <ul>
 *   <li>{@code to} 为 null ⇒ 本次迁移<b>不改写</b> {@code status} 列（相位专用迁移，如 HITL 等待 / 续跑 / 取消）；</li>
 *   <li>{@code toPhase} 为 null ⇒ 本次迁移<b>不改写</b> {@code turn_phase} 列（rescheduling 预留迁移）；</li>
 *   <li>{@code statusFrom} 为空集 ⇒ 不拼 {@code status} 条件（任意对外状态）；</li>
 *   <li>{@code phaseFrom} 为空集 ⇒ 不拼 {@code turn_phase} 条件（任意相位）。</li>
 * </ul>
 * <p>不变量：{@code to} 与 {@code toPhase} MUST NOT 同时为 null（否则迁移无任何写入语义）。</p>
 */
public record Transition(
        AgentSessionStatus to,
        TurnPhase toPhase,
        Set<AgentSessionStatus> statusFrom,
        Set<TurnPhase> phaseFrom
) {

    /**
     * 启动一轮（抢占执行权）：{@code status idle→running} + {@code phase →running}。
     * <p>非对称守卫：对外状态前置<b>只看</b> {@code status=idle}（不看 phase），
     * 故此处 statusFrom 为单元素、phaseFrom 为空集。</p>
     */
    public static final Transition BEGIN_TURN = new Transition(
            AgentSessionStatus.RUNNING, TurnPhase.RUNNING,
            Set.of(AgentSessionStatus.IDLE), Set.of());

    /** HITL 挂起：{@code phase running→awaiting_confirmation}，对外 status 保持 running（不改写）。 */
    public static final Transition PHASE_AWAIT = new Transition(
            null, TurnPhase.AWAITING_CONFIRMATION,
            Set.of(), Set.of(TurnPhase.RUNNING));

    /** HITL 确认续跑：{@code phase awaiting_confirmation→running}，对外 status 保持 running（不改写）。 */
    public static final Transition PHASE_RESUME = new Transition(
            null, TurnPhase.RUNNING,
            Set.of(), Set.of(TurnPhase.AWAITING_CONFIRMATION));

    /**
     * 收到取消：{@code phase running/awaiting_confirmation→cancelling}，对外 status 保持 running。
     * <p>无活跃执行（phase=idle）时前置未命中 ⇒ 影响 0 行 ⇒ 空操作（幂等，不产生任何取消残留）。</p>
     */
    public static final Transition PHASE_CANCEL = new Transition(
            null, TurnPhase.CANCELLING,
            Set.of(), Set.of(TurnPhase.RUNNING, TurnPhase.AWAITING_CONFIRMATION));

    /**
     * HITL 等待期作废回空闲：{@code status running→idle} + {@code phase awaiting_confirmation→idle}。
     * <p>相位前置<b>限定</b>为 {@code awaiting_confirmation}——等待确认是 durable 驻留态，
     * 中断 / 取消 / 删除该态会话无流可收，直接作废回 idle（不经 {@code cancelling}）。
     * 相位门禁不可放宽为「任意相位」：{@code running} 相位的活跃执行 MUST 走
     * {@link #PHASE_CANCEL} 取消链收流，否则将绕过取消信号直接静默落 idle。</p>
     */
    public static final Transition ABANDON_WAITING_CONFIRMATION = new Transition(
            AgentSessionStatus.IDLE, TurnPhase.IDLE,
            Set.of(AgentSessionStatus.RUNNING), Set.of(TurnPhase.AWAITING_CONFIRMATION));

    /**
     * 幂等收敛回空闲：{@code status running→idle} + {@code phase →idle}（正常完成 / 出错 / 中断）。
     * <p>相位前置为空集（任意相位）——这正是「cancelling 不得为静默死路」的兜底出口：
     * 即使终态判定时序未命中，以 cancelling 为前置的幂等回 idle 仍然成立。</p>
     */
    public static final Transition FINISH_TURN = new Transition(
            AgentSessionStatus.IDLE, TurnPhase.IDLE,
            Set.of(AgentSessionStatus.RUNNING), Set.of());

    /**
     * 不可恢复终态（显式终止 / 纯迭代上限）：{@code status running→terminated} + {@code phase →idle}。
     * <p>相位前置限定为 {@code running}：已进入 {@code cancelling} 的执行 MUST 以取消优先，
     * 不落 terminated——此时本迁移前置未命中（0 行），由调用方回退到 {@link #FINISH_TURN}。</p>
     */
    public static final Transition TERMINATE = new Transition(
            AgentSessionStatus.TERMINATED, TurnPhase.IDLE,
            Set.of(AgentSessionStatus.RUNNING), Set.of(TurnPhase.RUNNING));

    /** 重新调度：{@code status running→rescheduling}，相位不动（本期无生产者，词汇与迁移预留）。 */
    public static final Transition RESCHEDULE = new Transition(
            AgentSessionStatus.RESCHEDULING, null,
            Set.of(AgentSessionStatus.RUNNING), Set.of());

    /** 重新调度收敛回运行：{@code status rescheduling→running}，相位不动（与 {@link #RESCHEDULE} 对称）。 */
    public static final Transition RESUME_FROM_RESCHEDULE = new Transition(
            AgentSessionStatus.RUNNING, null,
            Set.of(AgentSessionStatus.RESCHEDULING), Set.of());

    /**
     * 启动恢复孤儿执行复位（<b>运维修复专用，非业务状态迁移</b>）：
     * {@code status running/rescheduling → idle} + {@code phase →idle}。
     * <p>相位前置限定为 {@code running / cancelling}——{@code awaiting_confirmation}
     * （durable HITL 等待）不在命中范围，跨重启 MUST NOT 被复位（见 execution spec
     * 「启动恢复仅复位孤儿执行相位」）。仅由 {@code StartupRecoveryLifecycle} 在
     * 确认无任何存活实例持有活跃 turn 租约后调用。</p>
     */
    public static final Transition ABANDON_ORPHAN_EXECUTION = new Transition(
            AgentSessionStatus.IDLE, TurnPhase.IDLE,
            Set.of(AgentSessionStatus.RUNNING, AgentSessionStatus.RESCHEDULING),
            Set.of(TurnPhase.RUNNING, TurnPhase.CANCELLING));

    public Transition {
        if (to == null && toPhase == null) {
            throw new IllegalArgumentException("迁移目标对外状态与内部相位不能同时为空");
        }
        if (statusFrom == null) {
            throw new IllegalArgumentException("迁移前置对外状态集合不能为空引用（任意状态请传空集）");
        }
        if (phaseFrom == null) {
            throw new IllegalArgumentException("迁移前置内部相位集合不能为空引用（任意相位请传空集）");
        }
        // 不可变 EnumSet：迭代顺序即枚举声明顺序，保证生成的 IN 参数顺序稳定可断言；
        // EnumSet.copyOf 拒绝空集合，故空集单独构造 noneOf
        statusFrom = freezeStatus(statusFrom);
        phaseFrom = freezePhase(phaseFrom);
    }

    /**
     * 是否带对外状态守卫（前置集合非空即需拼条件；空集表示任意状态）。
     *
     * @return true=生成的 CAS 需附加 status 前置条件
     */
    public boolean guardsStatus() {
        return !statusFrom.isEmpty();
    }

    /**
     * 是否带内部相位守卫（前置集合非空即需拼条件；空集表示任意相位）。
     *
     * @return true=生成的 CAS 需附加 turn_phase 前置条件
     */
    public boolean guardsPhase() {
        return !phaseFrom.isEmpty();
    }

    /**
     * 是否改写对外状态列。
     *
     * @return true=本次迁移写入 status 列
     */
    public boolean touchesStatus() {
        return to != null;
    }

    /**
     * 是否改写内部相位列。
     *
     * @return true=本次迁移写入 turn_phase 列
     */
    public boolean touchesPhase() {
        return toPhase != null;
    }

    private static Set<AgentSessionStatus> freezeStatus(Set<AgentSessionStatus> raw) {
        return raw.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(AgentSessionStatus.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(raw));
    }

    private static Set<TurnPhase> freezePhase(Set<TurnPhase> raw) {
        return raw.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(TurnPhase.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(raw));
    }
}