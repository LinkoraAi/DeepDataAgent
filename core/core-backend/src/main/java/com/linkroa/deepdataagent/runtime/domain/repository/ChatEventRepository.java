package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEventQuery;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 聊天事件仓储接口（事件溯源模型下唯一权威业务记录）。
 */
public interface ChatEventRepository {

    /**
     * 保存事件。
     */
    ChatEvent save(ChatEvent event);

    /**
     * 分配会话内下一序列号（须在事务内调用；会话级「同一会话同时只有一个执行」+
     * 单实例 + 单事务写入保证 MAX+1 偶发竞争安全）。
     *
     * @param sessionId 会话 ID
     * @return 下一可用 seq（从 1 开始）
     */
    long nextSequenceNum(String sessionId);

    /**
     * 按会话查询序列号大于 afterSeq 的事件（回放用，升序 + 可选限量，seq 游标分页）。
     *
     * @param sessionId 会话 ID
     * @param afterSeq  回放起点（seq 大于该值）
     * @param types     事件类型过滤（null / 空表示不过滤）
     * @param limit     单页上限（null 表示不限量）
     * @return 事件列表（按 seq 升序，至多 limit 条）
     */
    List<ChatEvent> findBySessionAfter(String sessionId, long afterSeq, List<String> types, Integer limit);

    /**
     * 按事件 ID 查询已落库序列号（SSE {@code Last-Event-ID} 断点游标换算：
     * evt_ 事件 ID → buffered 回放起点 seq）。
     *
     * @param sessionId 会话 ID
     * @param eventId   事件 ID（evt_ 前缀）
     * @return 序列号（empty = 该会话无此已落库事件）
     */
    OptionalLong findSeqByEventId(String sessionId, String eventId);

    /**
     * 清理指定会话的全部历史事件（6.3 delete 面：会话删除同事务物理清理事件流）。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数
     */
    int deleteBySessionId(String sessionId);

    /**
     * 按会话查询指定类型集合的全部事件（升序）。
     * <p>durable HITL 等待现场定位用：工具调用与工具结果两类各自取齐后，
     * 由应用层按 {@code tool_use_id} 配对求出「未应答」批次——等待事实完全由事件表承载，
     * 不再有 {@code session.requires_action} 旁路事件。</p>
     *
     * @param sessionId 会话 ID
     * @param types     事件类型集合（如 {@code TOOL_USE_TYPES} / {@code TOOL_RESULT_TYPES}）
     * @return 事件列表（按 seq 升序，类型集合为空时返回空列表）
     */
    List<ChatEvent> findByTypes(String sessionId, List<String> types);

    /**
     * 按公开事件 id 集合批查会话内工具调用事件行（durable 续跑整批明细重建）。
     * <p>覆盖 {@link com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType#TOOL_USE_TYPES}
     * 全集（内置 {@code agent.tool_use} 与 MCP {@code agent.mcp_tool_use} 载荷同形）。</p>
     *
     * @param sessionId 会话 ID
     * @param eventIds  公开事件 id 集合（evt_，未应答工具调用事件的 id）
     * @return 工具调用事件列表（按 seq 升序）
     */
    List<ChatEvent> findToolUsesByEventIds(String sessionId, List<String> eventIds);

    /**
     * 事件列表游标分页与过滤查询（公开契约 {@code GET /sessions/{id}/events} 及其线程作用域嵌套端点）。
     * <p>按 {@link ChatEventQuery} 的组合条件读取：会话 / 线程归属、seq 游标方向、
     * 类型集合与 created_at 区间；返回条数上限含调用方探针余量。</p>
     *
     * @param query 查询条件（会话必填，其余可空 / 空集合表示不过滤）
     * @return 事件列表（按 {@code ascending} 指定方向）
     */
    List<ChatEvent> findPage(ChatEventQuery query);

    /**
     * 按公开事件 ID 查询会话内单条已落库事件（SSE {@code Last-Event-ID} 定位：
     * 区分「指向归档 / 非公开事件」（400）与「引用不存在事件」（404））。
     *
     * @param sessionId 会话 ID
     * @param eventId   事件 ID（evt_ 前缀）
     * @return 事件（empty = 该会话无此已落库事件）
     */
    Optional<ChatEvent> findByEventId(String sessionId, String eventId);
}