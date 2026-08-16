/**
 * 模型配置管理接口模块（对齐后端 ModelProfileController：/api/v1/agent/model-profiles）。
 * <p>凭证在响应中一律脱敏（credentialConfigured 仅表示是否已配置）；更新时
 * credential 传 null 保留原值、传空串清空。</p>
 */
import { fetchJson, type PaginatedResponse } from '@/shared/api/http';

/** API 格式枚举（与后端 ApiFormat 对齐）。 */
export const API_FORMATS = ['AGENTSCOPE', 'OPENAI', 'BAILIAN', 'OTHER'] as const;
export type ApiFormatType = (typeof API_FORMATS)[number];

/** 模型配置 DTO（凭证不返回明文）。 */
export interface ModelProfileDto {
  profileId: string;
  displayName: string;
  description: string | null;
  apiFormat: string;
  apiEndpointUrl: string;
  modelName: string;
  /** 是否已配置凭证（响应中不返回明文）。 */
  credentialConfigured: boolean;
  modelSeries: string | null;
  contextWindowInput: number | null;
  contextWindowOutput: number | null;
  toolCallRounds: number | null;
  modelType: number | null;
  vectorDimension: number | null;
  status: string;
  createdAt: string;
  updatedAt: string;
}

/** 模型配置请求（创建 / 更新共用字段；modelType 1=CHAT 2=EMBEDDING）。 */
export interface ModelProfilePayload {
  displayName: string;
  description?: string | null;
  apiFormat: string;
  apiEndpointUrl: string;
  modelName: string;
  /** 凭证：更新时 null 保留原值、空串清空。 */
  credential?: string | null;
  modelSeries?: string | null;
  contextWindowInput?: number | null;
  contextWindowOutput?: number | null;
  toolCallRounds?: number | null;
  modelType?: number | null;
  vectorDimension?: number | null;
}

const PROFILES_API_BASE = '/api/v1/agent/model-profiles';

/** 创建模型配置。 */
export function createModelProfile(payload: ModelProfilePayload): Promise<ModelProfileDto> {
  return fetchJson<ModelProfileDto>(PROFILES_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 分页查询模型配置。 */
export function listModelProfiles(options?: {
  keyword?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PaginatedResponse<ModelProfileDto>> {
  const query = new URLSearchParams({
    page: String(options?.page ?? 1),
    size: String(options?.size ?? 20),
  });
  if (options?.keyword) {
    query.set('keyword', options.keyword);
  }
  if (options?.status) {
    query.set('status', options.status);
  }
  return fetchJson<PaginatedResponse<ModelProfileDto>>(`${PROFILES_API_BASE}?${query}`);
}

/** 模型配置详情。 */
export function getModelProfile(profileId: string): Promise<ModelProfileDto> {
  return fetchJson<ModelProfileDto>(`${PROFILES_API_BASE}/${encodeURIComponent(profileId)}`);
}

/** 更新模型配置（全量替换；credential 语义见 {@link ModelProfilePayload}）。 */
export function updateModelProfile(profileId: string, payload: ModelProfilePayload): Promise<ModelProfileDto> {
  return fetchJson<ModelProfileDto>(`${PROFILES_API_BASE}/${encodeURIComponent(profileId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 禁用模型配置（新 Agent 版本不可引用）。 */
export async function disableModelProfile(profileId: string): Promise<void> {
  await fetchJson<void>(`${PROFILES_API_BASE}/${encodeURIComponent(profileId)}/disable`, { method: 'POST' });
}

/** 重新启用模型配置。 */
export async function enableModelProfile(profileId: string): Promise<void> {
  await fetchJson<void>(`${PROFILES_API_BASE}/${encodeURIComponent(profileId)}/enable`, { method: 'POST' });
}

/** 删除模型配置（被 Agent 版本引用时返回 409）。 */
export async function deleteModelProfile(profileId: string): Promise<void> {
  await fetchJson<void>(`${PROFILES_API_BASE}/${encodeURIComponent(profileId)}`, { method: 'DELETE' });
}