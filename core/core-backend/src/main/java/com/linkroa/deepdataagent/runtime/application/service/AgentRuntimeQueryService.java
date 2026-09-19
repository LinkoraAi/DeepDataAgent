package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.api.AgentSnapshotApi;
import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert;
import com.linkroa.deepdataagent.runtime.application.convert.SseEventEnvelopeConvert;
import com.linkroa.deepdataagent.runtime.application.query.ListEventsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.runstate.TextBlockAccumulator.InFlightStream;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEventQuery;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import com.linkroa.deepdataagent.runtime.domain.repository.SessionThreadRepository;
import com.linkroa.deepdataagent.runtime.application.port.SessionRuntimeRegistry;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 运行时查询服务：会话 / 事件回放的只读用例编排。
 * <p>事件溯源模型下会话轨迹完全由 {@code chat_event} 事件流承载（{@code execution_round} /
 * {@code run_trace} 物化表已删除），回放对齐 Managed Agents 契约：按会话级 {@code seq}
 * 游标分页（{@code after=seq}）+ {@code types[]} 过滤；会话列表按
 * {@code agent_id / statuses[]} 过滤 + {@code cursor} 游标分页。</p>
 * <p>与写侧入口服务（{@code session.SessionLifecycleService} / {@code execution.TurnExecutionService} /
 * {@code hitl.HumanConfirmationService} / {@code event.InboundEventService}）拆分读写职责：
 * 本服务不承载任何写操作与事务边界，
 * 仅依赖仓储做查询与存在性校验（统一前置 {@code requireSession}）。</p>
 */
@Service
public class AgentRuntimeQueryService {

    private static final String DEEP_AGENT_SESSION_NOT_FOUND = "DEEP_AGENT_SESSION_NOT_FOUND";

    /** 线程作用域回放的分批读取上限（穷尽式回放，避免单次全量拉满账本）。 */
    private static final int THREAD_REPLAY_BATCH_SIZE = 500;

    @Resource
    private AgentSessionRepository sessionRepository;
    @Resource
    private ChatEventRepository chatEventRepository;
    @Resource
    private SessionThreadRepository sessionThreadRepository;
    @Resource
    private SessionRuntimeRegistry sessionRegistry;
    /** Agent 快照契约：Session 响应嵌入完整 Agent 快照（裁剪规则权威在 agent BC，3.9）。 */
    @Resource
    private AgentSnapshotApi agentSnapshotApi;

    /**
     * 查询会话详情（按 owner 隔离，非归属用户与不存在不可区分）；不存在时抛业务异常。
     */
    public AgentSession getSession(String sessionId) {
        return requireOwnedSession(sessionId);
    }

    /**
     * 游标分页查询会话列表（创建时间 + 行主键 keyset，shared/api-conventions Cursor 约定）。
     * <p>after_id/before_id 为会话业务 ID（sess_），先经归属校验解析为 keyset 行位点
     * （不存在 / 非本人 → 404，与详情同语义）；limit+1 探针判 has_more，
     * before 方向升序读取后由应用层翻转回请求方向。</p>
     * <p>过滤集合严格对齐公开契约 GET 列表：{@code agent_id / agent_version / deployment_id /
     * memory_store_id / statuses[] / include_archived / created_at[gt|gte|lt|lte] / order}；
     * {@code environment_id} 与 {@code metadata} 不是受支持的过滤参数（前者为自造字段、
     * 后者属 beta 搜索能力）。{@code next_page} 为不透明游标（当页末条会话 ID），
     * 客户端回传 {@code page} 即等价向后翻页。</p>
     *
     * @param query 列表查询（userId / agent 与来源过滤 / 状态 / 归档纳入 / 时间边界 / 方向 / 游标）
     * @return 游标分页结果（first_id/last_id 为会话业务 ID）
     */
    public CursorPage<AgentSession> listSessions(ListSessionsQuery query) {
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (cursor.afterId() != null) {
            AgentSession at = requireOwnedSession(cursor.afterId());
            cursorCreatedAt = at.createdAt();
            cursorRowId = at.id();
        } else if (cursor.beforeId() != null) {
            AgentSession at = requireOwnedSession(cursor.beforeId());
            cursorCreatedAt = at.createdAt();
            cursorRowId = at.id();
            reverse = true;
        }
        SessionListFilter filter = new SessionListFilter(
                query.agentId(), query.agentVersion(), query.deploymentId(), query.memoryStoreId(),
                query.statuses(), null, query.includeArchived(),
                query.createdAtGt(), query.createdAtGte(), query.createdAtLt(), query.createdAtLte(),
                query.ascending(), cursorCreatedAt, cursorRowId, reverse);
        List<AgentSession> rows = sessionRepository.findByCursor(query.userId(), filter, cursor.limit() + 1);
        // limit+1 探针读取后内存切片：余量（探针行）即 has_more；before 方向升序读取后翻回请求方向
        CursorPage<AgentSession> page = CursorPage.slice(rows, cursor, reverse, AgentSession::sessionId);
        // 不透明 page 游标信封：有下一页时回传当页末条会话 ID（客户端以 page= 回传即可续页）
        return page.hasMore() && page.nextPage() == null
                ? new CursorPage<>(page.data(), page.firstId(), page.lastId(), true, page.lastId())
                : page;
    }

    /**
     * 会话事件回放（事件溯源模型下的唯一轨迹）：返回 {@code seq > after} 的事件（升序），
     * 可选按事件类型过滤；{@code after=0} 表示全量回放。
     *
     * @param query 回放查询（sessionId / afterSequenceNum / types）
     * @return 事件列表（按 seq 升序，已落库的权威记录）
     */
    public List<ChatEvent> replayEvents(ReplayQuery query) {
        requireOwnedSession(query.sessionId());
        return chatEventRepository.findBySessionAfter(
                query.sessionId(), query.afterSequenceNum(), query.types(), query.limit());
    }

    /**
     * 事件历史分页（协议面用例）：按公开契约过滤集合读取会话（或线程作用域）事件，
     * 装配为扁平 Event 游标页 {@code {data, first_id, last_id, has_more, next_page}}。
     * <p>游标一律为 {@code evt_} 事件 ID：{@code after_id} / {@code before_id} 经
     * {@link #findEventSeq} 换算为账本 seq 位点（未命中 → 400，属请求非法）；
     * {@code order} 决定读取与展示方向（升序缺省）；{@code limit+1} 探针判 {@code has_more}，
     * 有下一页时 {@code next_page} 回传当页末条事件 ID（不透明游标）。</p>
     * <p>{@code types} 已由协议层静默剔除未知类型（列表路径不因未知类型报错）。</p>
     *
     * @param query 事件列表查询（会话 / 线程归属 / 类型 / 时间区间 / 方向 / 游标）
     * @return 扁平 Event 游标分页结果
     */
    public CursorPage<SseEventEnvelope> listEventPage(ListEventsQuery query) {
        requireOwnedSession(query.sessionId());
        CursorPageParams cursor = query.cursor();
        Long afterSeq = cursor.afterId() == null ? null : findEventSeq(query.sessionId(), cursor.afterId());
        Long beforeSeq = cursor.beforeId() == null ? null : findEventSeq(query.sessionId(), cursor.beforeId());
        List<String> typeValues = query.types().stream().map(ChatEventType::value).toList();
        List<ChatEvent> rows = chatEventRepository.findPage(new ChatEventQuery(
                query.sessionId(), query.sessionThreadId(), afterSeq, beforeSeq, typeValues,
                query.createdAtGt(), query.createdAtGte(), query.createdAtLt(), query.createdAtLte(),
                query.ascending(), cursor.limit() + 1));
        boolean hasMore = rows.size() > cursor.limit();
        List<ChatEvent> page = hasMore ? rows.subList(0, cursor.limit()) : rows;
        List<SseEventEnvelope> data = page.stream()
                .map(SseEventEnvelopeConvert.INSTANCE::toEnvelope)
                .toList();
        String firstId = page.isEmpty() ? null : page.get(0).eventId();
        String lastId = page.isEmpty() ? null : page.get(page.size() - 1).eventId();
        return new CursorPage<>(data, firstId, lastId, hasMore, hasMore ? lastId : null);
    }

    /**
     * 查询会话全部线程（公开契约 {@code GET /sessions/{id}/threads}，主线程 MUST 排首位）。
     *
     * @param sessionId 会话业务 ID（owner 校验统一前置）
     * @return 线程列表（主线程首位；无线程返回空列表）
     */
    public List<SessionThread> listThreads(String sessionId) {
        requireOwnedSession(sessionId);
        return sessionThreadRepository.findBySession(sessionId);
    }

    /**
     * 按业务 ID 查询线程并校验归属会话（线程作用域事件端点的前置存在性校验）。
     *
     * @param sessionId 会话业务 ID（owner 校验统一前置）
     * @param threadId  线程业务 ID（{@code sthr_} 前缀）
     * @return 线程领域模型
     * @throws ResourceNotFoundException 线程不存在或不属于该会话（404）
     */
    public SessionThread requireThread(String sessionId, String threadId) {
        requireOwnedSession(sessionId);
        return sessionThreadRepository.findByThreadId(sessionId, threadId)
                .orElseThrow(() -> new ResourceNotFoundException("会话线程不存在: " + threadId));
    }

    /**
     * 线程作用域事件回放（SSE 线程流回放用）：按线程归属过滤、自 {@code afterSeq} 起
     * 分批升序读取至穷尽（账本内部过滤键，不扩张对外扁平 Event 公开字段）。
     *
     * @param sessionId 会话业务 ID
     * @param threadId  线程业务 ID（调用方已完成存在性校验）
     * @param afterSeq  回放起点（seq 大于该值）
     * @return 归属该线程的事件列表（按 seq 升序）
     */
    public List<ChatEvent> replayThreadEvents(String sessionId, String threadId, long afterSeq) {
        List<ChatEvent> all = new ArrayList<>();
        long cursor = Math.max(afterSeq, 0L);
        while (true) {
            List<ChatEvent> batch = chatEventRepository.findPage(new ChatEventQuery(
                    sessionId, threadId, cursor, null, List.of(),
                    null, null, null, null, true, THREAD_REPLAY_BATCH_SIZE));
            all.addAll(batch);
            if (batch.size() < THREAD_REPLAY_BATCH_SIZE) {
                return all;
            }
            cursor = batch.get(batch.size() - 1).seq();
        }
    }

    /**
     * 游标分页列出会话挂载资源（资源管理契约 {@code list}）。
     * <p>资源随会话 JSONB 整列存储、无独立时间戳，按挂载顺序内存切片：
     * {@code after_id} 向后（列表尾部方向）、{@code before_id} 向前，游标资源
     * 未命中 → 404（与会话列表游标语义一致）。</p>
     *
     * @param sessionId 会话业务 ID
     * @param cursor    游标分页参数（limit / after_id / before_id）
     * @return 游标分页结果（first_id/last_id 为资源业务 ID sesr_）
     */
    public CursorPage<SessionResource> listResources(String sessionId, CursorPageParams cursor) {
        AgentSession session = requireOwnedSession(sessionId);
        List<SessionResource> all = session.resources();
        if (cursor.afterId() != null) {
            // 向后翻：游标之后的余量取头部 limit 条，仍有余量即 has_more
            int from = indexOfResource(all, cursor.afterId()) + 1;
            return CursorPage.slice(all.subList(from, all.size()), cursor, false, SessionResource::id);
        }
        if (cursor.beforeId() != null) {
            // 向前翻：游标之前的前缀以「尾部为头部」读取，取头部 limit 条后翻回挂载顺序
            int to = indexOfResource(all, cursor.beforeId());
            List<SessionResource> tailFirst = List.copyOf(all.subList(0, to)).reversed();
            return CursorPage.slice(tailFirst, cursor, true, SessionResource::id);
        }
        return CursorPage.slice(all, cursor, false, SessionResource::id);
    }

    /**
     * 查询单条挂载资源（资源管理契约 {@code get}，GitHub 令牌只写不读、
     * 由响应装配层剔除）。
     *
     * @param sessionId  会话业务 ID
     * @param resourceId 资源业务 ID（sesr_）
     * @return 挂载资源值对象
     */
    public SessionResource getResource(String sessionId, String resourceId) {
        AgentSession session = requireOwnedSession(sessionId);
        return session.findResource(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("挂载资源不存在: " + resourceId));
    }

    /**
     * 事件 ID → 会话级 seq 换算（{@code after_id} 续传游标定位）；未命中抛 400
     * （游标事件不存在属请求非法，区别于资源级 404）。
     *
     * @param sessionId 会话业务 ID（owner 校验统一前置）
     * @param eventId   buffered 事件业务 ID（evt_）
     * @return 该事件的 seq
     */
    public long findEventSeq(String sessionId, String eventId) {
        requireOwnedSession(sessionId);
        return chatEventRepository.findSeqByEventId(sessionId, eventId)
                .orElseThrow(() -> new IllegalArgumentException("after_id 事件不存在: " + eventId));
    }

    // ==================== Agent 嵌入快照（3.9） ====================

    /**
     * Session 响应嵌入的完整 Agent 快照（契约约定「Session 嵌入 Agent 快照」）：
     * 经 Agent 快照契约解析绑定版本台账（裁剪规则权威在 agent BC：audit / metadata 剔除、
     * {@code system} 输出、{@code multiagent} 恒 {@code null}）；台账缺行 / 解析异常
     * 降级为 {@code id / type / version} 摘要（会话可读性优先，不因 Agent 删除而 500）。
     *
     * @param session 会话领域模型
     * @return 嵌入 Agent 快照对象（snake_case 键）
     */
    public Map<String, Object> agentSnapshot(AgentSession session) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", session.agentId());
        summary.put("type", "agent");
        summary.put("version", VersionNumbers.parseNumber(session.agentVersion()));
        try {
            AgentSnapshotDTO dto = agentSnapshotApi.resolveSnapshot(session.agentId(),
                    VersionNumbers.parseOrNull(session.agentVersion()), UserIds.parse(session.userId()));
            return dto == null ? summary : dto.agent();
        } catch (RuntimeException ex) {
            return summary;
        }
    }

    // ==================== Session Thread 查询面（D5：list threads / thread events 只读端点） ====================
    // 线程状态镜像写入与事件归属落库由创建会话随行链路（session.SessionLifecycleService）与
    // execution 包承载；本服务只提供只读查询面（listThreads / requireThread / replayThreadEvents），
    // 归档面（线程归档端点）本期不提供（multiagent 子线程能力出界）。

    /** 资源定位：按 sesr_ ID 在挂载列表中检索下标（未命中 → 404）。 */
    private int indexOfResource(List<SessionResource> resources, String resourceId) {
        for (int i = 0; i < resources.size(); i++) {
            if (resources.get(i).id().equals(resourceId)) {
                return i;
            }
        }
        throw new ResourceNotFoundException("挂载资源不存在: " + resourceId);
    }

    /**
     * SSE 断点游标解析结果（三段重连语义定位）。
     *
     * @param afterSequence buffered 回放起点（回放 seq 大于该值的事件）
     * @param midStream     游标是否等于进行中流式事件 ID（二段：不重放历史 delta、仅推重连后新增）
     */
    public record ReplayPosition(long afterSequence, boolean midStream) {
    }

    /**
     * 解析 {@code Last-Event-ID} 断点游标为 buffered 回放起点（对齐增量帧三段重连语义）：
     * <ul>
     *   <li>空白（未携带）：0（全量回放）；会话已归档时携带非空游标 → 400（归档事件不可回放）；</li>
     *   <li>数字（历史 seq 游标，兼容旧客户端）：直接透传；非数字的非法游标 → 400；</li>
     *   <li>evt_ 事件 ID 命中已落库事件：该事件 seq；命中非公开事件（流式专用帧类型，不可回放）→ 400；</li>
     *   <li>evt_ 事件 ID 等于进行中流事件（未落库）：进行中流 baseSeq 并标记 midStream（二段）；</li>
     *   <li>evt_ 事件 ID 未命中任何已落库事件且非进行中流 → 404（引用不存在事件）。</li>
     * </ul>
     *
     * @param sessionId    会话 ID（owner 校验统一前置）
     * @param lastEventId  SSE 标准 {@code Last-Event-ID} 请求头原值（可空）
     * @return 回放定位
     * @throws IllegalArgumentException 游标形态非法或指向非公开事件（400 {@code invalid_request_error}）
     * @throws ResourceNotFoundException 游标引用的已落库事件不存在（404 {@code not_found_error}）
     */
    public ReplayPosition resolveReplayPosition(String sessionId, String lastEventId) {
        AgentSession session = requireOwnedSession(sessionId);
        if (StringUtils.isBlank(lastEventId)) {
            return new ReplayPosition(0L, false);
        }
        if (session.archived()) {
            // 归档会话不承担重连回放语义（events spec：Last-Event-ID 指向归档事件 → 400）
            throw new IllegalArgumentException("Last-Event-ID 指向归档会话事件: " + sessionId);
        }
        String cursor = lastEventId.trim();
        if (!cursor.startsWith(ChatEvent.EVENT_ID_PREFIX)) {
            try {
                return new ReplayPosition(Math.max(Long.parseLong(cursor), 0L), false);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Last-Event-ID 非法: " + lastEventId);
            }
        }
        Optional<ChatEvent> persisted = chatEventRepository.findByEventId(sessionId, cursor);
        if (persisted.isPresent()) {
            ChatEvent event = persisted.get();
            // 流式专用帧（event_start / event_delta）不落库、不回放，指向即属非公开事件（400）
            if (event.type() == ChatEventType.EVENT_START || event.type() == ChatEventType.EVENT_DELTA) {
                throw new IllegalArgumentException("Last-Event-ID 指向非公开事件: " + cursor);
            }
            return new ReplayPosition(event.seq(), false);
        }
        InFlightStream inFlight = currentInFlightStream(sessionId);
        if (inFlight != null && inFlight.eventId().equals(cursor)) {
            return new ReplayPosition(inFlight.baseSeq(), true);
        }
        throw new ResourceNotFoundException("Last-Event-ID 事件不存在: " + cursor);
    }

    /**
     * 当前进行中的流式块快照（一段重连回补判定用；会话不在场 / 无进行中流返回 {@code null}）。
     */
    public InFlightStream currentInFlightStream(String sessionId) {
        AgentSessionContext context = sessionRegistry.get(sessionId).orElse(null);
        if (context == null || context.runState() == null) {
            return null;
        }
        return context.runState().inFlightStream();
    }

    /**
     * 进行中流式块的累积文本（一段重连回补聚合 delta 用；会话不在场 / 块缺失返回空串）。
     */
    public String accumulatedStreamText(String sessionId, String blockId) {
        AgentSessionContext context = sessionRegistry.get(sessionId).orElse(null);
        if (context == null || context.runState() == null) {
            return "";
        }
        return context.runState().accumulatedText(blockId);
    }

    /**
     * 按 ID 查询会话并校验归属（owner 隔离）：会话不存在或非当前认证用户归属时抛业务异常
     * （越权与不存在不可区分，统一 404 语义，不泄露存在性）。
     */
    private AgentSession requireOwnedSession(String sessionId) {
        AgentSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在"));
        if (!session.ownedBy(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException(DEEP_AGENT_SESSION_NOT_FOUND + ": 会话不存在");
        }
        return session;
    }
}