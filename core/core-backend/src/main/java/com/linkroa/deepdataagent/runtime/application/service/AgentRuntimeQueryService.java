package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.RunTraceRepository;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 运行时查询服务：会话 / 轮次 / 事件 / 链路追踪的只读用例编排。
 * <p>与 {@link AgentRuntimeCommandService} 拆分读写职责：本服务不承载任何写操作与
 * 事务边界，仅依赖仓储做查询与存在性校验（统一前置 {@code requireSession} /
 * {@code requireRound}）。</p>
 */
@Service
public class AgentRuntimeQueryService {

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";
    private static final String DEEP_AGENT_ROUND_NOT_FOUND = "DEEP_AGENT_ROUND_NOT_FOUND";

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ExecutionRoundRepository roundRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private RunTraceRepository runTraceRepository;

    /**
     * 查询会话详情；不存在时抛业务异常。
     */
    public AgentSession getSession(String sessionId) {
        return requireSession(sessionId);
    }

    /**
     * 分页查询会话列表。
     */
    public PaginatedResult<AgentSession> listSessions(ListSessionsQuery query) {
        List<AgentSession> sessions = sessionRepository.findByUserId(query.userId(), query.page(), query.size());
        long total = sessionRepository.countByUserId(query.userId());
        return new PaginatedResult<>(sessions, total, query.page(), query.size());
    }

    /**
     * 会话内轮次列表（round_number 升序）。
     */
    public List<ExecutionRound> listRounds(String sessionId) {
        requireSession(sessionId);
        return roundRepository.findBySessionId(sessionId);
    }

    /**
     * 单轮事件回放（sequence_num 升序）。
     */
    public List<ChatEvent> roundEvents(String roundId) {
        requireRound(roundId);
        return chatEventRepository.findByRound(roundId);
    }

    /**
     * 会话事件回放（sequence_num > afterSequenceNum 升序）。
     */
    public List<ChatEvent> replayEvents(ReplayQuery query) {
        requireSession(query.sessionId());
        return chatEventRepository.findBySessionAfter(query.sessionId(), query.afterSequenceNum());
    }

    /**
     * 轮次链路追踪（span 树，升序）。
     */
    public List<RunTrace> getTrace(String roundId) {
        requireRound(roundId);
        return runTraceRepository.findByRound(roundId);
    }

    /**
     * 按 ID 查询会话，不存在时抛业务异常（会话相关用例的统一前置校验）。
     */
    private AgentSession requireSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new DeepDataAgentException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
    }

    /**
     * 按 ID 校验轮次存在，不存在时抛业务异常（轮次相关用例的统一前置校验）。
     */
    private void requireRound(String roundId) {
        roundRepository.findByRoundId(roundId)
                .orElseThrow(() -> new DeepDataAgentException(DEEP_AGENT_ROUND_NOT_FOUND + ": 轮次不存在"));
    }

    /**
     * 分页结果。
     */
    public record PaginatedResult<T>(List<T> data, long total, int page, int size) {
    }
}