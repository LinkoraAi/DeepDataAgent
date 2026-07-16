/**
 * Model template response
 */
export interface ModelTemplate {
  id: number;
  provider: string;
  modelName: string;
  displayName: string;
  baseUrl: string;
  description: string;
  sortOrder: number;
  isEnabled: boolean;
}

/**
 * Model config response
 */
export interface ModelConfig {
  id: number;
  name: string;
  templateId: number;
  provider: string;
  baseUrl: string;
  apiKeyMasked: string;
  modelName: string;
  temperature: number;
  isDefault: boolean;
  description: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Add model config request
 */
export interface AddModelConfigRequest {
  name: string;
  templateId: number;
  apiKey: string;
  temperature?: number;
  description?: string;
  setDefault?: boolean;
}

/**
 * Update model config request
 */
export interface UpdateModelConfigRequest {
  id: number;
  name?: string;
  temperature?: number;
  apiKey?: string;
  description?: string;
}

/**
 * Test connection result
 */
export interface TestConnectionResult {
  available: boolean;
  message: string;
  responseTime: number;
}
