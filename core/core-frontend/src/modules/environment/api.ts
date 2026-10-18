import { get, post, del, buildQuery } from '@/shared/api/http';
import type { CursorPage } from '@/shared/types/api';

/**
 * 运行环境端点（后端 EnvironmentController，前缀 /api/v1/cloud/environments）。
 * 注意：本域为云资源契约，JSON 一律 snake_case、更新端点是 POST /{id}（全量替换语义），
 * 列表为游标分页（limit / after_id / before_id），归档仅以 archived_at 时间戳表达。
 */

/** 环境配置类型（后端 config.type 线格式小写值） */
export type EnvironmentConfigType = 'cloud' | 'self_hosted';

/** 请求侧 packages 允许的键（后端仅接受三键，其余键 400） */
export type EnvironmentPackageKey = 'apt' | 'pip' | 'npm';

/** 请求侧预装依赖：包管理器 → 包名数组 */
export type EnvironmentPackagesPayload = Partial<Record<EnvironmentPackageKey, string[]>>;

/** 响应侧预装依赖：判别字段 type 恒为 packages，六类数组全量回显（cargo/gem/go 恒空） */
export interface EnvironmentPackagesWire {
  type: string;
  apt: string[];
  npm: string[];
  pip: string[];
  cargo: string[];
  gem: string[];
  go: string[];
}

/** 响应侧环境配置 */
export interface EnvironmentConfigWire {
  /** 环境类型（cloud / self_hosted） */
  type: string;
  /** 预装依赖（六类全量回显） */
  packages?: EnvironmentPackagesWire | null;
  /** 准备阶段 shell 脚本（可空；下划线键按契约约定） */
  setup_script?: string | null;
}

/** 运行环境线格式对象（EnvironmentResponse） */
export interface EnvironmentWire {
  /** 业务 ID（env_ 前缀） */
  id: string;
  /** 资源类型词（恒 environment） */
  type: string;
  /** 名称（owner 内唯一） */
  name: string;
  /** 描述（缺省空串） */
  description: string;
  /** 环境配置 */
  config: EnvironmentConfigWire;
  /** 自定义元数据键值对象 */
  metadata: Record<string, unknown>;
  /** 归档时间（null=未归档；无 archived 布尔字段） */
  archived_at: string | null;
  /** 创建时间 */
  created_at: string;
  /** 更新时间 */
  updated_at: string;
}

/** 请求侧环境配置（self_hosted MUST NOT 携带 packages） */
export interface EnvironmentConfigPayload {
  type: EnvironmentConfigType;
  packages?: EnvironmentPackagesPayload;
  setup_script?: string | null;
}

/** 创建/更新请求载荷（两端点同构，全量替换语义） */
export interface EnvironmentPayload {
  name: string;
  description?: string;
  config?: EnvironmentConfigPayload;
  metadata?: Record<string, unknown>;
}

/** 游标列表查询参数 */
export interface ListEnvironmentParams {
  /** 每页条数（1-100，缺省 20） */
  limit?: number;
  /** 向后翻页游标（取上页 last_id / next_page） */
  after_id?: string;
  /** 向前翻页游标 */
  before_id?: string;
}

const BASE = '/api/v1/cloud/environments';

/** 游标分页列表（创建时间降序）。 */
export function listEnvironments(params: ListEnvironmentParams = {}): Promise<CursorPage<EnvironmentWire>> {
  return get<CursorPage<EnvironmentWire>>(`${BASE}${buildQuery({ ...params })}`);
}

/** 创建运行环境（名称在 owner 内唯一，重复 409）。 */
export function createEnvironment(payload: EnvironmentPayload): Promise<EnvironmentWire> {
  return post<EnvironmentWire>(BASE, payload);
}

/** 更新运行环境（POST /{id}，全量替换；不影响已运行会话）。 */
export function updateEnvironment(id: string, payload: EnvironmentPayload): Promise<EnvironmentWire> {
  return post<EnvironmentWire>(`${BASE}/${id}`, payload);
}

/** 归档运行环境（写 archived_at；仍被未删除会话或未归档调度器引用时 409）。 */
export function archiveEnvironment(id: string): Promise<EnvironmentWire> {
  return post<EnvironmentWire>(`${BASE}/${id}/archive`);
}

/** 删除运行环境（仍被会话/调度器引用时 409）。 */
export function deleteEnvironment(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`);
}
