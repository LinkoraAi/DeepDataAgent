/**
 * Agent 定义与版本管理接口模块（对齐后端 AgentController：/api/v1/cloud/agents）。
 * <p>「配置即版本」：创建与发布版本共用同一请求体，创建即产生 v1；发布为全量替换，
 * 未提交字段视为清空并固化进新版本快照。</p>
 * <p>列表与版本历史遵循 Cursor 约定（limit/after_id/before_id →
 * {data, first_id, last_id, has_more}，创建时间/发布号降序，不提供 total）。</p>
 * <p>模型引用对齐系统模型目录：字符串简写（目录模型 id）或对象形态
 * {@code {id, effort?, context_window?}}，响应按提交形态回显；
 * 内部供应商映射（modelProfileId）不在对外契约出现。</p>
 * <p>结构化字段（tools / mcp_servers / skills / metadata）请求与响应均为结构化 JSON 值，
 * 未配置时响应回落空数组 / 空对象；AGENTS.md（agents_md）已废止，提交即 400。</p>
 */
import { fetchJson, type CursorPageResponse } from '@/shared/api/http';
import type { ModelEffort } from './cloud-models';
import type { SkillBinding } from './skills';

/** 模型引用对象形态（effort / context_window 须在该目录模型支持档位内）。 */
export interface CloudModelRef {
  id: string;
  effort?: ModelEffort;
  context_window?: number;
}

/** 模型引用：目录模型 id 字符串简写，或对象形态（两种提交形态等价）。 */
export type ModelRef = string | CloudModelRef;

/** 内置工具集类型（enabled_tools / disallowed_tools / configs）。 */
export const AGENT_TOOLSET_TYPE = 'agent_toolset_20260401';
/** 浏览器工具集类型（本期不实现，提交即 400）。 */
export const BROWSER_TOOLSET_TYPE = 'browser_toolset_20260714';
/** MCP 工具集类型（引用 mcp_servers[].name）。 */
export const MCP_TOOLSET_TYPE = 'mcp_toolset';
/** 自定义工具类型。 */
export const CUSTOM_TOOL_TYPE = 'custom';

/** 内置工具名全集（白 / 黑名单仅可引用这 11 个名字，越界 400）。 */
export const BUILTIN_TOOL_NAMES = [
  'Bash',
  'DeliverArtifacts',
  'Edit',
  'Glob',
  'Grep',
  'ImageGen',
  'ImageSearch',
  'Read',
  'WebFetch',
  'WebSearch',
  'Write',
] as const;

/** 工具权限策略值域（空 = 平台默认）。 */
export const TOOL_PERMISSION_POLICIES = ['always_allow', 'always_ask', 'always_deny'] as const;

/** 逐工具配置项（agent_toolset / mcp_toolset 适用；custom 不支持）。 */
export interface AgentToolConfigRecipe {
  name: string;
  /** false = 隐藏并拒绝调用；省略 = 默认启用 */
  enabled?: boolean | null;
  permission_policy?: string | null;
}

/** 工具配方条目（公开四类，按 type 取用相应字段）。 */
export interface AgentToolRecipe {
  type: string;
  /** 内置工具白名单（省略 / 空数组 = 默认内置工具集，非清空） */
  enabled_tools?: string[];
  /** 内置工具隐藏 / 拒绝名单 */
  disallowed_tools?: string[];
  configs?: AgentToolConfigRecipe[];
  /** mcp_toolset 必填：须匹配某个 mcp_servers[].name */
  mcp_server_name?: string;
  /** custom 必填 */
  name?: string;
  /** custom 必填 */
  description?: string;
  /** custom 必填：JSON Schema，type 须为 "object" */
  input_schema?: Record<string, unknown>;
}

/** 内联外部 MCP 工具源配方（仅 type=url）。 */
export interface McpServerRecipe {
  name: string;
  type: string;
  url: string;
}

/**
 * Agent 对象 DTO（创建 / 列表 / 更新 / 详情与版本快照共用同一形状）。
 * <p><b>与版本快照同形</b>：{@code GET /agents/{id}?version=N} 与 {@code GET /agents/{id}/versions}
 * 返回的版本快照字段集与本接口完全一致，差别仅在 {@code version} 的取值口径——
 * Agent 对象取当前生效版本，快照取该快照自身的版本号。</p>
 * <p>归档仅以 {@code archived_at} 时间戳表达（无 archived 布尔位）；
 * {@code multiagent} 本期恒 {@code null}（词汇预留）。</p>
 */
export interface AgentDto {
  id: string;
  type: string;
  name: string;
  description: string | null;
  /** 模型引用（按提交形态回显：目录模型 id 字符串或对象形态） */
  model: ModelRef;
  /** 系统提示词（版本快照唯一指令载体） */
  system: string;
  /** 内联工具配方（未配置为空数组） */
  tools: AgentToolRecipe[];
  /** 内联外部 MCP 工具源配方（未配置为空数组） */
  mcp_servers: McpServerRecipe[];
  /** 技能绑定配方（未配置为空数组） */
  skills: SkillBinding[];
  /** 业务自定义元数据键值对象（未配置为空对象） */
  metadata: Record<string, unknown>;
  /** 多智能体编排配置（本期恒 null） */
  multiagent: Record<string, unknown> | null;
  /** 当前版本号（起始 1）；版本快照为快照自身版本号 */
  version: number;
  /** 归档时间（未归档为 null） */
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/**
 * Agent 配置请求（创建 / 发布版本共用）。
 * <p>与后端 `AgentConfigRequest` 一致：结构化字段直接提交 JSON 值（非 JSON 文本），
 * 缺省即清空并固化进新版本快照；{@code agents_md} 已废止（提交即 400），
 * 指令统一由 {@code system} 承载；非空 {@code multiagent} 同样 400（词汇预留），
 * 故请求面不提供该字段。</p>
 */
export interface AgentConfigPayload {
  name: string;
  description?: string | null;
  system?: string;
  /** 模型引用（目录模型 id 字符串简写，或 {id, effort?, context_window?} 对象形态；须存在于模型目录） */
  model: ModelRef;
  /** 内联工具配方（缺省 = 清空；browser 工具集本期 400） */
  tools?: AgentToolRecipe[] | null;
  /** 内联外部 MCP 工具源配方（缺省 = 清空；仅 type=url） */
  mcp_servers?: McpServerRecipe[] | null;
  /** 技能绑定配方（缺省 = 清空） */
  skills?: SkillBinding[] | null;
  /** 业务自定义元数据键值对象（缺省 = 清空） */
  metadata?: Record<string, unknown> | null;
}

/** Agent 更新请求（OCC：必须携带当前最新版本号，不匹配 409）。 */
export interface UpdateAgentPayload {
  name?: string;
  description?: string | null;
  version: number;
}

/** Agent 列表游标查询参数（status：active / archived；metadata 为键值包含过滤 JSON 文本）。 */
export interface ListAgentsOptions {
  keyword?: string;
  status?: 'active' | 'archived';
  metadata?: string;
  createdAfter?: string;
  createdBefore?: string;
  limit?: number;
  afterId?: string;
  beforeId?: string;
}

const AGENTS_API_BASE = '/api/v1/cloud/agents';

/** 创建 Agent（生成 v1）。 */
export function createAgent(payload: AgentConfigPayload): Promise<AgentDto> {
  return fetchJson<AgentDto>(AGENTS_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 游标分页查询 Agent 列表（创建时间降序，不提供 total）。 */
export function listAgents(options?: ListAgentsOptions): Promise<CursorPageResponse<AgentDto>> {
  const query = new URLSearchParams();
  if (options?.keyword) {
    query.set('keyword', options.keyword);
  }
  if (options?.status) {
    query.set('status', options.status);
  }
  if (options?.metadata) {
    query.set('metadata', options.metadata);
  }
  if (options?.createdAfter) {
    query.set('created_after', options.createdAfter);
  }
  if (options?.createdBefore) {
    query.set('created_before', options.createdBefore);
  }
  if (options?.limit != null) {
    query.set('limit', String(options.limit));
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const qs = query.toString();
  return fetchJson<CursorPageResponse<AgentDto>>(qs ? `${AGENTS_API_BASE}?${qs}` : AGENTS_API_BASE);
}

/** Agent 详情：传入 version 返回对应版本快照，省略返回完整 Agent 对象（配置取当前生效版本）。 */
export function getAgent(agentId: string, version?: number): Promise<AgentDto> {
  const qs = version != null ? `?version=${version}` : '';
  return fetchJson<AgentDto>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}${qs}`);
}

/** 更新 Agent 定义属性（名称/描述；OCC：version 不匹配 409，更新产生新版本快照）。 */
export function updateAgent(agentId: string, payload: UpdateAgentPayload): Promise<AgentDto> {
  return fetchJson<AgentDto>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 发布新版本快照（版本号由服务端按版本台账递增）。 */
export function publishAgentVersion(agentId: string, payload: AgentConfigPayload): Promise<AgentDto> {
  return fetchJson<AgentDto>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/versions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 游标分页查询版本历史（发布号降序，行内 version 为快照自身版本号）。 */
export function listAgentVersions(
  agentId: string,
  options?: { limit?: number; afterId?: string; beforeId?: string },
): Promise<CursorPageResponse<AgentDto>> {
  const query = new URLSearchParams();
  if (options?.limit != null) {
    query.set('limit', String(options.limit));
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const qs = query.toString();
  return fetchJson<CursorPageResponse<AgentDto>>(
    qs
      ? `${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/versions?${qs}`
      : `${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/versions`,
  );
}

/** 归档 Agent（拒绝创建新会话，不可恢复）。 */
export async function archiveAgent(agentId: string): Promise<void> {
  await fetchJson<void>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/archive`, { method: 'POST' });
}

/** 删除 Agent（级联删除全部版本）。 */
export async function deleteAgent(agentId: string): Promise<void> {
  await fetchJson<void>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}`, { method: 'DELETE' });
}