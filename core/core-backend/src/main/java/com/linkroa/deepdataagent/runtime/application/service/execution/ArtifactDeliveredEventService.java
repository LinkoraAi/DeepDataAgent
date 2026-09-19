package com.linkroa.deepdataagent.runtime.application.service.execution;

import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliverySignalPort;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.runtime.domain.event.ArtifactDeliveredSignal;
import com.linkroa.deepdataagent.runtime.domain.event.ChatEventFactory;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 产物交付信号应用编排（{@link ArtifactDeliverySignalPort} 实现，5.4）：把交付目标上抛的
 * {@link ArtifactDeliveredSignal} 装配为 {@code agent.artifact_delivered} 事件，并复用轮内写入底座
 * （{@link TurnEventWriter}）完成「推送 + 异步入队落库」——seq 走会话级计数器，与当轮其余事件同一全序，
 * 随终态严格排空协议一并落库（交付事件先于收场二事件）。</p>
 * <p><b>执行现场来源</b>：交付发生在当轮工具执行内部，本服务按 sessionId 从会话运行时注册表取回当轮
 * 聚合（{@code AgentSessionContext}），拼出 {@link ExecutionContext}（runState 取当轮、线程归属现查主线程一次）。
 * 会话上下文不在场（会话已删除 / 已终止并被清理）属边界情形，记 WARN 静默跳过——契约要求交付事件
 * 归属该轮事件流，脱离轮次现场时无落点。</p>
 * <p>交付已成功才到达本服务：事件上抛异常不改变登记结果（调用方已捕获并降级为告警）。</p>
 */
@Service
public class ArtifactDeliveredEventService implements ArtifactDeliverySignalPort {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDeliveredEventService.class);

    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    @Resource
    private TurnEventWriter turnEventWriter;
    /** 线程仓储：轮外落库点的线程归属锚点（主线程缺失降级 null = 归属未知，不阻断落库）。 */
    @Resource
    private SessionThreadRepository sessionThreadRepository;

    @Override
    public void publish(ArtifactDeliveredSignal signal) {
        AgentSessionContext sessionContext = sessionRegistry.get(signal.sessionId()).orElse(null);
        if (sessionContext == null) {
            log.warn("产物交付事件无轮次现场（会话上下文不在场，跳过落库）: sessionId={}, fileId={}",
                    signal.sessionId(), signal.fileId());
            return;
        }
        ExecutionContext context = new ExecutionContext(sessionContext, sessionContext.runState(),
                resolveMainThreadId(signal.sessionId()));
        turnEventWriter.persistAndBroadcast(context, ChatEventFactory.INSTANCE.artifactDelivered(
                signal.fileId(), signal.originalFilename(), signal.sizeBytes(), signal.contentType()));
    }

    /** 解析会话主线程归属 ID（交付为低频路径，现查一次；主线程缺失降级 null）。 */
    private String resolveMainThreadId(String sessionId) {
        return sessionThreadRepository.findMain(sessionId)
                .map(SessionThread::threadId)
                .orElse(null);
    }
}