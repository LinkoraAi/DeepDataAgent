import { FUSION_TYPES, RETRIEVAL_CHANNELS, RETRIEVAL_STRATEGIES } from '@/shared/enums/dict';
import type { RetrievalStrategyConfig } from './api/retrieval';

/**
 * 检索策略配置（草稿）与后端线格式（JSON 字符串）之间的纯装配 / 反解 / 校验层。
 * <p>后端以 {@code PUT knowledge-bases/{id}/retrieval-config} 接收「JSON 字符串」承载策略，
 * 并由 RetrievalConfigValidator 做区间与结构校验；本模块把同一套口径前置到表单层：
 * draft ↔ JSON 双向转换 + 提交前字段级校验，避免无谓请求。纯函数、无 Vue 依赖，便于单测。</p>
 */

/** 检索策略类型：朴素召回（向量 + BM25） */
export const STRATEGY_NAIVE = 'NAIVE';
/** 检索策略类型：混合召回（向量 + BM25 + 图谱） */
export const STRATEGY_MIX = 'MIX';
/** 融合类型：倒数排名融合 */
export const FUSION_RRF = 'RRF';
/** 融合类型：加权求和 */
export const FUSION_WEIGHTED_SUM = 'WEIGHTED_SUM';

/**
 * 可编辑检索策略草稿：各节点与标量恒存在（由 defaultRetrievalConfig / parseStrategyJson 补齐），
 * 可直接绑定表单控件；结构上是 RetrievalStrategyConfig 的收紧子集。
 */
export interface RetrievalDraft {
  /** 检索策略类型：NAIVE / MIX */
  strategyType: string;
  /** 是否启用问题改写 */
  rewriteQuestion: boolean;
  /** 结果返回数量（1~100） */
  resultChunkCount: number;
  /** 相似度阈值（0~1，0 表示不过滤） */
  similarThreshold: number;
  /** 重排配置（可编辑态） */
  rerankConfig: RetrievalDraftRerank;
  /** 融合配置（可编辑态） */
  fusionConfig: RetrievalDraftFusion;
}

/** 草稿态重排配置（enabled 与模型引用恒存在，topK 留空表示不限制） */
export interface RetrievalDraftRerank {
  /** 是否启用重排 */
  enabled: boolean;
  /** 重排模型 profileId（启用时必填） */
  modelProfileId: string;
  /** 重排保留条数（留空不提交该字段） */
  topK?: number;
}

/** 草稿态融合配置（RRF 与加权求和互斥，两分支取值均常驻便于来回切换） */
export interface RetrievalDraftFusion {
  /** 融合类型：RRF / WEIGHTED_SUM */
  fusionType: string;
  /** RRF 常数 K（仅 RRF 分支提交，必须 &gt; 0） */
  rrfK: number;
  /** 三通道稠密权重（仅加权分支提交，键为通道枚举名） */
  channelDenseWeight: Record<string, number>;
}

/**
 * 检索附图草稿：后端线契约（contentType + base64）加前端展示所需的文件名。
 * <p>提交时按 QueryImage 字段下发，name 仅用于缩略图标签与错误定位。</p>
 */
export interface QueryImageDraft {
  /** 文件名（不提交后端） */
  name: string;
  /** 图片 MIME 类型（白名单：image/jpeg|png|gif|webp） */
  contentType: string;
  /** base64 载荷（不含 data: 前缀） */
  data: string;
}

/** 结果返回数量下限（对齐后端 RESULT_CHUNK_COUNT_MIN） */
export const RESULT_CHUNK_COUNT_MIN = 1;
/** 结果返回数量上限（对齐后端 RetrievalConfigDTO.RESULT_CHUNK_COUNT_MAX） */
export const RESULT_CHUNK_COUNT_MAX = 100;
/** 相似度阈值下限（对齐后端 SIMILAR_THRESHOLD_MIN） */
export const SIMILAR_THRESHOLD_MIN = 0;
/** 相似度阈值上限（对齐后端 RetrievalConfigDTO.SIMILAR_THRESHOLD_MAX） */
export const SIMILAR_THRESHOLD_MAX = 1;
/** 结果返回数量默认值（对齐后端检索契约默认召回量） */
export const DEFAULT_RESULT_CHUNK_COUNT = 20;
/** RRF 常数 K 默认值（对齐后端 FusionStrategyConfig.DEFAULT_RRF_K） */
export const DEFAULT_RRF_K = 60;
/** RRF 常数 K 下限（后端要求严格大于 0） */
export const RRF_K_MIN = 1;
/** RRF 常数 K 上限（安全整数上界，仅防脏数据） */
export const RRF_K_MAX = Number.MAX_SAFE_INTEGER;
/** 重排保留条数下限（topK 为正整数，留空表示不限制） */
export const RERANK_TOP_K_MIN = 1;
/** 重排保留条数默认值（开启重排时的常用保留量，仍可在面板中清空表示不限制） */
export const DEFAULT_RERANK_TOP_K = 10;
/** 通道稠密权重输入下限（后端校验为开区间 (0,1)，控件边界仅允许贴近 0） */
export const CHANNEL_WEIGHT_FLOOR = 0;
/** 通道稠密权重输入上限（后端校验为开区间 (0,1)，控件边界仅允许贴近 1） */
export const CHANNEL_WEIGHT_CEIL = 1;
/** 加权求和各通道默认权重（键为 RetrievalChannel 枚举名） */
export const DEFAULT_CHANNEL_WEIGHTS: Record<string, number> = {
  VECTOR: 0.7,
  BM25: 0.2,
  GRAPH: 0.1,
};

/** 检索附图数量上限（对齐后端 MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST） */
export const MAX_QUERY_IMAGES = 3;
/** 单张检索附图字节上限（对齐后端 MultimodalConstraints.MAX_IMAGE_BYTES = 4MB） */
export const MAX_QUERY_IMAGE_BYTES = 4 * 1024 * 1024;
/** 检索附图内容类型白名单（对齐后端 QueryImageValidator，大小写不敏感） */
export const QUERY_IMAGE_CONTENT_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];

/** 复制一份默认权重表（避免调用方就地改写常量对象） */
function defaultChannelWeights(): Record<string, number> {
  return { ...DEFAULT_CHANNEL_WEIGHTS };
}

/**
 * 默认检索策略配置：NAIVE / 不改写 / 结果数量 20 / 阈值不过滤（0）/ 重排关闭（topK 预填 10）/ RRF（K=60）。
 * <p>产物为「完整草稿形态」——嵌套配置与各通道权重均已补齐，可直接绑定表单，
 * 也是 parseStrategyJson 防御回退的基准。</p>
 * @returns 默认配置对象（每次新建，不与外部共享引用）
 */
export function defaultRetrievalConfig(): RetrievalDraft {
  return {
    strategyType: STRATEGY_NAIVE,
    rewriteQuestion: false,
    resultChunkCount: DEFAULT_RESULT_CHUNK_COUNT,
    similarThreshold: SIMILAR_THRESHOLD_MIN,
    rerankConfig: { enabled: false, modelProfileId: '', topK: DEFAULT_RERANK_TOP_K },
    fusionConfig: { fusionType: FUSION_RRF, rrfK: DEFAULT_RRF_K, channelDenseWeight: defaultChannelWeights() },
  };
}

/** 安全解析 JSON 字符串为对象，非法 / 非对象返回 null（防御存量脏数据） */
function parseJsonObject(raw: string | null | undefined): Record<string, unknown> | null {
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    return null;
  } catch {
    return null;
  }
}

/** 读取嵌套对象（非对象返回 null） */
function readObject(value: unknown): Record<string, unknown> | null {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
}

/** 读取字符串（非字符串回退默认） */
function readString(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback;
}

/** 读取布尔（非布尔回退默认） */
function readBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

/** 读取有限数值（非法 / 非数值回退默认） */
function readNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

/** 值是否命中字典选项（枚举白名单回退用） */
function isInOptions(value: string, options: Array<{ value: string }>): boolean {
  return options.some((option) => option.value === value);
}

/** 整数区间校验（闭区间） */
function isIntInRange(value: number | undefined, min: number, max: number): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

/** 正整数校验（> 0） */
function isPositiveInt(value: number | undefined): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/** 数值是否为正（用于嵌套节点内可空数值的类型收窄） */
function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/** 数值回退：越界或非法取默认值 */
function numberOrDefault(value: unknown, min: number, max: number, fallback: number): number {
  const parsed = readNumber(value, fallback);
  return parsed < min || parsed > max ? fallback : parsed;
}

/**
 * 由配置装配 retrieval-config 端点请求用的 JSON 字符串。
 * <p>裁剪口径：重排关闭时只写 {@code {enabled:false}}（避免残留模型引用）；
 * 融合 RRF 只写 rrfK、WEIGHTED_SUM 只写三通道权重（两者互斥，均与后端校验器分支一致）。
 * 非法 / 缺省标量在装配时归一为默认值，保证产物必然通过后端校验。</p>
 * @param config 表单绑定的配置对象
 * @returns 后端要求的检索策略 JSON 字符串
 */
export function buildStrategyJson(config: RetrievalStrategyConfig): string {
  const strategyType = readString(config.strategyType, STRATEGY_NAIVE);
  const rerank = config.rerankConfig;
  const fusion = config.fusionConfig;
  const json: Record<string, unknown> = {
    strategyType: isInOptions(strategyType, RETRIEVAL_STRATEGIES) ? strategyType : STRATEGY_NAIVE,
    rewriteQuestion: config.rewriteQuestion === true,
    resultChunkCount: numberOrDefault(config.resultChunkCount, RESULT_CHUNK_COUNT_MIN, RESULT_CHUNK_COUNT_MAX,
      DEFAULT_RESULT_CHUNK_COUNT),
    similarThreshold: numberOrDefault(config.similarThreshold, SIMILAR_THRESHOLD_MIN, SIMILAR_THRESHOLD_MAX,
      SIMILAR_THRESHOLD_MIN),
    rerankConfig: buildRerankJson(rerank),
    fusionConfig: buildFusionJson(fusion),
  };
  return JSON.stringify(json);
}

/** 装配 rerankConfig 节点（关闭态不携带模型引用与 topK） */
function buildRerankJson(rerank: RetrievalStrategyConfig['rerankConfig']): Record<string, unknown> {
  if (rerank !== undefined && rerank.enabled === true) {
    const node: Record<string, unknown> = {
      enabled: true,
      modelProfileId: readString(rerank.modelProfileId, '').trim(),
    };
    if (isPositiveNumber(rerank.topK)) {
      node.topK = Math.trunc(rerank.topK);
    }
    return node;
  }
  return { enabled: false };
}

/** 装配 fusionConfig 节点（RRF 与加权求和互斥） */
function buildFusionJson(fusion: RetrievalStrategyConfig['fusionConfig']): Record<string, unknown> {
  const fusionType = readString(fusion?.fusionType, FUSION_RRF);
  if (fusionType === FUSION_WEIGHTED_SUM) {
    const weights: Record<string, number> = {};
    RETRIEVAL_CHANNELS.forEach((channel) => {
      const weight = fusion?.channelDenseWeight?.[channel.value];
      if (typeof weight === 'number' && Number.isFinite(weight)) {
        weights[channel.value] = weight;
      }
    });
    return { fusionType: FUSION_WEIGHTED_SUM, channelDenseWeight: weights };
  }
  const rrfK = typeof fusion?.rrfK === 'number' && Number.isFinite(fusion.rrfK) && fusion.rrfK > 0
    ? Math.trunc(fusion.rrfK)
    : DEFAULT_RRF_K;
  return { fusionType: FUSION_RRF, rrfK };
}

/**
 * 反解 retrievalStrategy JSON 字符串为可绑定表单的完整配置。
 * <p>逐字段防御：整体非法 JSON / 非对象 → 全量默认值；单字段缺失或越界 → 该字段默认值；
 * 枚举值不在字典内 → 回落默认枚举。嵌套配置与通道权重恒被补齐，调用方无需二次判空。</p>
 * @param raw 知识库详情中的 retrievalStrategy 字段（JSON 字符串，可能为空）
 * @returns 完整配置对象
 */
export function parseStrategyJson(raw: string | null | undefined): RetrievalDraft {
  const fallback = defaultRetrievalConfig();
  const root = parseJsonObject(raw);
  if (root === null) {
    return fallback;
  }
  const strategyType = readString(root.strategyType, fallback.strategyType);
  const rerankNode = readObject(root.rerankConfig);
  const fusionNode = readObject(root.fusionConfig);
  const fusionType = readString(fusionNode?.fusionType, fallback.fusionConfig.fusionType);
  return {
    strategyType: isInOptions(strategyType, RETRIEVAL_STRATEGIES) ? strategyType : STRATEGY_NAIVE,
    rewriteQuestion: readBoolean(root.rewriteQuestion, false),
    resultChunkCount: numberOrDefault(root.resultChunkCount, RESULT_CHUNK_COUNT_MIN, RESULT_CHUNK_COUNT_MAX,
      DEFAULT_RESULT_CHUNK_COUNT),
    similarThreshold: numberOrDefault(root.similarThreshold, SIMILAR_THRESHOLD_MIN, SIMILAR_THRESHOLD_MAX,
      SIMILAR_THRESHOLD_MIN),
    rerankConfig: {
      enabled: readBoolean(rerankNode?.enabled, false),
      modelProfileId: readString(rerankNode?.modelProfileId, ''),
      topK: isPositiveNumber(rerankNode?.topK) ? Math.trunc(rerankNode.topK) : DEFAULT_RERANK_TOP_K,
    },
    fusionConfig: {
      fusionType: isInOptions(fusionType, FUSION_TYPES) ? fusionType : FUSION_RRF,
      rrfK: Math.trunc(numberOrDefault(fusionNode?.rrfK, RRF_K_MIN, RRF_K_MAX, DEFAULT_RRF_K)),
      channelDenseWeight: parseChannelWeights(fusionNode?.channelDenseWeight),
    },
  };
}

/** 反解三通道权重（缺失或非数值回退默认权重） */
function parseChannelWeights(node: unknown): Record<string, number> {
  const weights = defaultChannelWeights();
  const source = readObject(node);
  if (source === null) {
    return weights;
  }
  RETRIEVAL_CHANNELS.forEach((channel) => {
    const value = source[channel.value];
    if (typeof value === 'number' && Number.isFinite(value)) {
      weights[channel.value] = value;
    }
  });
  return weights;
}

/**
 * 提交前置校验：字段级错误集合（键为字段路径，值为用户可读文案，空对象表示通过）。
 * <p>口径严格对齐 RetrievalConfigValidator：数量 1~100、阈值 0~1、重排开启必须有模型、
 * RRF 的 K 必须 > 0、加权求和的三通道权重必填且落在开区间 (0,1)（后端对越界值直接 400）。</p>
 * @param config 表单绑定的配置对象
 * @returns 字段错误映射
 */
export function validateRetrievalConfig(config: RetrievalStrategyConfig): Record<string, string> {
  const errors: Record<string, string> = {};

  const strategyType = readString(config.strategyType, '');
  if (!isInOptions(strategyType, RETRIEVAL_STRATEGIES)) {
    errors.strategyType = '请选择检索策略（NAIVE 或 MIX）';
  }

  if (!isIntInRange(config.resultChunkCount, RESULT_CHUNK_COUNT_MIN, RESULT_CHUNK_COUNT_MAX)) {
    errors.resultChunkCount = `结果返回数量须为 ${RESULT_CHUNK_COUNT_MIN}~${RESULT_CHUNK_COUNT_MAX} 的整数`;
  }

  const threshold = config.similarThreshold;
  if (typeof threshold !== 'number' || !Number.isFinite(threshold)
    || threshold < SIMILAR_THRESHOLD_MIN || threshold > SIMILAR_THRESHOLD_MAX) {
    errors.similarThreshold = `相似度阈值须为 ${SIMILAR_THRESHOLD_MIN}~${SIMILAR_THRESHOLD_MAX} 之间的数值`;
  }

  const rerank = config.rerankConfig;
  if (rerank !== undefined && rerank.enabled === true && readString(rerank.modelProfileId, '').trim() === '') {
    errors['rerankConfig.modelProfileId'] = '开启重排时必须选择重排模型';
  }
  if (rerank?.topK !== undefined && !isPositiveInt(rerank.topK)) {
    errors['rerankConfig.topK'] = '重排 topK 须为大于 0 的整数（留空表示不限制）';
  }

  const fusion = config.fusionConfig;
  const fusionType = readString(fusion?.fusionType, '');
  if (!isInOptions(fusionType, FUSION_TYPES)) {
    errors['fusionConfig.fusionType'] = '请选择融合方式';
    return errors;
  }
  if (fusionType === FUSION_RRF) {
    if (!isPositiveInt(fusion?.rrfK)) {
      errors['fusionConfig.rrfK'] = `RRF 常数 K 必须大于 0（默认 ${DEFAULT_RRF_K}）`;
    }
    return errors;
  }
  RETRIEVAL_CHANNELS.forEach((channel) => {
    const key = `fusionConfig.channelDenseWeight.${channel.value}`;
    const weight = fusion?.channelDenseWeight?.[channel.value];
    if (weight === undefined || weight === null) {
      errors[key] = `请填写「${channel.label}」通道的稠密权重`;
      return;
    }
    if (!Number.isFinite(weight) || weight <= CHANNEL_WEIGHT_FLOOR || weight >= CHANNEL_WEIGHT_CEIL) {
      errors[key] = `「${channel.label}」通道权重必须落在 ${CHANNEL_WEIGHT_FLOOR}~${CHANNEL_WEIGHT_CEIL} 之间（不含两端）`;
    }
  });
  return errors;
}

/**
 * 校验单张检索附图（数量 / 类型白名单 / 体积），返回拒绝原因；通过返回 null。
 * <p>口径对齐后端 QueryImageValidator 的入参规则，超限在前端即拒绝，不发起请求。</p>
 * @param file 待加入的附件
 * @param currentCount 当前已挂载的附件数量
 * @returns 拒绝原因文案；可加入时为 null
 */
export function validateQueryImage(file: QueryImageDraft, currentCount: number): string | null {
  if (currentCount >= MAX_QUERY_IMAGES) {
    return `单次检索最多附带 ${MAX_QUERY_IMAGES} 张附图`;
  }
  const contentType = normalizeContentType(file.contentType);
  if (!QUERY_IMAGE_CONTENT_TYPES.includes(contentType)) {
    return `附图「${file.name}」类型不受支持，仅允许 ${QUERY_IMAGE_CONTENT_TYPES.join('、')}`;
  }
  const bytes = decodedByteLength(file.data);
  if (bytes > MAX_QUERY_IMAGE_BYTES) {
    return `附图「${file.name}」解码后 ${bytes} bytes，超过单图 ${Math.floor(
      MAX_QUERY_IMAGE_BYTES / 1024 / 1024,
    )}MB 上限`;
  }
  return null;
}

/** 归一附图内容类型（trim + 小写，对齐后端大小写不敏感口径） */
export function normalizeContentType(contentType: string | null | undefined): string {
  return readString(contentType, '').trim().toLowerCase();
}

/**
 * 由 data URL 剥出纯 base64 载荷（后端 QueryImageDTO.data 要求不含 data: 前缀）。
 * @param dataUrl FileReader.readAsDataURL 的产物
 * @returns 纯 base64 串；无逗号分隔时原样返回
 */
export function stripDataUrlPrefix(dataUrl: string): string {
  const index = dataUrl.indexOf(',');
  return index < 0 ? dataUrl : dataUrl.slice(index + 1);
}

/**
 * 估算 base64 载荷解码后的字节数。
 * <p>口径：floor(长度 × 3 ÷ 4) 扣除尾部 padding 字符数，与后端解码字节数一致。</p>
 * @param data base64 载荷（不含 data: 前缀）
 * @returns 解码后字节数
 */
export function decodedByteLength(data: string | null | undefined): number {
  const raw = readString(data, '').trim();
  if (raw === '') {
    return 0;
  }
  let padding = 0;
  if (raw.endsWith('==')) {
    padding = 2;
  } else if (raw.endsWith('=')) {
    padding = 1;
  }
  return Math.max(0, Math.floor((raw.length * 3) / 4) - padding);
}
