/**
 * Agent 运行时接口层（REST + SSE）。
 * <p>会话管理走统一 ApiResponse 包装，消息发送采用 {@code POST /sessions/{sessionId}/events}
 * （Accept: text/event-stream）直连 SSE，事件载荷为统一 {@code Message} 信封（SseEventEnvelope）。</p>
 */
import { fetchJson, type PaginatedResponse } from '@/shared/api/http';

// ==================== 类型定义 ====================

/** 聊天事件信封 DTO（与后端 SseEventEnvelope 对齐的 Message 信封结构）。 */
export interface ChatEventDto {
  object: string;
  id: string;
  created_at: string;
  role: string;
  type: string;
  content: ContentBlock[];
  metadata: Record<string, unknown>;
  status: string;
  sequence_number: number;
}

/** 会话 DTO。 */
export interface SessionDto {
  sessionId: string;
  userId: string;
  agentId: string;
  agentVersion: string;
  status: string;
  metadata: string | null;
  sandboxId: string | null;
  title: string | null;
  lastActiveAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 执行轮次 DTO。 */
export interface RoundDto {
  roundId: string;
  sessionId: string;
  runId: string;
  roundNumber: number;
  input: string;
  output: string | null;
  status: string;
  replayedFromRoundId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 发送消息回执（202 / SSE 直连）。 */
export interface SendMessageResult {
  roundId: string | null;
  runId: string | null;
  stopReason: string | null;
}

/** SSE 事件行（event name + data）。 */
export interface SseEventLine {
  event: string;
  data: string;
}

/** content-blocks 块（text/thinking/tool_call/tool_result/data，字段 snake_case 对齐后端）。 */
export interface ContentBlock {
  type: string;
  block_id?: string;
  tool_call_id?: string;
  name?: string;
  text?: string;
  input?: Record<string, unknown>;
  output?: string;
  truncated?: boolean;
  data?: Record<string, unknown>;
}

/** 聊天事件类型（与后端 ChatEventType 小写枚举名对齐）。 */
export const CHAT_EVENT_TYPES = {
  RUN_START: 'run_start',
  THINKING: 'thinking',
  MESSAGE: 'message',
  TOOL_CALL: 'tool_call',
  TOOL_CALL_OUTPUT: 'tool_call_output',
  SUMMARY: 'summary',
  RUN_END: 'run_end',
  RUN_ERROR: 'run_error',
  SESSION_STATUS: 'session_status',
  ERROR: 'error',
  AGENT_PROGRESS: 'agent_progress',
  EXCEED_MAX_ITERS: 'exceed_max_iters',
} as const;

/** 默认演示身份（当前无用户体系，落地后替换为真实身份）。 */
export const DEMO_USER_ID = 'demo-user';

/** Agent REST 基路径（v1 版本化，后端路径前缀 /api/v1/agent）。 */
export const API_BASE = '/api/v1/agent';

// ==================== REST 接口 ====================

/** 会话创建入参（Agent 与发布号必填，取代旧 demo 硬编码）。 */
export interface CreateSessionOptions {
  agentId: string;
  /** 发布号（十进制字符串，如 "1"）；从 Agent 版本列表选择。 */
  agentVersion: string;
  title?: string;
  metadata?: string;
}

/** 创建会话。 */
export function createSession(options: CreateSessionOptions): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userId: DEMO_USER_ID,
      agentId: options.agentId,
      agentVersion: options.agentVersion,
      title: options.title ?? null,
      metadata: options.metadata ?? null,
    }),
  });
}

/** 分页查询会话。 */
export function listSessions(userId = DEMO_USER_ID, page = 1, size = 20): Promise<PaginatedResponse<SessionDto>> {
  const query = new URLSearchParams({ userId, page: String(page), size: String(size) });
  return fetchJson<PaginatedResponse<SessionDto>>(`${API_BASE}/sessions?${query}`);
}

/** 会话详情。 */
export function getSession(sessionId: string): Promise<SessionDto> {
  return fetchJson<SessionDto>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}`);
}

/** 终止会话。 */
export async function terminateSession(sessionId: string): Promise<void> {
  await fetchJson<string>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
}

/** 轮次列表。 */
export function listRounds(sessionId: string): Promise<RoundDto[]> {
  return fetchJson<RoundDto[]>(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/rounds`);
}

/** 单轮事件回放。 */
export function roundEvents(sessionId: string, roundId: string): Promise<ChatEventDto[]> {
  return fetchJson<ChatEventDto[]>(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/rounds/${encodeURIComponent(roundId)}/events`,
  );
}

// ==================== SSE 接口 ====================

/** 发送消息（SSE 直连）：消费本轮完整事件流，逐事件回调。 */
export async function sendMessageStream(
  sessionId: string,
  content: string,
  onEvent: (line: SseEventLine) => void,
): Promise<void> {
  const response = await fetch(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify({ type: 'message', content }),
    },
  );
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`发送消息失败(${response.status}): ${text.slice(0, 200)}`);
  }
  const body = response.body;
  if (!body) {
    throw new Error('当前浏览器不支持响应流读取');
  }
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let separatorIndex = buffer.indexOf('\n\n');
    while (separatorIndex >= 0) {
      const block = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      const line = parseSseBlock(block);
      if (line !== null) {
        onEvent(line);
      }
      separatorIndex = buffer.indexOf('\n\n');
    }
  }
}

/** 打开实时订阅流（回放 after_sequence_num 之后事件 + 实时订阅）。 */
export function openEventStream(
  sessionId: string,
  afterSequenceNum = 0,
  onError?: (error: string) => void,
): EventSource {
  const query = new URLSearchParams({ after_sequence_num: String(afterSequenceNum) });
  const source = new EventSource(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events/stream?${query}`);
  source.onerror = () => onError?.('事件订阅连接中断，正在重试…');
  return source;
}

// ==================== 解析工具 ====================

/**
 * 解析单条 SSE 块（可能多条 {@code data:} 行）。
 * 返回 {@code event}（缺省 message）与拼接后的 {@code data}；无数据返回 null。
 */
export function parseSseBlock(block: string): SseEventLine | null {
  let event = 'message';
  let data = '';
  for (const rawLine of block.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data += line.slice(5).replace(/^ /, '');
    }
  }
  return data ? { event, data } : null;
}

/** 解析 SSE data 为聊天事件信封 DTO。 */
export function parseChatEvent(data: string): ChatEventDto {
  return JSON.parse(data) as ChatEventDto;
}