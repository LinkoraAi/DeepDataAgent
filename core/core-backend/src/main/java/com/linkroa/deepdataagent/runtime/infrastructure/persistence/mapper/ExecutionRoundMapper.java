package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ExecutionRoundEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 执行轮次 Mapper。
 */
@Mapper
public interface ExecutionRoundMapper extends BaseMapper<ExecutionRoundEntity> {

    default ExecutionRoundEntity findByRoundId(String roundId) {
        return selectOne(Wrappers.<ExecutionRoundEntity>lambdaQuery()
                .eq(ExecutionRoundEntity::getRoundId, roundId)
                .last("LIMIT 1"));
    }

    default List<ExecutionRoundEntity> findBySessionId(String sessionId) {
        return selectList(Wrappers.<ExecutionRoundEntity>lambdaQuery()
                .eq(ExecutionRoundEntity::getSessionId, sessionId)
                .orderByAsc(ExecutionRoundEntity::getRoundNumber));
    }

    /**
     * 会话内最大轮次序号（无记录返回 0）。
     */
    default int maxRoundNumber(String sessionId) {
        List<ExecutionRoundEntity> rows = selectList(Wrappers.<ExecutionRoundEntity>lambdaQuery()
                .select(ExecutionRoundEntity::getRoundNumber)
                .eq(ExecutionRoundEntity::getSessionId, sessionId)
                .orderByDesc(ExecutionRoundEntity::getRoundNumber)
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return 0;
        }
        Integer value = rows.get(0).getRoundNumber();
        return value == null ? 0 : value;
    }

    /**
     * 将指定会话的 RUNNING 轮次批量置为 INTERRUPTED（启动恢复）。
     *
     * @return 受影响行数
     */
    default int updateRunningToInterrupted(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        return update(null, Wrappers.<ExecutionRoundEntity>lambdaUpdate()
                .set(ExecutionRoundEntity::getStatus, "INTERRUPTED")
                .in(ExecutionRoundEntity::getSessionId, sessionIds)
                .eq(ExecutionRoundEntity::getStatus, "RUNNING"));
    }
}