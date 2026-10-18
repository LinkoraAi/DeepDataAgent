import { get, post, del, put, buildQuery } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 模型配置端点（后端 ModelProfileController，前缀 /api/v1/cloud/model-profiles）。
 * 注意：本域 JSON 为 camelCase（与云资源域 snake_case 不同）；
 * 更新端点是 POST /{profileId}（全量替换语义），非 PUT。
 */

/** 模型类型线格式：1=CHAT、2=EMBEDDING */
export const MODEL_TYPE_CHAT = 1;
export const MODEL_TYPE_EMBEDDING = 2;

/** 模型配置响应（ModelProfileResponse；凭证永不回显，只有 credentialConfigured） */
export interface ModelProfileResponse {
  profileId: string;
  displayName: string;
  description: string | null;
  /** 大写枚举名：OPENAI/BAILIAN/AGENTSCOPE/OTHER */
  apiFormat: string;
  apiEndpointUrl: string;
  modelName: string;
  credentialConfigured: boolean;
  modelSeries: string | null;
  contextWindowInput: number | null;
  contextWindowOutput: number | null;
  toolCallRounds: number | null;
  modelType: number;
  vectorDimension: number | null;
  status: 'ENABLED' | 'DISABLED';
  createdAt: string;
  updatedAt: string;
}

/** 创建/更新请求（CreateModelProfileRequest 同构）；credential 三态：缺省=保留、空串=清除、新值=替换 */
export interface ModelProfilePayload {
  displayName: string;
  description?: string;
  apiFormat: string;
  apiEndpointUrl: string;
  modelName: string;
  credential?: string | null;
  modelSeries?: string;
  contextWindowInput?: number;
  contextWindowOutput?: number;
  toolCallRounds?: number;
  modelType?: number;
  vectorDimension?: number;
}

const BASE = '/api/v1/cloud/model-profiles';

/** 分页列表（keyword/status/page/size） */
export function listModelProfiles(params: {
  keyword?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PaginatedResp<ModelProfileResponse>> {
  return get<PaginatedResp<ModelProfileResponse>>(`${BASE}${buildQuery(params)}`);
}

/**
 * 取全部启用中的某类型模型（跨模块下拉数据源：知识库嵌入/多模态/重排、智能体等）。
 * 后端按 status=ENABLED 过滤，类型在前端二次过滤（size 上限 100 足够管理端规模）。
 */
export async function listEnabledProfiles(modelType?: number): Promise<ModelProfileResponse[]> {
  const resp = await listModelProfiles({ status: 'ENABLED', page: 1, size: 100 });
  const list = resp.list ?? [];
  return modelType === undefined ? list : list.filter((item) => item.modelType === modelType);
}

/** 详情 */
export function getModelProfile(profileId: string): Promise<ModelProfileResponse> {
  return get<ModelProfileResponse>(`${BASE}/${profileId}`);
}

/** 创建 */
export function createModelProfile(payload: ModelProfilePayload): Promise<ModelProfileResponse> {
  return post<ModelProfileResponse>(BASE, payload);
}

/** 更新（全量替换；credential 缺省保留/空串清除/新值替换） */
export function updateModelProfile(profileId: string, payload: ModelProfilePayload): Promise<ModelProfileResponse> {
  return post<ModelProfileResponse>(`${BASE}/${profileId}`, payload);
}

/** 启用 */
export function enableModelProfile(profileId: string): Promise<void> {
  return post<void>(`${BASE}/${profileId}/enable`);
}

/** 禁用 */
export function disableModelProfile(profileId: string): Promise<void> {
  return post<void>(`${BASE}/${profileId}/disable`);
}

/** 删除（被智能体版本引用时后端 409） */
export function deleteModelProfile(profileId: string): Promise<void> {
  return del<void>(`${BASE}/${profileId}`);
}
