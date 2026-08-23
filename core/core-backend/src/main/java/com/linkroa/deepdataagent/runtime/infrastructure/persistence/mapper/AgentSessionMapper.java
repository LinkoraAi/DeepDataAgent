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
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式（改写为普通 lambda 会导致列名解析失败），运行时与 null 语义无关。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    default AgentSessionEntity findBySessionId(String sessionId) {
        return selectOne(Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    default List<AgentSessionEntity> findByFilters(String userId, String agentId,
                                                   List<String> statuses,
                                                   OffsetDateTime cursorCreatedAt, Long cursorId,
                                                   int limit) {
        LambdaQueryWrapper<AgentSessionEntity> wrapper = Wrappers.<AgentSessionEntity>lambdaQuery()
                .eq(AgentSessionEntity::getUserId, userId);
        if (agentId != null && !agentId.isBlank()) {
            wrapper.eq(AgentSessionEntity::getAgentId, agentId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(AgentSessionEntity::getStatus, statuses);
        }
        if (cursorCreatedAt != null && cursorId != null) {
            wrapper.and(w -> w
                    .gt(AgentSessionEntity::getCreatedAt, cursorCreatedAt)
                    .or(o -> o.eq(AgentSessionEntity::getCreatedAt, cursorCreatedAt)
                            .gt(AgentSessionEntity::getId, cursorId)));
        }
        return selectList(wrapper
                .orderByAsc(AgentSessionEntity::getCreatedAt)
                .orderByAsc(AgentSessionEntity::getId)
                .last("LIMIT " + limit));
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