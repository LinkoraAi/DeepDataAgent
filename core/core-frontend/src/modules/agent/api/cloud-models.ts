import { get } from '@/shared/api/http';

/**
 * 模型目录只读接口（后端 CloudModelController，{@code GET /api/v1/cloud/models}）。
 * <p>目录条目为平台种子配置（design D7），仅含展示与调优能力字段；
 * 内部供应商映射（目录档位 → model_profile_id）不进入本契约，由后端运行时装配消费。
 * 线格式为 camelCase（对齐 ModelCatalogResponse），与提交载荷的 snake_case 无关。</p>
 */

/** 目录端点路径（版本化云资源路径）。 */
const MODELS_BASE = '/api/v1/cloud/models';

/** 模型目录条目 DTO（后端 ModelCatalogResponse record 一一对应）。 */
export interface ModelCatalogDto {
  /** 对象类型词汇（后端固定 "model"） */
  type: string;
  /** 目录档位 id（如 auto/ultimate，模型引用字符串形态的取值） */
  id: string;
  /** 展示名 */
  displayName: string;
  /** 来源（system/user） */
  source: string;
  /** 是否启用（禁用档位不可被新引用） */
  isEnabled: boolean;
  /** 是否新模型标记（界面角标用） */
  isNew: boolean;
  /** 是否视觉（VL）模型 */
  isVl: boolean;
  /** 是否目录默认档位（界面默认选中用） */
  isDefault: boolean;
  /** 支持的 effort 档位线格式词汇（空数组 = 不支持 effort 调优） */
  efforts: string[];
  /** 缺省 effort 档位（可空） */
  defaultEffort: string | null;
  /** 最大输入 token（可空 = 目录未声明） */
  maxInputTokens: number | null;
  /** 最大输出 token（可空 = 目录未声明） */
  maxOutputTokens: number | null;
  /** 缺省上下文窗口（可空） */
  defaultContextWindow: number | null;
  /** 可选上下文窗口档位（空数组 = 不支持窗口调优） */
  availableContextWindows: number[];
}

/** 查询模型目录清单（保持种子顺序，后端目录未配置时报错）。 */
export function listCloudModels(): Promise<ModelCatalogDto[]> {
  return get<ModelCatalogDto[]>(MODELS_BASE);
}
