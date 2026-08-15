package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;

import java.util.List;

/**
 * 链路追踪 Span 仓储接口。
 */
public interface RunTraceRepository {

    /**
     * 保存 span。
     */
    RunTrace save(RunTrace trace);

    /**
     * 按轮次查询全部 span（升序）。
     */
    List<RunTrace> findByRound(String roundId);
}