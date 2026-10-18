import { get, post, del, buildQuery, authHeaders } from '@/shared/api/http';
import { ApiError } from '@/shared/types/api';
import type { ApiResponse, CursorPage, ErrorEnvelope } from '@/shared/types/api';
import type {
  ChatEventWire,
  CreateSessionPayload,
  OutboundEvent,
  SessionResourceWire,
  SessionWire,
} from '../types';
import { buildSessionListQuery } from '../mappers';
import type { SessionListFilter, SessionListPageParams } from '../mappers';

/**
 * 会话端点（后端 AgentSessionController + AgentChatEventController，云资源域 snake_case）。
 * <p>契约要点（与后端代码逐字段核对）：</p>
 * <ul>
 *   <li>前缀 {@code /api/v1/cloud/sessions}，列表为游标分页（{@code data/first_id/last_id/has_more/next_page}，无 total）；</li>
 *   <li>列表参数真实名：{@code agent_id / agent_version / deployment_id / statuses[]（可重复）/
 *       include_archived / created_at[gt|gte|lt|lte] / order / limit / page / after_id / before_id}；
 *       {@code environment_id} 与 {@code metadata} 非受支持参数，MUST NOT 提交；</li>
 *   <li>创建 {@code POST}（{@code agent} 支持字符串 ID 或 {@code {id,type:"agent",version?}} 对象，
 *       版本省略 = 服务端解析激活版本）；已废止字段 {@code environment / vaults / vault_ids /
 *       memory_store_ids / delta_flush_interval_ms} 任一非空即 400，本模块绝不提交；</li>
 *   <li>{@code POST /{id}} 更新（title / metadata / environment_variables）；显式提交
 *       {@code agent / environment_id / status} 非空值 → 400；</li>
 *   <li>{@code POST /{id}/cancel} 存在活跃 turn → HTTP 202、无活跃 turn → HTTP 200（幂等空操作），
 *       两种 2xx 响应体完全相同（固定回执 {@code {id,type:"session",status:"canceling"}}），
 *       故 MUST 以真实 HTTP 状态码判别受理形态；</li>
 *   <li>响应 MUST NOT 回显 {@code environment_variables}（仅入参接受），前端不得假设有回显。</li>
 * </ul>
 */

/** 会话端点前缀。 */
const BASE = '/api/v1/cloud/sessions';

/** 智能体端点前缀（下拉选项数据源）。 */
const AGENTS_BASE = '/api/v1/cloud/agents';

/** 智能体轻量列表拉取条数上限（下拉一次性取活跃智能体，MVP 不做翻页）。 */
const AGENT_LITE_LIMIT = 100;

/** 会话资源挂载项入参（本期仅 file 类型，与创建载荷资源项同形）。 */
export type SessionResourceInput = NonNullable<CreateSessionPayload['resources']>[number];

/** 取消会话受理结果（HTTP 状态双态）。 */
export interface SessionCancelResult {
  /** 真实 HTTP 状态码（200 = 无活跃 turn 的幂等空操作；202 = 已投递取消） */
  httpStatus: number;
  /** 是否为"取消中"受理（202） */
  accepted: boolean;
  /** 会话 ID */
  id: string;
  /** 固定回执状态（恒 canceling，非持久化会话状态） */
  status: string;
}

/** 智能体轻量项（列表筛选与创建下拉仅需 ID 与名称）。 */
export interface AgentLite {
  /** 智能体业务 ID */
  id: string;
  /** 智能体名称 */
  name: string;
}

/** 会话事件列表查询参数（对齐 AgentChatEventController#listEvents）。 */
export interface ListEventsParams {
  /** 每页条数（1-100，缺省 20） */
  limit?: number;
  /** 排序方向（缺省 asc） */
  order?: 'asc' | 'desc';
  /** 事件类型过滤（可重复参数 types；无法识别的类型后端静默忽略） */
  types?: string[];
  /** 向后翻页游标（evt_ 事件 ID） */
  after_id?: string;
  /** 向前翻页游标（evt_ 事件 ID） */
  before_id?: string;
}

/** 游标翻页参数（资源列表）。 */
export interface ResourcePageParams {
  /** 每页条数 */
  limit?: number;
  /** 向后翻页游标（sesr_ 资源 ID） */
  after_id?: string;
  /** 向前翻页游标 */
  before_id?: string;
}

/**
 * 游标分页列出会话（创建时间降序缺省）。
 * @param filter UI 筛选条件（智能体 / 状态多选 / 含归档 / 创建时间范围）
 * @param page 游标翻页参数
 * @returns 游标分页结果
 */
export function listSessions(
  filter: SessionListFilter = {},
  page: SessionListPageParams = {},
): Promise<CursorPage<SessionWire>> {
  return get<CursorPage<SessionWire>>(`${BASE}${buildSessionListQuery(filter, page)}`);
}

/**
 * 会话详情（含创建时冻结的智能体快照与已挂载资源）。
 * @param sessionId 会话业务 ID
 * @returns 会话对象
 */
export function getSession(sessionId: string): Promise<SessionWire> {
  return get<SessionWire>(`${BASE}/${encodeURIComponent(sessionId)}`);
}

/**
 * 创建会话（绑定智能体与 cloud 运行环境，可同时挂载文件与写入环境变量）。
 * @param payload 创建载荷（废止字段不得出现）
 * @returns 新建会话对象
 */
export function createSession(payload: CreateSessionPayload): Promise<SessionWire> {
  return post<SessionWire>(BASE, payload);
}

/**
 * 更新会话标题（{@code POST /{id}}，title 传空串按清空语义由后端处理）。
 * @param sessionId 会话业务 ID
 * @param title 新标题
 * @returns 更新后的会话对象
 */
export function updateSessionTitle(sessionId: string, title: string): Promise<SessionWire> {
  return post<SessionWire>(`${BASE}/${encodeURIComponent(sessionId)}`, { title });
}

/**
 * 归档会话（仅写 archived_at，并中断运行中的执行、释放订阅与租约）。
 * @param sessionId 会话业务 ID
 * @returns 归档后的会话对象
 */
export function archiveSession(sessionId: string): Promise<SessionWire> {
  return post<SessionWire>(`${BASE}/${encodeURIComponent(sessionId)}/archive`);
}

/**
 * 取消当前执行（200 / 202 双态）。
 * <p>基座 {@code requestEnvelope} 只回传响应体（{@code ApiResponse.code} 恒为 "200"，
 * 无法区分 200 与 202），故此处与上传封装同样直连 fetch 层，以真实 HTTP 状态码判定受理形态；
 * 非 2xx 归一为 ApiError，语义与基座一致。</p>
 * @param sessionId 会话业务 ID
 * @returns 受理结果（accepted=true 表示 HTTP 202 取消中）
 */
export async function cancelSession(sessionId: string): Promise<SessionCancelResult> {
  const path = `${BASE}/${encodeURIComponent(sessionId)}/cancel`;
  const response = await fetch(path, { method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }) });
  if (!response.ok) {
    throw await toApiError(response);
  }
  const wrapper = (await response.json()) as ApiResponse<{ id?: string; status?: string }>;
  return {
    httpStatus: response.status,
    accepted: response.status === 202,
    id: wrapper.data?.id ?? sessionId,
    status: wrapper.data?.status ?? 'canceling',
  };
}

/**
 * 删除会话（逻辑删除：会话与其历史事件流一并清理，仍活跃的执行先取消）。
 * @param sessionId 会话业务 ID
 */
export function deleteSession(sessionId: string): Promise<void> {
  return del<void>(`${BASE}/${encodeURIComponent(sessionId)}`);
}

/**
 * 游标分页列出已挂载资源。
 * @param sessionId 会话业务 ID
 * @param page 游标翻页参数
 * @returns 资源游标分页结果
 */
export function listResources(sessionId: string, page: ResourcePageParams = {}): Promise<CursorPage<SessionResourceWire>> {
  const query = buildQuery({ limit: page.limit, after_id: page.after_id, before_id: page.before_id });
  return get<CursorPage<SessionResourceWire>>(`${BASE}/${encodeURIComponent(sessionId)}/resources${query}`);
}

/**
 * 创建后追加挂载文件（请求体仅接受 {@code {"resources":[...]}} 数组结构，长度 ≥1）。
 * @param sessionId 会话业务 ID
 * @param resources 待挂载文件项
 * @returns 已追加的资源列表
 */
export function addResources(sessionId: string, resources: SessionResourceInput[]): Promise<SessionResourceWire[]> {
  return post<SessionResourceWire[]>(`${BASE}/${encodeURIComponent(sessionId)}/resources`, { resources });
}

/**
 * 移除单个挂载资源（本期仅 file，一律可移除）。
 * @param sessionId 会话业务 ID
 * @param resourceId 资源业务 ID（sesr_ 前缀）
 */
export function removeResource(sessionId: string, resourceId: string): Promise<void> {
  return del<void>(`${BASE}/${encodeURIComponent(sessionId)}/resources/${encodeURIComponent(resourceId)}`);
}

/**
 * 发送入站事件（扁平结构，禁止 payload 嵌套；HTTP 成功仅表示事件已落库）。
 * @param sessionId 会话业务 ID
 * @param events 入站事件（user.message / user.interrupt / user.tool_confirmation / user.tool_result）
 * @returns 已落库事件（顺序与提交一致）
 */
export function sendEvents(sessionId: string, events: OutboundEvent[]): Promise<ChatEventWire[]> {
  return post<ChatEventWire[]>(`${BASE}/${encodeURIComponent(sessionId)}/events`, { events });
}

/**
 * 发送一条纯文本用户消息（创建会话后补发初始消息、工作区发送均复用）。
 * @param sessionId 会话业务 ID
 * @param text 消息文本
 * @returns 已落库事件
 */
export function sendUserMessage(sessionId: string, text: string): Promise<ChatEventWire[]> {
  return sendEvents(sessionId, [{ type: 'user.message', content: [{ type: 'text', text }] }]);
}

/**
 * 游标分页列出会话事件历史（工作区进入时按 order=asc 回放）。
 * @param sessionId 会话业务 ID
 * @param params 过滤与翻页参数
 * @returns 事件游标分页结果
 */
export function listEvents(sessionId: string, params: ListEventsParams = {}): Promise<CursorPage<ChatEventWire>> {
  const search = new URLSearchParams();
  if (params.limit !== undefined && params.limit > 0) {
    search.set('limit', String(params.limit));
  }
  if (params.order) {
    search.set('order', params.order);
  }
  if (params.after_id) {
    search.set('after_id', params.after_id);
  }
  if (params.before_id) {
    search.set('before_id', params.before_id);
  }
  // types 为可重复参数，buildQuery 的同键覆盖语义不适用，逐个 append
  for (const type of params.types ?? []) {
    if (type) {
      search.append('types', type);
    }
  }
  const query = search.toString();
  return get<CursorPage<ChatEventWire>>(
    `${BASE}/${encodeURIComponent(sessionId)}/events${query ? `?${query}` : ''}`,
  );
}

/**
 * 拉取活跃智能体轻量列表（创建会话与列表筛选的下拉数据源）。
 * <p>本模块内自带实现（{@code GET /api/v1/cloud/agents?status=active&limit=100}），
 * 避免与 agent 模块产生跨模块依赖；MVP 一次性取前 100 条不做翻页。</p>
 * @returns 智能体 ID 与名称列表（缺失 ID 的脏数据项被丢弃）
 */
export async function listActiveAgentsLite(): Promise<AgentLite[]> {
  const query = buildQuery({ status: 'active', limit: AGENT_LITE_LIMIT });
  const page = await get<CursorPage<Partial<AgentLite>>>(`${AGENTS_BASE}${query}`);
  return (page.data ?? [])
    .map((item) => ({ id: item.id ?? '', name: item.name ?? '' }))
    .filter((item) => item.id);
}

/** 非 2xx 响应 → ApiError（信封解析失败回落状态码 + 原文截断，与基座同语义）。 */
async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '');
  try {
    const envelope = JSON.parse(text) as ErrorEnvelope;
    if (envelope?.error?.message) {
      return new ApiError(response.status, envelope.error.type ?? '', envelope.error.message, envelope.request_id ?? '');
    }
  } catch {
    // 非信封形态（网关纯文本错误等）走回落
  }
  return new ApiError(response.status, '', `请求失败(${response.status}): ${text.slice(0, 200)}`);
}
