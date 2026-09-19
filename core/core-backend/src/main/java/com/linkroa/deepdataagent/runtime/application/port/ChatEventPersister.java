package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;

/**
 * 聊天事件异步落库端口：SSE 推流与持久化解耦，事件先入内存队列，
 * 由后台按 {@code seq} 单调顺序批量落库。
 * <p>落库分两档语义：</p>
 * <ul>
 *   <li><b>异步尽力而为</b>（{@link #enqueue} + {@link #flush}）：写库失败重试并记录日志，
 *       最终凭 {@code (session_id, seq)} 唯一索引与断线重连回放做幂等 / 去重兜底；
 *       进程崩溃等非优雅停机会丢失尚未落库的事件。文本 / 思考块等可重建事件容忍此档。</li>
 *   <li><b>严格排空协议</b>（{@link #flush} + {@link #isPoisoned}）：状态迁移（终态 / HITL 挂起）
 *       的完整性栅栏，分两段——① 事务<b>外</b>先 {@code flush()} 整队排空（逐条独立提交，
 *       他会话事件不进入本会话 JDBC 事务、不随本会话回滚丢失）；② 事务首行按会话
 *       {@code isPoisoned} 查毒，命中即由调用方抛业务异常令外层事务整批回滚，
 *       杜绝「状态已迁移而账本缺中间事件」的回放不一致。</li>
 * </ul>
 */
public interface ChatEventPersister {

    /**
     * 入队（非阻塞，不等待落库）。
     */
    void enqueue(ChatEvent event);

    /**
     * 排空待写队列（优雅停机 / 测试确定性 / 严格排空协议·事务前段用）。
     * <p>尽力而为语义：写失败仅记日志不抛异常，重试耗尽的事件所属会话经
     * {@link #isPoisoned(String)} 可查。<b>与状态迁移事务内首行的 {@link #isPoisoned(String)}
     * 查毒共同构成严格排空协议</b>：终态 / HITL 挂起须在开启事务<b>之前</b>调用本方法整队排空
     * （独立提交），再于事务首行按会话查毒、命中即抛令事务回滚。MUST NOT 把排空放进状态迁移
     * 事务内做（他会话事件行会随本会话回滚一并丢失，构成跨会话账本污染）。</p>
     */
    void flush();

    /**
     * 查询该会话是否存在「重试耗尽仍未落库」的事件（严格排空协议·事务首行查毒）。
     * <p>毒标志仅在落库线程持锁处理队列条目的瞬间置位；调用方先行 {@link #flush()} 排空后，
     * 本查询结果即与排空结果一致（终态 / HITL 挂起事务首行专用，命中由调用方抛
     * {@link com.linkroa.deepdataagent.shared.exception.DeepDataAgentException} 令事务回滚、
     * 会话状态不迁移）。按 sessionId 隔离，不存在跨会话误伤。</p>
     *
     * @param sessionId 待确认落库完整性的会话 ID
     * @return true=该会话存在重试耗尽仍未落库的事件（账本不可信，须拒绝状态迁移）
     */
    boolean isPoisoned(String sessionId);

    /**
     * 清除该会话的持久化失败标志（新一轮开跑 CAS 提交成功后调用）。
     * <p>幂等内存操作：旧轮丢失的事件属已回滚旧轮（事实已在结构化 ERROR 留痕），
     * 新一轮不继承旧轮故障，避免会话被永久砖化。</p>
     *
     * @param sessionId 会话 ID
     */
    void clearPoisonFlag(String sessionId);
}
