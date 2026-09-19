package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TurnRunState;

/**
 * turn 执行现场：启动事务产出的聚合参数（会话级聚合 / 流状态 / 线程归属），
 * 贯穿事件流编排与终态路径。
 * <p>decompose-command-facade 2.3：自原命令门面私有嵌套 record
 * 提为顶层 package-private 载体，门面壳与 {@code TurnEventWriter} 共用（原样平移，零逻辑变化；
 * 三个访问方法均只委托自身 {@code sessionContext} 字段，不依赖任何服务实例）。</p>
 * <p><b>可见性</b>：public（永久）——5.1 归位复核结论：本子包外的实际消费面为
 * {@code service.event.InboundEventService}（轮外落库点取 seq / 线程归属）与
 * {@code service.hitl.HumanConfirmationService}（续跑现场构造），且同包的
 * {@code TurnEventWriter} / {@code TurnFinalizer} 公开方法签名均以本类型为入参，
 * 故不可收紧为包私有。</p>
 *
 * @param sessionContext  会话级聚合（逻辑线程组，跨 turn 常驻）
 * @param runState        当前 turn 事件流状态（beginRound 产出）
 * @param sessionThreadId 主线程归属 ID（启动 / 领取事务内解析一次，随现场传递；null = 归属未知降级）
 */
public record ExecutionContext(AgentSessionContext sessionContext, TurnRunState runState,
                               String sessionThreadId) {

    /** 会话镜像（身份信息 / 终态判定）。 */
    public AgentSession session() {
        return sessionContext.session();
    }

    /** 会话 ID。 */
    public String sessionId() {
        return sessionContext.sessionId();
    }

    /** 会话级事件序列号（跨 turn 计数器，本会话单调递增）。 */
    public long nextSequence() {
        return sessionContext.nextSequence();
    }
}
