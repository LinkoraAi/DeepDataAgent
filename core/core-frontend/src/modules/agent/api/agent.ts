/**
 * Agent 运行时接口层（Session / Event 契约）。
 * <p>对齐后端 AgentSessionController + AgentChatEventController（/api/v1/cloud/sessions）：
 * 公开事件为**扁平 Event 对象**——固定字段仅 {@code id}（evt_ 前缀）/ {@code type}
 * （{@code {域}.{动作}}）/ 可选 {@code processed_at}，其余字段为该事件类型自有的顶层字段
 * （content / tool_use_id / stop_reason / error 等），MUST NOT 出现 object / role / 固定
 * content 槽 / created_at / seq / payload。</p>
 * <p>{@code POST .../events} 请求体为 {@code {"events":[...]}}，成功回显 {@code {"data":[...]}}
 * 已落库扁平事件，投递即驱动 turn；实时渲染统一经 {@code GET .../events/stream} SSE
 * （帧 {@code event} = 扁平事件 type、{@code id} = evt_ 事件 ID 断点游标，与之逐字一致）；
 * 前端经 fetch-stream 订阅（可携带 Authorization 头），重连时以 {@code Last-Event-ID}
 * 请求头回传游标。增量帧（event_start / event_delta）为**连接级协商**，仅经
 * {@code event_deltas[]} 声明后对该连接生效。轮次（rounds）端点已随后端移除。</p>
 */
import { authHeaders, fetchJson, interceptUnauthorized, type CursorPageResponse } from '@/shared/api/http';

// ==================== 常量 ====================

/** Agent 运行时 REST 基路径（v1 版本化，后端路径前缀 /api/v1/cloud）。 */
export const API_BASE = '/api/v1/cloud';

/** 会话状态事件类型前缀（session.status_&lt;status&gt;，四态派生）。 */
export const SESSION_STATUS_PREFIX = 'session.status_';

/** 主线程状态事件类型前缀（session.thread_status_&lt;status&gt;，四态派生）。 */
export const THREAD_STATUS_PREFIX = 'session.thread_status_';

/** 聊天事件类型（与后端 ChatEventType 权威事件表对齐，{域}.{动作} 形式）。 */
export const CHAT_EVENT_TYPES = {
  // 入站（投递即驱动 / 影响 turn）
  USER_MESSAGE: 'user.message',
  USER_INTERRUPT: 'user.interrupt',
  USER_TOOL_CONFIRMATION: 'user.tool_confirmation',
  USER_TOOL_RESULT: 'user.tool_result',
  USER_CUSTOM_TOOL_RESULT: 'user.custom_tool_result',
  SYSTEM_MESSAGE: 'system.message',
  // 出站 Agent
  AGENT_MESSAGE: 'agent.message',
  AGENT_THINKING: 'agent.thinking',
  AGENT_TOOL_USE: 'agent.tool_use',
  AGENT_TOOL_RESULT: 'agent.tool_result',
  AGENT_CUSTOM_TOOL_USE: 'agent.custom_tool_use',
  AGENT_MCP_TOOL_USE: 'agent.mcp_tool_use',
  AGENT_MCP_TOOL_RESULT: 'agent.mcp_tool_result',
  AGENT_ARTIFACT_DELIVERED: 'agent.artifact_delivered',
  // 会话状态（四态）/ 错误 / 管理面
  SESSION_STATUS_RUNNING: 'session.status_running',
  SESSION_STATUS_IDLE: 'session.status_idle',
  SESSION_STATUS_RESCHEDULED: 'session.status_rescheduled',
  SESSION_STATUS_TERMINATED: 'session.status_terminated',
  SESSION_ERROR: 'session.error',
  SESSION_UPDATED: 'session.updated',
  SESSION_DELETED: 'session.deleted',
  // 线程（协调器多线程，单 Agent 场景仅主线程产出状态镜像）
  SESSION_THREAD_CREATED: 'session.thread_created',
  SESSION_THREAD_STATUS_RUNNING: 'session.thread_status_running',
  SESSION_THREAD_STATUS_IDLE: 'session.thread_status_idle',
  SESSION_THREAD_STATUS_RESCHEDULED: 'session.thread_status_rescheduled',
  SESSION_THREAD_STATUS_TERMINATED: 'session.thread_status_terminated',
  // 计量（本期 UI 不渲染）
  SPAN_MODEL_REQUEST_START: 'span.model_request_start',
  SPAN_MODEL_REQUEST_END: 'span.model_request_end',
  // 流式专用帧（不落库、不回放；连接级协商 event_deltas[] 后下发）
  EVENT_START: 'event_start',
  EVENT_DELTA: 'event_delta',
} as const;

/** SSE buffered 订阅需注册的事件类型全集（帧 event 名 = 扁平事件 type；流式帧另行分派）。 */
const STREAM_EVENT_TYPES: string[] = [
  'user.message',
  'agent.message',
  'agent.thinking',
  'agent.tool_use',
  'agent.tool_result',
  'agent.custom_tool_use',
  'agent.mcp_tool_use',
  'agent.mcp_tool_result',
  'agent.artifact_delivered',
  'session.status_running',
  'session.status_idle',
  'session.status_rescheduled',
  'session.status_terminated',
  'session.error',
  'session.updated',
  'session.deleted',
  'session.thread_created',
  'session.thread_status_running',
  'session.thread_status_idle',
  'session.thread_status_rescheduled',
  'session.thread_status_terminated',
  'span.model_request_start',
  'span.model_request_end',
];

// ==================== 类型定义 ====================

/** 会话对外四态（小写词汇；归档为 archived_at 正交维度、取消为响应固定回执，均非状态取值）。 */
export type SessionStatus = 'idle' | 'running' | 'rescheduling' | 'terminated';

/** 线程对外四态（与会话同词汇域）。 */
export type ThreadStatus = SessionStatus;

/** 收敛原因（session.status_idle / session.thread_status_idle 的对象形态字段，客户端须容忍新增值）。 */
export interface StopReason {
  type: 'end_turn' | 'interrupted' | 'error' | 'max_iterations' | string;
}

/** 错误重试状态（本期重试能力未实现，恒 terminal；结构须完整）。 */
export interface RetryStatus {
  type: 'retrying' | 'exhausted' | 'terminal' | string;
}

/** session.error 的类型自有字段（诊断码字段名为中性的 error_code）。 */
export interface SessionErrorDetail {
  type: string;
  message: string;
  retry_status: RetryStatus;
  error_code?: string;
}

/** image 内容块来源（base64 无 data URL 前缀 + media_type / 外部 HTTPS / 已就绪 file_id）。 */
export interface EventImageSource {
  type: 'base64' | 'url' | 'file' | string;
  media_type?: string;
  data?: string;
  url?: string;
  file_id?: string;
}

/** 消息 ContentBlock（content 数组元素：文本块 / 图片块等，按 type 取用自有字段）。 */
export interface EventContentBlock {
  type: string;
  text?: string;
  source?: EventImageSource;
  [key: string]: unknown;
}

/**
 * 扁平 Event 对象（与后端 SseEventEnvelope 对齐）。
 * <p>固定字段仅 {@code id}（evt_ 前缀，回放 / 实时重合去重键与断点游标）/ {@code type}
 * （{@code {域}.{动作}}）/ 可选 {@code processed_at}；类型自有字段（content / tool_use_id /
 * name / input / result / stop_reason / error / file_id / original_filename / size /
 * content_type 等）直接位于顶层，经索引签名取用。MUST NOT 出现 object / role /
 * 固定 content 槽 / created_at / seq / payload。</p>
 */
export interface EventEnvelope {
  id: string;
  type: string;
  processed_at?: string | null;
  [key: string]: unknown;
}

/** 增量内容片段（event_delta 的 delta 载荷）。 */
export interface ContentDelta {
  type: 'content_delta';
  index: number;
  content: EventContentBlock;
}

/** 流式增量帧（仅实时推送，不落库不回放；SSE id 与最终 buffered 事件同 ID）。 */
export type StreamFrame =
  | { type: 'event_start'; event: { id: string; type: string } }
  | { type: 'event_delta'; event_id: string; delta: ContentDelta };

/** 挂载资源项（会话响应回显，snake_case 按类型取用；仓库令牌 / 密码只写不读、不回显）。 */
export interface SessionResourceDto {
  id?: string;
  type: string;
  file_id?: string;
  /** file：挂载路径（工作区相对路径，mounts/<file_id> 形态，沙箱内可见 /workspace/mount_path、只读） */
  mount_path?: string;
  url?: string;
  checkout?: string;
  memory_store_id?: string;
  access?: string;
  instructions?: string;
}

/**
 * 会话 DTO（对齐后端 SessionResponse，字段不多不少）。
 * <p>MUST NOT 出现 turn_status（或任何轮次相位字段）/ agent_id / memory_store_ids /
 * environment_variables / trigger_type / trigger_id / stats / usage / outcome_evaluations。</p>
 */
export interface SessionDto {
  id: string;
  type: string;
  /** 完整冻结 Agent 快照（含只读 model.effective_context_window，无 audit / metadata 字段）。 */
  agent: Record<string, unknown>;
  environment_id: string;
  status: SessionStatus;
  title: string | null;
  metadata: Record<string, unknown>;
  resources: SessionResourceDto[];
  vault_ids: string[] | null;
  deployment_id: string | null;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 会话游标分页响应（对齐后端 CursorPage，创建时间降序、不提供 total）。 */
export type SessionListResult = CursorPageResponse<SessionDto>;

/** 会话列表游标查询参数（statuses 为对外四态词汇；metadata 为键值包含过滤 JSON 文本）。 */
export interface ListSessionsOptions {
  agentId?: string;
  agentVersion?: number;
  deploymentId?: string;
  memoryStoreId?: string;
  statuses?: SessionStatus[];
  metadata?: string;
  includeArchived?: boolean;
  order?: 'asc' | 'desc';
  limit?: number;
  page?: string;
  afterId?: string;
  beforeId?: string;
}

/** 挂载资源提交项（扁平结构按类型取用字段，对齐后端数组形态资源项）。 */
export interface SessionResourceInput {
  type: 'file' | 'github_repository' | 'git_repository' | 'memory_store';
  /** file：已上传文件业务 ID（前缀 file_） */
  file_id?: string;
  /** file：挂载路径（可空，缺省自动补 mounts/&lt;file_id&gt;；自定义必须为 mounts/ 前缀的工作区相对路径，不得含 ..，沙箱内只读） */
  mount_path?: string;
  /** github_repository / git_repository：仓库地址（git_repository 时用户名须体现在 URL 中） */
  url?: string;
  /** github_repository：访问令牌（只写不读） */
  authorization_token?: string;
  /** git_repository：密码（只写不读，与 URL 中用户名成对） */
  password?: string;
  /** 仓库资源：检出分支 / 标签 */
  checkout?: string;
  /** memory_store：记忆库业务 ID（前缀 ms_） */
  memory_store_id?: string;
  /** memory_store：访问模式 */
  access?: 'read_write' | 'read_only';
  /** memory_store：挂载级附加指令 */
  instructions?: string;
}

/** 创建会话入参（agent 必填，服务端锁定其最新激活版本；userId 由 JWT 注入，前端不传）。 */
export interface CreateSessionPayload {
  agent: string;
  environment_id: string;
  title?: string | null;
  resources?: SessionResourceInput[] | null;
  vault_ids?: string[] | null;
  metadata?: Record<string, unknown> | null;
  environment_variables?: Record<string, string> | null;
}

/** 追加挂载资源项响应（对齐 SessionResourceResponse）。 */
export interface SessionResourceResponseDto {
  id: string;
  type: string;
  file_id: string;
  /** file：挂载路径（工作区相对路径，恒为 mounts/ 前缀；沙箱内可见 /workspace/mount_path、只读） */
  mount_path: string;
  created_at: string;
  updated_at: string;
}

/** 取消回执（活跃 turn → 202、idle/terminated 幂等空操作 → 200，两种 2xx 响应体相同）。 */
export interface SessionCancelResult {
  id: string;
  type: string;
  /** 响应固定值 canceling（非持久化状态取值） */
  status: string;
}

/** 删除会话响应（对齐后端 SessionDeletedResponse）。 */
export interface SessionDeletedResult {
  id: string;
  type: string;
}

/** 扁平事件游标分页响应（对齐 CursorPage，事件 evt_ ID 作为所有分页游标）。 */
export type EventListResult = CursorPageResponse<EventEnvelope>;

// ==================== Session REST 接口 ====================

/** 创建会话（绑定 Agent 最新版本快照与运行环境）。 */
export function createSession(payload: CreateSessionPayload): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 游标分页列出会话（创建时间降序；agent_id / deployment_id / statuses[] 等过滤 + after_id/before_id 续传）。 */
export function listSessions(options?: ListSessionsOptions): Promise<SessionListResult> {
  const query = new URLSearchParams();
  if (options?.agentId) {
    query.set('agent_id', options.agentId);
  }
  if (options?.agentVersion !== undefined) {
    query.set('agent_version', String(options.agentVersion));
  }
  if (options?.deploymentId) {
    query.set('deployment_id', options.deploymentId);
  }
  if (options?.memoryStoreId) {
    query.set('memory_store_id', options.memoryStoreId);
  }
  for (const status of options?.statuses ?? []) {
    query.append('statuses[]', status);
  }
  if (options?.metadata) {
    query.set('metadata', options.metadata);
  }
  if (options?.includeArchived) {
    query.set('include_archived', 'true');
  }
  if (options?.order) {
    query.set('order', options.order);
  }
  if (options?.limit) {
    query.set('limit', String(options.limit));
  }
  if (options?.page) {
    query.set('page', options.page);
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const suffix = query.toString() ? `?${query}` : '';
  return fetchJson<SessionListResult>(`${API_BASE}/sessions${suffix}`);
}

/** 会话详情。 */
export function getSession(sessionId: string): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}`);
}

/** 更新会话（title 省略不改、null 清空；metadata 浅合并；environment_variables 整体替换；agent / status 不可改）。 */
export function updateSession(
  sessionId: string,
  patch: {
    title?: string | null;
    metadata?: Record<string, unknown> | null;
    environment_variables?: Record<string, string> | null;
  },
): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
}

/** 归档会话（仅写 archived_at、status 保持原值；存在活跃 turn 先按取消语义中止收敛）。 */
export function archiveSession(sessionId: string): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/archive`, {
    method: 'POST',
  });
}

/** 创建后追加挂载资源（本期仅 file 类型；重复挂载 / 未就绪 / 归档终止 → 409）。 */
export function appendSessionResources(
  sessionId: string,
  resources: SessionResourceInput[],
): Promise<SessionResourceResponseDto[]> {
  return fetchJson<SessionResourceResponseDto[]>(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/resources`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resources }),
    },
  );
}

/** 取消当前执行（活跃 turn → 202、idle/terminated 幂等空操作 → 200，两者响应体相同）。 */
export function cancelSession(sessionId: string): Promise<SessionCancelResult> {
  return fetchJson<SessionCancelResult>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/cancel`, {
    method: 'POST',
  });
}

/** 删除会话（删除事务生效前向在线订阅者推送 session.deleted，随后关闭连接）。 */
export function deleteSession(sessionId: string): Promise<SessionDeletedResult> {
  return fetchJson<SessionDeletedResult>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
}

// ==================== Event 接口 ====================

/** 入站事件项（类型自有字段直接位于顶层，MUST NOT 使用嵌套 payload 包装）。 */
export type InboundEventInput = { type: string } & Record<string, unknown>;

/**
 * 投递入站事件（POST .../events，请求体 {"events":[...]}，成功回显已落库扁平事件数组）。
 * <p>user.message 驱动本轮 turn；实时渲染依赖事件流广播，本返回值用于立即回显。</p>
 */
export function sendInboundEvents(
  sessionId: string,
  events: InboundEventInput[],
): Promise<EventEnvelope[]> {
  return fetchJson<EventEnvelope[]>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ events }),
  });
}

/**
 * 发送用户消息（user.message 事件，驱动 Agent 执行本轮）。
 * <p>入站契约：content MUST 为非空 ContentBlock 数组（纯文本 / 字符串形态被拒 400），
 * 本函数将文本包装为单个 text 块。</p>
 */
export function sendUserMessage(sessionId: string, text: string): Promise<EventEnvelope[]> {
  return sendInboundEvents(sessionId, [{
    type: CHAT_EVENT_TYPES.USER_MESSAGE,
    content: [{ type: 'text', text }],
  }]);
}

/** 中断进行中的 turn（user.interrupt → 回 idle，stop_reason=interrupted，不产生 cancelled 终态）。 */
export function sendUserInterrupt(sessionId: string): Promise<EventEnvelope[]> {
  return sendInboundEvents(sessionId, [{ type: CHAT_EVENT_TYPES.USER_INTERRUPT }]);
}

/**
 * 人工确认指令（user.tool_confirmation 事件，durable HITL 唯一确认入口）。
 * <p>allow 放行工具执行并在同一轮续跑；deny 注入被拒工具结果后续跑（不终止 turn）。
 * 锚点 {@code toolUseId} MUST 为待确认工具事件的公开 evt_ ID；越界锚点 404、
 * 重复确认 409，均无副作用。续跑全程无状态迁移事件，对外状态保持 running。</p>
 */
export function sendUserToolConfirmation(
  sessionId: string,
  toolUseId: string,
  result: 'allow' | 'deny',
  denyMessage?: string | null,
): Promise<EventEnvelope[]> {
  const event: InboundEventInput = {
    type: CHAT_EVENT_TYPES.USER_TOOL_CONFIRMATION,
    tool_use_id: toolUseId,
    result,
  };
  if (result === 'deny' && denyMessage) {
    event.deny_message = denyMessage;
  }
  return sendInboundEvents(sessionId, [event]);
}

/**
 * 事件历史回放（扁平 Event 游标分页）。
 * <p>{@code limit} 默认 20、范围 1–100；{@code page} 为响应 next_page 回传的不透明游标
 * （与 after_id / before_id 互斥）；{@code after_id} / {@code before_id} 为 evt_ ID 定位；
 * {@code order} 取 asc（缺省）/ desc；{@code types} 无法识别的类型静默忽略。</p>
 */
export function listEvents(sessionId: string, options?: {
  limit?: number;
  page?: string;
  afterId?: string;
  beforeId?: string;
  order?: 'asc' | 'desc';
  types?: string[];
}): Promise<EventListResult> {
  const query = new URLSearchParams();
  if (options?.limit) {
    query.set('limit', String(options.limit));
  }
  if (options?.page) {
    query.set('page', options.page);
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  if (options?.order) {
    query.set('order', options.order);
  }
  for (const type of options?.types ?? []) {
    query.append('types', type);
  }
  const suffix = query.toString() ? `?${query}` : '';
  return fetchJson<EventListResult>(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events${suffix}`,
  );
}

/** 流式增量帧可协商的事件类型（连接级协商，值域仅这两项；非法值服务端 400）。 */
export const EVENT_DELTA_TYPES = ['agent.message', 'agent.thinking'] as const;

/**
 * 实时事件订阅句柄（close 语义兼容原 EventSource，调用侧仅 `.close()` 用法不变）。
 */
export interface EventStreamHandle {
  /** 主动关闭订阅：abort 在途 fetch 并取消待重连定时器。 */
  close(): void;
}

/** 重连指数退避起点毫秒。 */
const STREAM_RECONNECT_BASE_MS = 500;
/** 重连指数退避上限毫秒（消除 EventSource onerror「连接中断」风暴路径）。 */
const STREAM_RECONNECT_MAX_MS = 5000;

/** SSE 帧解析结果。 */
interface SseFrame {
  event?: string;
  id?: string;
  data?: string;
}

/** 解析单帧 SSE 文本（多行 data 以 \n 拼接；「:」注释行（含 : heartbeat / : connected）忽略；行尾已在读取侧统一为 \n）。 */
function parseSseFrame(block: string): SseFrame {
  const dataLines: string[] = [];
  let event: string | undefined;
  let id: string | undefined;
  let hasData = false;
  for (const line of block.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'data') {
      hasData = true;
      dataLines.push(value);
    } else if (field === 'event') {
      event = value;
    } else if (field === 'id') {
      id = value;
    }
  }
  return { event, id, data: hasData ? dataLines.join('\n') : undefined };
}

/**
 * 打开实时事件订阅流（fetch-stream 实现：EventSource 无法携带 Authorization 头，改走 fetch + ReadableStream）。
 * <p>服务端下发 {@code : connected} 后按 Last-Event-ID（evt_ 事件 ID 游标，本层从帧 id 字段自行跟踪）
 * 续推历史 + 实时订阅；buffered 帧 {@code event} 名 = 扁平事件 type，仅派发
 * {@code STREAM_EVENT_TYPES} 登记的类型；上层继续按事件 id 幂等去重，重连回放重复帧天然消化。
 * 空闲期服务端下发 {@code : heartbeat} 注释行，本层忽略。</p>
 * <p>增量帧（{@code event_start} / {@code event_delta}）仅在连接协商 {@code event_deltas[]}
 * 后下发，经 {@code onStreamFrame} 回调分派；帧与最终 buffered 事件共用同一 evt_ ID
 * （SSE id 亦为该 ID，故断点游标语义不变）。</p>
 * <p>断线 / 服务端 SSE 超时正常收尾均由本层指数退避（500ms 起、上限 5s）自重连；
 * 401 经统一处置清会话跳登录，401/403/404 视为致命不再重连。断线 MUST NOT 触发 turn 取消。</p>
 */
export function openEventStream(
  sessionId: string,
  onEvent: (envelope: EventEnvelope) => void,
  onConnectionError?: (message: string) => void,
  options?: {
    /** 协商增量帧的事件类型（如 ['agent.message']；空/缺省 = 不协商） */
    eventDeltas?: string[];
    /** 增量帧聚合刷写间隔毫秒（缺省服务端 50ms） */
    deltaFlushIntervalMs?: number;
    /** 增量帧回调（event_start / event_delta；缺省 = 丢弃增量帧，仅用 buffered 完整事件） */
    onStreamFrame?: (frame: StreamFrame) => void;
  },
): EventStreamHandle {
  const query = new URLSearchParams();
  for (const type of options?.eventDeltas ?? []) {
    if ((EVENT_DELTA_TYPES as readonly string[]).includes(type)) {
      query.append('event_deltas[]', type);
    }
  }
  if (options?.deltaFlushIntervalMs !== undefined) {
    query.set('delta_flush_interval_ms', String(options.deltaFlushIntervalMs));
  }
  const suffix = query.toString() ? `?${query}` : '';
  const url = `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events/stream${suffix}`;

  let closed = false;
  let controller: AbortController | null = null;
  let reconnectTimer: number | null = null;
  let attempt = 0;
  /** 断点游标：本连接收到的最后一条带 id 帧（evt_ 前缀事件 ID），重连经 Last-Event-ID 头回传。 */
  let lastEventId = '';

  const dispatchFrame = (block: string): void => {
    const frame = parseSseFrame(block);
    if (frame.id) {
      lastEventId = frame.id;
    }
    if (frame.data === undefined || !frame.event) {
      return;
    }
    if (frame.event === CHAT_EVENT_TYPES.EVENT_START || frame.event === CHAT_EVENT_TYPES.EVENT_DELTA) {
      const streamFrame = parseStreamFrame(frame.data);
      if (streamFrame) {
        options?.onStreamFrame?.(streamFrame);
      }
      return;
    }
    if (!STREAM_EVENT_TYPES.includes(frame.event)) {
      return;
    }
    onEvent(parseEventEnvelope(frame.data));
  };

  const scheduleReconnect = (): void => {
    if (closed || reconnectTimer !== null) {
      return;
    }
    const delay = Math.min(STREAM_RECONNECT_BASE_MS * 2 ** attempt, STREAM_RECONNECT_MAX_MS);
    attempt += 1;
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      void connect();
    }, delay);
  };

  const connect = async (): Promise<void> => {
    if (closed) {
      return;
    }
    controller = new AbortController();
    const active = controller;
    const headers: Record<string, string> = { Accept: 'text/event-stream' };
    if (lastEventId) {
      headers['Last-Event-ID'] = lastEventId;
    }
    try {
      const response = await fetch(url, { headers: authHeaders(headers), signal: active.signal });
      if (!response.ok) {
        // 401 → http 层统一清会话跳登录；鉴权失败 / 会话不存在视为致命，不重连
        interceptUnauthorized(url, response.status);
        onConnectionError?.(`事件订阅失败(${response.status})`);
        if (response.status !== 401 && response.status !== 403 && response.status !== 404) {
          scheduleReconnect();
        }
        return;
      }
      if (!response.body) {
        throw new Error('事件订阅响应缺少字节流');
      }
      attempt = 0;
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        // SSE 允许 CRLF 行尾：按块归一为 LF 后再做帧切分（空行分帧）
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        let separator = buffer.indexOf('\n\n');
        while (separator !== -1) {
          dispatchFrame(buffer.slice(0, separator));
          buffer = buffer.slice(separator + 2);
          separator = buffer.indexOf('\n\n');
        }
      }
      // 服务端正常收尾（如 SSE 超时断连）：本地未关闭则携游标重连继续订阅
      if (!closed) {
        scheduleReconnect();
      }
    } catch (err) {
      // close() 触发的 abort 静默退出；其余异常退避重连
      if (closed || active.signal.aborted) {
        return;
      }
      onConnectionError?.(`事件订阅连接中断，正在重试…: ${(err as Error).message}`);
      scheduleReconnect();
    }
  };

  void connect();

  return {
    close(): void {
      closed = true;
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      controller?.abort();
      controller = null;
    },
  };
}

// ==================== 解析工具 ====================

/** 解析 SSE data 为扁平事件对象。 */
export function parseEventEnvelope(data: string): EventEnvelope {
  return JSON.parse(data) as EventEnvelope;
}

/** 解析流式增量帧 data；形状不符（缺 type / 载荷不全）返回 null 静默丢弃。 */
export function parseStreamFrame(data: string): StreamFrame | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') {
    return null;
  }
  const frame = parsed as Record<string, unknown>;
  if (frame.type === 'event_start') {
    const event = frame.event as Record<string, unknown> | undefined;
    if (!event || typeof event.id !== 'string' || typeof event.type !== 'string') {
      return null;
    }
    return { type: 'event_start', event: { id: event.id, type: event.type } };
  }
  if (frame.type === 'event_delta') {
    if (typeof frame.event_id !== 'string' || !frame.delta || typeof frame.delta !== 'object') {
      return null;
    }
    return {
      type: 'event_delta',
      event_id: frame.event_id,
      delta: frame.delta as ContentDelta,
    };
  }
  return null;
}