import { get, post, del, buildQuery } from '@/shared/api/http';
import type { CursorPage } from '@/shared/types/api';

/**
 * 智能体与模型目录端点（后端 AgentController / CloudModelController，前缀 /api/v1/cloud/agents 与 /api/v1/cloud/models）。
 * <p>契约要点（逐字段核对后端代码）：</p>
 * <ul>
 *   <li>创建：POST /agents（body=AgentConfigRequest）→ 完整 Agent 对象（version=1）；</li>
 *   <li>改定义属性：POST /agents/{id}（body=UpdateAgentRequest，version 必填乐观锁）→ 生成新版本快照，
 *       版本不匹配 409 conflict_error；</li>
 *   <li>发版：POST /agents/{id}/versions（body=AgentConfigRequest 全量配置）→ 版本快照对象；</li>
 *   <li>版本历史：GET /agents/{id}/versions（发布号降序游标，游标为版本号字符串，有下一页回传 next_page）；</li>
 *   <li>激活/归档/删除：POST /agents/{id}/versions/{n}/activate、POST /agents/{id}/archive、DELETE /agents/{id}；</li>
 *   <li>Agent 对象与版本快照对外<b>同形</b>（{@link AgentWire}），差别仅在 version 取值口径。</li>
 * </ul>
 * <p>已废止字段（agents_md、mcp_servers、multiagent 非空、model.speed）MUST NOT 出现在任何提交载荷中，
 * 本模块的请求类型不提供这些字段，装配逻辑见 ../mappers.ts。</p>
 */

/** 智能体端点前缀（版本化云资源路径）。 */
const BASE = '/api/v1/cloud/agents';

/** 模型引用字符串简写形态：目录模型 id 或内部 model_profile_id（等价于默认调优参数）。 */
export type ModelRefStringWire = string;

/**
 * 模型引用对象形态（后端 ModelRef：{id, effort?, context_window?}）。
 * effort 线格式词汇为 none|low|medium|high|xhigh|max；context_window 为正整数档位。
 */
export interface ModelRefObjectWire {
  /** 目录模型 id */
  id: string;
  /** 推理 effort 档位（省略 = 目录缺省档位） */
  effort?: string;
  /** 上下文窗口档位（省略 = 目录缺省窗口） */
  context_window?: number;
}

/** 模型引用对外形态：按提交形态回显，故两种形态都可能出现在响应里。 */
export type ModelRefWire = ModelRefStringWire | ModelRefObjectWire;

/** 内置工具集条目的逐工具配置（后端 AgentTool.ToolConfig）。 */
export interface AgentToolConfigWire {
  /** 工具名（须在 11 个内置工具白名单内） */
  name: string;
  /** 是否启用（省略 = 默认启用；false = 隐藏并拒绝调用） */
  enabled?: boolean;
  /** 权限策略（省略 = 平台默认）：always_allow|always_ask|always_deny */
  permission_policy?: string;
}

/** 内置工具集条目（type 恒 agent_toolset_20260401）。 */
export interface AgentToolsetItemWire {
  type: string;
  /** 白名单（空/省略 = 全量基座暴露） */
  enabled_tools?: string[];
  /** 隐藏/拒绝名单（与 enabled_tools 不得交集） */
  disallowed_tools?: string[];
  /** 逐工具配置 */
  configs?: AgentToolConfigWire[];
}

/** 自定义工具条目（type 恒 custom；不支持 configs/permission_policy）。 */
export interface AgentCustomToolWire {
  type: string;
  /** 工具名（不得与内置重名、不得为 advisor、不得含 __ 或 mcp__ 前缀） */
  name?: string;
  /** 工具描述（必填） */
  description?: string;
  /** 输入 JSON Schema（type 须为 object） */
  input_schema?: Record<string, unknown>;
}

/** 工具配方条目联合类型（响应侧未做窄化判别，按 type 分支消费）。 */
export type AgentToolWire = AgentToolsetItemWire | AgentCustomToolWire;

/** 技能绑定条目（后端 SkillBinding：version 省略或 "latest" 为动态版，其余为钉版 epoch 串）。 */
export interface AgentSkillBindingWire {
  /** 绑定类型：catalog / custom */
  type: string;
  /** 技能业务 ID（skill_ 前缀） */
  skill_id: string;
  /** 版本键（epoch 微秒字符串；动态版省略该键） */
  version?: string;
}

/**
 * 智能体线格式对象（后端 AgentResponse；Agent 对象与版本快照同形）。
 * 键名与后端 record 组件名一致：基础字段无下划线，时间字段为 created_at/updated_at/archived_at。
 */
export interface AgentWire {
  /** 业务 ID（agent_ 前缀） */
  id: string;
  /** 对象类型（恒 agent） */
  type: string;
  /** 名称（1-256） */
  name: string;
  /** 描述（≤2048，未配置为 null） */
  description: string | null;
  /** 模型引用（按提交形态回显；快照缺失时 null） */
  model: ModelRefWire | null;
  /** 系统提示词（≤100000，未配置为 null） */
  system: string | null;
  /** 工具配方（未配置为空数组） */
  tools: AgentToolWire[];
  /** 技能绑定（未配置为空数组） */
  skills: AgentSkillBindingWire[];
  /** 业务元数据（未配置为空对象） */
  metadata: Record<string, unknown>;
  /** 多智能体编排（本期恒 null，词汇预留） */
  multiagent: Record<string, unknown> | null;
  /** 版本号（Agent 对象取当前生效版本，快照取自身发布号；起始 1） */
  version: number;
  /** 归档时间（null = 未归档；契约无 archived 布尔） */
  archived_at: string | null;
  /** 创建时间（RFC3339 UTC） */
  created_at: string;
  /** 更新时间（RFC3339 UTC） */
  updated_at: string;
}

/**
 * 模型目录条目 DTO 与拉取函数见同目录 cloud-models.ts（只读参考数据独立成模块）；
 * 智能体表单模型双模式：默认直选本租户模型配置（listEnabledProfiles，值 = model_profile_id），
 * 可切目录档位（值 = 档位 id + 可选调优），Raw 模式两形态皆可手工表达。
 */

/** 列表查询参数（后端 @RequestParam 真实参数名）。 */
export interface ListAgentsParams {
  /** 名称模糊关键字 */
  keyword?: string;
  /** 状态过滤：active（缺省）/ archived */
  status?: string;
  /** 元数据键值包含过滤（JSON 对象文本） */
  metadata?: string;
  /** 创建时间下界（RFC3339） */
  created_after?: string;
  /** 创建时间上界（RFC3339） */
  created_before?: string;
  /** 每页条数（1-100，缺省 20） */
  limit?: number;
  /** 向后翻页游标（本页末条 agent_id） */
  after_id?: string;
  /** 向前翻页游标（本页首条 agent_id，与 after_id 互斥） */
  before_id?: string;
}

/** 版本历史查询参数（游标为「版本号字符串」）。 */
export interface ListAgentVersionsParams {
  /** 每页条数（1-100，缺省 20） */
  limit?: number;
  /** 不透明下页游标（取响应 next_page，与 after_id/before_id 互斥） */
  page?: string;
  /** 向后翻页游标（版本号字符串） */
  after_id?: string;
  /** 向前翻页游标（版本号字符串，与 after_id 互斥） */
  before_id?: string;
}

/** 创建与发版共用的配置载荷（后端 AgentConfigRequest 的对外提交形态）。 */
export interface AgentConfigPayload {
  /** 名称（1-256，必填） */
  name: string;
  /** 描述（≤2048） */
  description?: string;
  /** 系统提示词（≤100000，指令唯一载体） */
  system?: string;
  /** 模型引用（字符串简写或对象形态，必填） */
  model: ModelRefWire;
  /** 工具配方（内置工具集条目 + custom 条目） */
  tools?: AgentToolWire[];
  /** 技能绑定（≤20） */
  skills?: AgentSkillBindingWire[];
  /** 业务元数据键值对象 */
  metadata?: Record<string, unknown>;
}

/** 改定义属性载荷（后端 UpdateAgentRequest；version 必填乐观锁，缺省名称/描述沿用现值）。 */
export interface UpdateAgentDefinitionPayload {
  /** 新名称（省略 = 沿用现值） */
  name?: string;
  /** 新描述（省略 = 沿用现值） */
  description?: string;
  /** 客户端持有的当前版本号（必填；不匹配 409） */
  version: number;
}

/** 游标分页列出智能体（创建时间降序，缺省 status=active）。 */
export function listAgents(params: ListAgentsParams = {}): Promise<CursorPage<AgentWire>> {
  return get<CursorPage<AgentWire>>(`${BASE}${buildQuery({ ...params })}`);
}

/**
 * 智能体详情。
 * @param agentId 业务 ID
 * @param version 目标发布号（省略 = 当前生效版本；传入则返回该版本快照，与 Agent 对象同形）
 */
export function getAgent(agentId: string, version?: number): Promise<AgentWire> {
  const query = buildQuery({ version });
  return get<AgentWire>(`${BASE}/${encodeURIComponent(agentId)}${query}`);
}

/** 创建智能体（同步产生首版快照并激活，返回 version=1 的完整对象）。 */
export function createAgent(payload: AgentConfigPayload): Promise<AgentWire> {
  return post<AgentWire>(BASE, payload);
}

/** 改定义属性（POST /agents/{id}，携带 version 乐观锁；成功同步生成新版本快照，不匹配 409）。 */
export function updateAgentDefinition(agentId: string, payload: UpdateAgentDefinitionPayload): Promise<AgentWire> {
  return post<AgentWire>(`${BASE}/${encodeURIComponent(agentId)}`, payload);
}

/** 发布新版本（全量配置提交，版本号由服务端台账递增）。 */
export function publishVersion(agentId: string, payload: AgentConfigPayload): Promise<AgentWire> {
  return post<AgentWire>(`${BASE}/${encodeURIComponent(agentId)}/versions`, payload);
}

/** 游标分页列出版本历史（发布号降序）。 */
export function listAgentVersions(
  agentId: string,
  params: ListAgentVersionsParams = {},
): Promise<CursorPage<AgentWire>> {
  return get<CursorPage<AgentWire>>(`${BASE}/${encodeURIComponent(agentId)}/versions${buildQuery({ ...params })}`);
}

/** 激活 / 回滚版本（切换当前生效版本；越界 400、台账缺失 404、已归档 409）。 */
export function activateVersion(agentId: string, versionNumber: number): Promise<AgentWire> {
  return post<AgentWire>(`${BASE}/${encodeURIComponent(agentId)}/versions/${versionNumber}/activate`);
}

/** 归档智能体（仅写 archived_at，已发布版本仍可供既有会话装配；不可逆）。 */
export function archiveAgent(agentId: string): Promise<void> {
  return post<void>(`${BASE}/${encodeURIComponent(agentId)}/archive`);
}

/** 删除智能体（逻辑删除定义与全部版本）。 */
export function deleteAgent(agentId: string): Promise<void> {
  return del<void>(`${BASE}/${encodeURIComponent(agentId)}`);
}
