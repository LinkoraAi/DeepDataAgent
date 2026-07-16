import { post } from './http';
import type { 
  ModelTemplate, 
  ModelConfig, 
  AddModelConfigRequest, 
  UpdateModelConfigRequest,
  TestConnectionResult 
} from '@/modules/model/types';

/**
 * Get preset model templates
 */
export async function listTemplates(): Promise<ModelTemplate[]> {
  return post<ModelTemplate[]>('/api/llm-models/templates/list', {});
}

/**
 * Get my model configs
 */
export async function listConfigs(): Promise<ModelConfig[]> {
  return post<ModelConfig[]>('/api/llm-models/configs/list', {});
}

/**
 * Add model config
 */
export async function addConfig(request: AddModelConfigRequest): Promise<void> {
  return post<void>('/api/llm-models/configs/add', request);
}

/**
 * Update model config
 */
export async function updateConfig(request: UpdateModelConfigRequest): Promise<void> {
  return post<void>('/api/llm-models/configs/update', request);
}

/**
 * Delete model config
 */
export async function deleteConfig(id: number): Promise<void> {
  return post<void>('/api/llm-models/configs/delete', { id });
}

/**
 * Test connection
 */
export async function testConnection(id: number): Promise<TestConnectionResult> {
  return post<TestConnectionResult>('/api/llm-models/configs/test', { id });
}

/**
 * Set default model
 */
export async function setDefaultModel(id: number): Promise<void> {
  return post<void>('/api/llm-models/configs/set-default', { id });
}

/**
 * Get default model
 */
export async function getDefaultModel(): Promise<ModelConfig | null> {
  return post<ModelConfig | null>('/api/llm-models/default/get', {});
}
