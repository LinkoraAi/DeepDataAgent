/**
 * 运行环境管理接口模块（对齐后端 EnvironmentController：/api/v1/cloud/environments）。
 * <p>标准对象头 {@code id}（env_ 前缀）/ {@code type:"environment"} / {@code name} /
 * {@code description} / {@code config} / {@code metadata} / {@code archived_at}（snake_case 对齐后端）；
 * 归档仅以 {@code archived_at} 时间戳表达（无 archived 布尔位）。</p>
 * <p>config 为 {@code {type, packages, setup_script}}（snake_case 键对齐后端契约），
 * 类型值域 cloud / self_hosted（self_hosted 执行平面显式拒绝，创建会话时不可选）。
 * 请求侧 packages <b>仅接受 apt / pip / npm 三键</b>（cargo/gem/go 为响应保留字段，
 * 提交即 400）；响应侧固定序列化为含 {@code type:"packages"} 判别字段的六键对象。</p>
 */
import { fetchJson, type CursorPageResponse } from '@/shared/api/http';

/** 环境类型值域（后端 EnvironmentType 二值化，规范化小写）。 */
export const ENVIRONMENT_TYPES = ['cloud', 'self_hosted'] as const;
export type EnvironmentType = (typeof ENVIRONMENT_TYPES)[number];

/** 请求侧可提交的包管理器键（其余键提交即 400）。 */
export const REQUEST_PACKAGE_MANAGERS = ['apt', 'npm', 'pip'] as const;
export type RequestPackageManager = (typeof REQUEST_PACKAGE_MANAGERS)[number];

/** 软件包声明响应（判别字段 + 六 manager 全量回显，无数据为空数组）。 */
export interface EnvironmentPackagesDto {
  type?: string;
  apt: string[];
  cargo: string[];
  gem: string[];
  go: string[];
  npm: string[];
  pip: string[];
}

/** 软件包声明请求（仅 apt / npm / pip 三键；版本须显式锁定）。 */
export interface EnvironmentPackagesPayload {
  apt?: string[];
  npm?: string[];
  pip?: string[];
}

/** 环境配置响应（后端 EnvironmentConfigResponse：type 必填、setup_script 可空 ≤64KB）。 */
export interface EnvironmentConfigDto {
  type: string;
  packages: EnvironmentPackagesDto;
  /** 环境准备脚本（snake_case 键对齐后端契约；空白 = 未配置）。 */
  setup_script?: string | null;
}

/** 环境配置请求（packages 仅三键；self_hosted 不得携带 packages）。 */
export interface EnvironmentConfigPayload {
  type: string;
  packages?: EnvironmentPackagesPayload | null;
  setup_script?: string | null;
}

/** 运行环境 DTO（对齐后端 EnvironmentResponse；type 值保留字符串以兼容前向值域）。 */
export interface EnvironmentDto {
  id: string;
  type: string;
  name: string;
  description: string;
  config: EnvironmentConfigDto;
  metadata: Record<string, unknown>;
  /** 归档时间（null = 未归档） */
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 创建 / 更新运行环境请求（全量替换语义，对齐 CreateEnvironmentRequest / UpdateEnvironmentRequest；config 缺省 = cloud 默认）。 */
export interface EnvironmentPayload {
  name: string;
  description?: string | null;
  config?: EnvironmentConfigPayload | null;
  metadata?: Record<string, unknown> | null;
}

/** 六 manager 空包声明（响应缺省回填用；请求侧只取三键）。 */
export function emptyPackages(): EnvironmentPackagesDto {
  return { apt: [], cargo: [], gem: [], go: [], npm: [], pip: [] };
}

/** 运行环境列表游标与过滤参数（6.6 管理面 Cursor 约定）。 */
export interface ListEnvironmentsOptions {
  /** metadata JSON 包含过滤（对象文本，走后端 JSONB @> 判定） */
  metadata?: string;
  createdAtGte?: string;
  createdAtLte?: string;
  limit?: number;
  afterId?: string;
  beforeId?: string;
}

const ENVIRONMENTS_API_BASE = '/api/v1/cloud/environments';

/** 游标查询运行环境列表（创建时间降序，无 total）。 */
export function listEnvironments(
  options: ListEnvironmentsOptions = {},
): Promise<CursorPageResponse<EnvironmentDto>> {
  const query = new URLSearchParams();
  if (options.metadata) {
    query.set('metadata', options.metadata);
  }
  if (options.createdAtGte) {
    query.set('created_at[gte]', options.createdAtGte);
  }
  if (options.createdAtLte) {
    query.set('created_at[lte]', options.createdAtLte);
  }
  if (options.limit != null) {
    query.set('limit', String(options.limit));
  }
  if (options.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const qs = query.toString();
  return fetchJson<CursorPageResponse<EnvironmentDto>>(qs ? `${ENVIRONMENTS_API_BASE}?${qs}` : ENVIRONMENTS_API_BASE);
}

/** 运行环境详情。 */
export function getEnvironment(environmentId: string): Promise<EnvironmentDto> {
  return fetchJson<EnvironmentDto>(`${ENVIRONMENTS_API_BASE}/${encodeURIComponent(environmentId)}`);
}

/** 创建运行环境。 */
export function createEnvironment(payload: EnvironmentPayload): Promise<EnvironmentDto> {
  return fetchJson<EnvironmentDto>(ENVIRONMENTS_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 更新运行环境（全量替换）。 */
export function updateEnvironment(environmentId: string, payload: EnvironmentPayload): Promise<EnvironmentDto> {
  return fetchJson<EnvironmentDto>(`${ENVIRONMENTS_API_BASE}/${encodeURIComponent(environmentId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 删除运行环境（仍被未删除会话或未归档调度器引用 → 409）。 */
export async function deleteEnvironment(environmentId: string): Promise<void> {
  await fetchJson<void>(`${ENVIRONMENTS_API_BASE}/${encodeURIComponent(environmentId)}`, { method: 'DELETE' });
}

/** 是否自托管执行平面（大小写不敏感、可空安全；后端 Session 装配该类型环境显式 400，前端仅作不可执行标注）。 */
export function isSelfHosted(type?: string | null): boolean {
  return type != null && type.toLowerCase() === 'self_hosted';
}