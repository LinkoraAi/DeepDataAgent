/**
 * 云模型目录只读接口模块（对齐后端 CloudModelController：/api/v1/cloud/models）。
 * <p>仅暴露目录展示字段（id / displayName / efforts / 窗口档位等）；
 * 内部供应商映射（api_format / base_url / 凭证）不出现在此契约。</p>
 */
import { fetchJson } from '@/shared/api/http';

/** 模型推理 effort 档位（线格式词汇，对齐后端 ModelEffort 枚举）。 */
export type ModelEffort = 'none' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';

/** 模型目录条目 DTO（GET /api/v1/cloud/models）。 */
export interface ModelCatalogDto {
  /** 固定对象类型（model） */
  type: string;
  /** 目录模型标识（对外模型引用的 id） */
  id: string;
  displayName: string;
  /** 来源（system / user） */
  source: string;
  isEnabled: boolean;
  isNew: boolean;
  /** 是否视觉（VL）模型 */
  isVl: boolean;
  /** 是否目录默认模型 */
  isDefault: boolean;
  /** 支持的推理 effort 档位 */
  efforts: string[];
  defaultEffort: string | null;
  maxInputTokens: number | null;
  maxOutputTokens: number | null;
  defaultContextWindow: number | null;
  /** 可选上下文窗口档位 */
  availableContextWindows: number[];
}

/** 查询模型目录清单。 */
export function listCloudModels(): Promise<ModelCatalogDto[]> {
  return fetchJson<ModelCatalogDto[]>('/api/v1/cloud/models');
}
