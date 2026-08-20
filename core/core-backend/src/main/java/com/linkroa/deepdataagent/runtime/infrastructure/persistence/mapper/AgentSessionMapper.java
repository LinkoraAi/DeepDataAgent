package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Agent 会话 Mapper。
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    default AgentSessionEntity findBySessionId(String sessionId) {
        return selectOne(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    default List<AgentSessionEntity> findByUserId(String userId, int page, int size) {
        return selectList(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getUserId, userId)
                .orderByAsc(AgentSessionEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + ((long) Math.max(0, page - 1) * size)));
    }

    default long countByUserId(String userId) {
        return selectCount(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getUserId, userId));
    }

    /**
     * 抢占执行权的原子 CAS：仅当状态为 IDLE 时置 RUNNING（保证同一会话同时只有一个执行；TERMINATED 会话不可复活）。
     *
     * @return 受影响行数（1=抢占成功，0=会话正忙或已终止）
     */
    default int tryMarkRunning(String sessionId) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getStatus, "RUNNING")
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .eq(AgentSessionEntity::getStatus, "IDLE"));
    }

    /**
     * 幂等回 IDLE：仅当状态为 RUNNING 时恢复（防止终态后被异常路径误改）。
     *
     * @return 受影响行数（1=成功，0=会话非 RUNNING 状态）
     */
    default int markIdle(String sessionId) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getStatus, "IDLE")
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .eq(AgentSessionEntity::getStatus, "RUNNING"));
    }

    default int updateStatus(String sessionId, String status) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getStatus, status)
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    default int updateMeta(String sessionId, String title, String metadata) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getTitle, title)
                .set(AgentSessionEntity::getMetadata, metadata)
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    default int touchLastActive(String sessionId) {
        return update(null, Wrappers.<AgentSessionEntity>lambdaUpdate()
                .set(AgentSessionEntity::getLastActiveAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .set(AgentSessionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentSessionEntity::getSessionId, sessionId));
    }

    default List<String> findRunningSessionIds() {
        return selectList(Wrappers.<AgentSessionEntity>lambdaQuery()
                        .select(AgentSessionEntity::getSessionId)
                        .eq(AgentSessionEntity::getStatus, "RUNNING"))
                .stream()
                .map(AgentSessionEntity::getSessionId)
                .toList();
    }
}