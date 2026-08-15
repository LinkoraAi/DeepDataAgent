package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;

import java.util.List;
import java.util.Optional;

/**
 * 执行轮次仓储接口。
 */
public interface ExecutionRoundRepository {

    /**
     * 保存轮次（新增或更新）。
     */
    ExecutionRound save(ExecutionRound round);

    /**
     * 按轮次 ID 查询。
     */
    Optional<ExecutionRound> findByRoundId(String roundId);

    /**
     * 按会话 ID 查询全部轮次（按 round_number 升序）。
     */
    List<ExecutionRound> findBySessionId(String sessionId);

    /**
     * 会话内下一轮次序号。
     */
    int nextRoundNumber(String sessionId);

    /**
     * 批量将指定会话的 RUNNING 轮次置为终态（启动恢复用）。
     */
    int updateRunningToInterrupted(List<String> sessionIds);
}