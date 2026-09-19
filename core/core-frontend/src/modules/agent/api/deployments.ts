/**
 * 定时部署接口模块（对齐后端 DeploymentController：/api/v1/cloud/deployments，6.5 管理面）。
 * <p>契约全 snake_case（统一对外形状）；列表走统一游标信封（status / agent_id /
 * created_at 区间 / include_archived 过滤）；PATCH 为 merge-patch 三态语义（缺省=不改、
 * 提供=替换、显式 null=清空，metadata 为浅合并增量且键值 null 删除该键）；
 * 手动运行端点由 trigger 更名为 run；运行记录支持单调度器与全局两种游标作用域。
 * webhook 触发经免 JWT 端点（POST /api/v1/cloud/webhook/deployments/{token}/trigger，前端不接线）。</p>
 */
import { fetchJson, type CursorPageResponse } from '@/shared/api/http';

/** 调度配置（cron 6 段 + IANA 时区）。 */
export interface DeploymentScheduleDto {
  cron: string;
  timezone: string;
}

/** 定时部署 DTO（对齐后端 DeploymentResponse，snake_case 全字段）。 */
export interface DeploymentDto {
  deployment_id: string;
  name: string;
  description: string | null;
  agent_id: string;
  agent_version: number;
  environment_id: string;
  environment_variables: Record<string, unknown>;
  resources: Record<string, unknown>[];
  vault_ids: string[];
  initial_events: Record<string, unknown>[];
  metadata: Record<string, unknown>;
  schedule: DeploymentScheduleDto | null;
  next_run_at: string | null;
  webhook_token: string | null;
  status: string;
  paused_reason: string | null;
  last_run_at: string | null;
  last_session_id: string | null;
  last_status: string | null;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
  /** 响应侧实时计算的未来 5 次到期时间（不落库） */
  upcoming_runs_at: string[] | null;
}

/** 调度运行记录 DTO（对齐后端 DeploymentRunResponse）。 */
export interface DeploymentRunDto {
  id: string;
  type: string;
  deployment_id: string;
  session_id: string | null;
  trigger: string;
  status: string;
  started_at: string;
  finished_at: string | null;
}

/** 手动运行结果（对齐后端 DeploymentTriggerResponse）。 */
export interface RunDeploymentResult {
  deployment_id: string;
  run_id: string;
  session_id: string;
}

/** 创建定时部署请求（agent_version 省略 = 固化当前激活版本；webhook=true 开通免 JWT 触发令牌）。 */
export interface CreateDeploymentPayload {
  name: string;
  description?: string | null;
  agent_id: string;
  agent_version?: number | null;
  environment_id: string;
  schedule?: DeploymentScheduleDto | null;
  webhook?: boolean | null;
}

/** merge-patch 更新载荷（三态语义：键缺省=不改、值为对象/字符串=替换、值为 null=清空；未知键 400）。 */
export type UpdateDeploymentPatch = Record<string, unknown>;

/** 调度器列表过滤与游标参数。 */
export interface ListDeploymentsOptions {
  status?: string;
  agentId?: string;
  createdAtGte?: string;
  createdAtLte?: string;
  includeArchived?: boolean;
  limit?: number;
  afterId?: string;
  beforeId?: string;
}

/** 运行记录列表游标与时间区间参数。 */
export interface ListRunsOptions {
  createdAtGte?: string;
  createdAtLte?: string;
  limit?: number;
  afterId?: string;
  beforeId?: string;
}

const DEPLOYMENTS_API_BASE = '/api/v1/cloud/deployments';

/** 追加游标与时间区间查询参数（后端字面 query 名 created_at[gte]/[lte]、after_id/before_id）。 */
function appendCursorQuery(query: URLSearchParams, options?: ListRunsOptions): void {
  if (!options) {
    return;
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
}

/** 拼接 query 后缀（空参时返回空串）。 */
function querySuffix(query: URLSearchParams): string {
  return query.toString() ? `?${query.toString()}` : '';
}

/** 创建定时部署。 */
export function createDeployment(payload: CreateDeploymentPayload): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(DEPLOYMENTS_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 游标分页列出调度器（创建时间降序；缺省排除归档）。 */
export function listDeployments(
  options?: ListDeploymentsOptions,
): Promise<CursorPageResponse<DeploymentDto>> {
  const query = new URLSearchParams();
  if (options?.status) {
    query.set('status', options.status);
  }
  if (options?.agentId) {
    query.set('agent_id', options.agentId);
  }
  if (options?.includeArchived != null) {
    query.set('include_archived', String(options.includeArchived));
  }
  appendCursorQuery(query, options);
  return fetchJson<CursorPageResponse<DeploymentDto>>(
    `${DEPLOYMENTS_API_BASE}${querySuffix(query)}`,
  );
}

/** 调度器详情。 */
export function getDeployment(deploymentId: string): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}`);
}

/** merge-patch 部分更新（JSON.stringify 原样保留显式 null 键=清空信号；undefined 键自然省略=不改）。 */
export function updateDeployment(
  deploymentId: string,
  patch: UpdateDeploymentPatch,
): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
}

/** 暂停调度器（reason 可空；active→paused）。 */
export function pauseDeployment(deploymentId: string, reason?: string | null): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/pause`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason ?? null }),
  });
}

/** 恢复调度器（paused→active，到期窗口重算不补欠账）。 */
export function unpauseDeployment(deploymentId: string): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/unpause`, {
    method: 'POST',
  });
}

/** 归档调度器（停止一切触发）。 */
export function archiveDeployment(deploymentId: string): Promise<DeploymentDto> {
  return fetchJson<DeploymentDto>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/archive`, {
    method: 'POST',
  });
}

/** 手动运行一次（run，active 前提；input 为首条用户消息文本，可空；6.5 由 trigger 更名）。 */
export function runDeployment(deploymentId: string, input?: string | null): Promise<RunDeploymentResult> {
  return fetchJson<RunDeploymentResult>(`${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input: input ?? null }),
  });
}

/** 单调度器运行记录游标列表（触发时间降序 + 时间区间过滤）。 */
export function listDeploymentRuns(
  deploymentId: string,
  options?: ListRunsOptions,
): Promise<CursorPageResponse<DeploymentRunDto>> {
  const query = new URLSearchParams();
  appendCursorQuery(query, options);
  return fetchJson<CursorPageResponse<DeploymentRunDto>>(
    `${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/runs${querySuffix(query)}`,
  );
}

/** 全局运行记录游标列表（跨全部调度器，经归属过滤）。 */
export function listAllDeploymentRuns(
  options?: ListRunsOptions,
): Promise<CursorPageResponse<DeploymentRunDto>> {
  const query = new URLSearchParams();
  appendCursorQuery(query, options);
  return fetchJson<CursorPageResponse<DeploymentRunDto>>(
    `${DEPLOYMENTS_API_BASE}/runs${querySuffix(query)}`,
  );
}

/** 单调度器运行记录详情（记录不属于该调度器 → 404）。 */
export function getDeploymentRun(deploymentId: string, runId: string): Promise<DeploymentRunDto> {
  return fetchJson<DeploymentRunDto>(
    `${DEPLOYMENTS_API_BASE}/${encodeURIComponent(deploymentId)}/runs/${encodeURIComponent(runId)}`,
  );
}

/** 全局运行记录详情（非本人所属 → 404，不泄露存在性）。 */
export function getDeploymentRunGlobal(runId: string): Promise<DeploymentRunDto> {
  return fetchJson<DeploymentRunDto>(`${DEPLOYMENTS_API_BASE}/runs/${encodeURIComponent(runId)}`);
}
