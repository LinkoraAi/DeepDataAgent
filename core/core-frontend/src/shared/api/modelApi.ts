import { del, get, post, put } from './http';
import type {
  ModelProvider,
  ModelInfo,
  ModelConfig,
  AddModelConfigRequest,
  UpdateModelConfigRequest,
  TestConnectionResult,
} from '@/modules/model/types';

/**
 * 获取所有启用的服务商列表
 */
export async function fetchProviders(): Promise<ModelProvider[]> {
  return get<ModelProvider[]>('/api/model/providers');
}

/**
 * 根据服务商技术标识获取该服务商下所有启用的模型
 *
 * @param providerKey 服务商技术标识
 */
export async function fetchModelsByProvider(providerKey: string): Promise<ModelInfo[]> {
  return get<ModelInfo[]>(`/api/model/providers/${providerKey}/models`);
}

/**
 * 获取所有用户模型配置列表
 */
export async function listConfigs(): Promise<ModelConfig[]> {
  return get<ModelConfig[]>('/api/model/configs');
}

/**
 * 添加模型配置
 */
export async function addConfig(request: AddModelConfigRequest): Promise<void> {
  return post<void>('/api/model/configs', request);
}

/**
 * 更新模型配置
 *
 * @param id 配置 ID
 * @param request 更新请求
 */
export async function updateConfig(id: number, request: UpdateModelConfigRequest): Promise<void> {
  return put<void>(`/api/model/configs/${id}`, request);
}

/**
 * 删除模型配置
 *
 * @param id 配置 ID
 */
export async function deleteConfig(id: number): Promise<void> {
  return del<void>(`/api/model/configs/${id}`);
}

/**
 * 测试连接
 *
 * @param id 配置 ID
 */
export async function testConnection(id: number): Promise<TestConnectionResult> {
  return post<TestConnectionResult>(`/api/model/configs/${id}/test`);
}

/**
 * 设置默认模型
 *
 * @param id 配置 ID
 */
export async function setDefaultModel(id: number): Promise<void> {
  return put<void>(`/api/model/configs/${id}/default`);
}

/**
 * 获取默认模型
 */
export async function getDefaultModel(): Promise<ModelConfig | null> {
  return get<ModelConfig | null>('/api/model/configs/default');
}

/**
 * 获取模型配置详情（用于编辑，返回解密后的 API Key）
 *
 * @param id 配置 ID
 */
export async function fetchConfigForEdit(id: number): Promise<ModelConfig> {
  return get<ModelConfig>(`/api/model/configs/${id}/edit`);
}
