/**
 * 模型服务商信息
 */
export interface ModelProvider {
  /** 服务商 ID */
  id: number;
  /** 显示名称（如 "阿里百炼"） */
  name: string;
  /** 技术标识（如 "dashscope"） */
  providerKey: string;
  /** 默认 API 地址 */
  baseUrl: string;
}

/**
 * 模型信息（归属于某个服务商）
 */
export interface ModelInfo {
  /** 模型 ID */
  id: number;
  /** 模型技术标识（如 "qwen-plus"） */
  modelKey: string;
  /** 显示名称（如 "通义千问 Plus"） */
  displayName: string;
}

/**
 * 模型配置响应
 */
export interface ModelConfig {
  /** 配置 ID */
  id: number;
  /** 服务商标识 */
  providerKey: string;
  /** 服务商显示名称 */
  providerName: string;
  /** 模型标识 */
  modelKey: string;
  /** API 基础地址 */
  baseUrl: string;
  /** 脱敏后的 API Key */
  apiKeyMasked: string;
  /** API 格式（openai / anthropic） */
  apiFormat: string;
  /** 是否为默认模型 */
  isDefault: boolean;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/**
 * 添加模型配置请求
 * <p>支持两种模式：</p>
 * <ul>
 *   <li>预设模式：providerKey 对应已启用的服务商，modelKey 对应预设模型或自定义模型</li>
 *   <li>自定义模式：providerKey 为 "custom"，必须提供 baseUrl 和 modelKey</li>
 * </ul>
 */
export interface AddModelConfigRequest {
  /** 服务商标识（预设模式填服务商 key，自定义模式填 "custom"） */
  providerKey: string;
  /** 模型标识 */
  modelKey: string;
  /** API 基础地址（自定义模式必填，预设模式可选） */
  baseUrl?: string;
  /** API 格式（openai / anthropic，默认 openai） */
  apiFormat?: string;
  /** API Key（必填） */
  apiKey: string;
  /** 是否设为默认模型 */
  setDefault?: boolean;
}

/**
 * 更新模型配置请求
 * <p>支持更新 API Key 和 base_url，非空字段才会覆盖现有值。
 * 配置 ID 通过 URL 路径参数传递，不包含在请求体中。</p>
 */
export interface UpdateModelConfigRequest {
  /** API Key（留空表示不修改） */
  apiKey?: string;
  /** API 基础地址（留空表示不修改） */
  baseUrl?: string;
}

/**
 * 测试连接结果
 */
export interface TestConnectionResult {
  /** 是否可用 */
  available: boolean;
  /** 结果消息 */
  message: string;
  /** 响应时间（毫秒） */
  responseTime: number;
}
