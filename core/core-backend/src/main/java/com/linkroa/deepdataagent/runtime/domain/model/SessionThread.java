package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Session 线程领域模型（对应 session_thread 表，单 Agent 场景仅主线程）。
 * <p>标准结构：{@code threadId}（{@code sthr_} 前缀）、{@code sessionId}、
 * {@code parentThreadId}（协调器主线程为 null）、{@code agent}
 * （该线程使用的 Agent 快照 JSON 文本——Session 嵌入快照裁剪规则再去 {@code multiagent}）、
 * {@code status}（对外四态 {@code idle/running/rescheduling/terminated}，
 * 复用 {@link AgentSessionStatus} 值域）。</p>
 * <p>旧字段 {@code name / role / stop_reason / usage} 一律不承载
 * （对外形状由响应层锁定「MUST NOT 出现」）。线程归档端点本期不实现
 * （multiagent 子线程能力出界），故本模型不提供归档状态方法；公开契约中子线程归档动作
 * 亦为「置 {@code archived_at} + status 置 terminated」而非新增 archived 状态值。</p>
 */
public record SessionThread(
        Long id,
        String threadId,
        String sessionId,
        String parentThreadId,
        String agent,
        AgentSessionStatus status,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    /** 线程业务 ID 前缀（shared/api-conventions：资源 ID 语义前缀，create 时装配）。 */
    public static final String THREAD_ID_PREFIX = "sthr_";

    public SessionThread {
        if (StringUtils.isBlank(threadId) || !threadId.startsWith(THREAD_ID_PREFIX)) {
            throw new IllegalArgumentException("线程ID不能为空且必须带 sthr_ 前缀");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("所属会话ID不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("线程状态不能为空");
        }
        // Agent 快照：空白收敛为空 JSON 对象文本（快照解析属响应装配层）
        agent = StringUtils.isBlank(agent) ? "{}" : agent;
    }

    /**
     * 创建会话主线程（协调器主线程：{@code parentThreadId=null}，初始 idle，
     * 快照取会话绑定 Agent 的裁剪快照）。
     *
     * @param sessionId     所属会话业务 ID
     * @param agentSnapshot 线程 Agent 快照 JSON 文本（可空收敛为 {}）
     */
    public static SessionThread createMain(String sessionId, String agentSnapshot) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new SessionThread(null, newThreadId(), sessionId, null, agentSnapshot,
                AgentSessionStatus.IDLE, null, now, now, null, null);
    }

    /**
     * 从数据库恢复（查询 / 回放场景）。
     */
    public static SessionThread restore(
            Long id,
            String threadId,
            String sessionId,
            String parentThreadId,
            String agent,
            AgentSessionStatus status,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new SessionThread(id, threadId, sessionId, parentThreadId, agent,
                status, archivedAt, createdAt, updatedAt, createdBy, updatedBy);
    }

    /**
     * 是否协调器主线程（主线程不可归档、不可被委派创建链回收）。
     */
    public boolean isMainThread() {
        return parentThreadId == null || parentThreadId.isBlank();
    }

    /**
     * 派生指定状态的线程（线程状态镜像迁移后回填内存视图，刷新 {@code updatedAt}）。
     * <p>主线程 {@code archivedAt} 恒为 null——归档不是状态取值。</p>
     *
     * @param nextStatus 目标线程状态（非空）
     * @return 迁移后的线程
     */
    public SessionThread withStatus(AgentSessionStatus nextStatus) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new SessionThread(id, threadId, sessionId, parentThreadId, agent,
                nextStatus, archivedAt, createdAt, now, createdBy, updatedBy);
    }

    /** 生成 sthr_ 前缀线程业务 ID（32 位十六进制）。 */
    private static String newThreadId() {
        return THREAD_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
