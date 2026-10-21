import { get, post, del } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 模型配置端点（后端 ModelProfileController，前缀 /api/v1/cloud/model-profiles）。
 * 注意：本域 JSON 为 camelCase（与云资源域 snake_case 不同）；
 * 更新端点是 POST /{profileId}（全量替换语义），非 PUT。
 */

/**
 * 模型类型业务码（与后端 model-type-taxonomy 契约一致）：
 * 1=对话LLM、2=视觉对话、3=嵌入、4=重排。
 * 码值是持久化与线格式共用口径，禁止按枚举顺序推导。
 */
export const MODEL_TYPE_CHAT_LLM = 1;
export const MODEL_TYPE_CHAT_VLM = 2;
export const MODEL_TYPE_EMBEDDING = 3;
export const MODEL_TYPE_RERANK = 4;

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

/**
 * 分页列表（keyword/status/modelType/page/size）。
 * <p>{@code modelType} 支持单值或数组；数组按重复查询参数发送（后端并集语义）。</p>
 */
export function listModelProfiles(params: {
  keyword?: string;
  status?: string;
  modelType?: number | number[];
  page?: number;
  size?: number;
}): Promise<PaginatedResp<ModelProfileResponse>> {
  const search = new URLSearchParams();
  if (params.keyword) {
    search.set('keyword', params.keyword);
  }
  if (params.status) {
    search.set('status', params.status);
  }
  if (params.page !== undefined) {
    search.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    search.set('size', String(params.size));
  }
  const types: number[] = params.modelType === undefined
    ? []
    : Array.isArray(params.modelType) ? params.modelType : [params.modelType];
  types.forEach((code) => search.append('modelType', String(code)));
  const query = search.toString();
  return get<PaginatedResp<ModelProfileResponse>>(`${BASE}${query ? `?${query}` : ''}`);
}

/**
 * 取全部启用中的某类（或多类）模型配置，供各模块下拉数据源使用。
 * <p>类型过滤下推到服务端（{@code modelType} 查询参数），不再拉全量后本地过滤；
 * size 上限 100 足够管理端规模。</p>
 *
 * @param modelType 类型业务码，单值或数组（数组取并集）；缺省表示不限类型
 */
export async function listEnabledProfiles(
  modelType?: number | number[],
): Promise<ModelProfileResponse[]> {
  const resp = await listModelProfiles({ status: 'ENABLED', page: 1, size: 100, modelType });
  return resp.list ?? [];
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
