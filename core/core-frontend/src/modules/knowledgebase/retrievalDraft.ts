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
  /** 通道召回数量配置（可编辑态；NAIVE 序列化时不提交图谱数量） */
  channelQueryCountConfig: RetrievalDraftChannelQueryCount;
}

/** 草稿态通道召回数量（三值恒存在，滑块直接绑定；1~100，默认 20） */
export interface RetrievalDraftChannelQueryCount {
  /** 向量通道召回数量 */
  vectorQueryCount: number;
  /** BM25 通道召回数量 */
  bm25QueryCount: number;
  /** 图谱通道召回数量（实体/关系路共用单值；NAIVE 下保存但序列化不提交） */
  graphQueryCount: number;
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
  /** 三通道稠密权重（仅加权分支提交，键为通道枚举名；0.1 步进、合计恒为 1.0） */
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
export const DEFAULT_RESULT_CHUNK_COUNT = 50;
/** RRF 常数 K 默认值（对齐后端 FusionStrategyConfig.DEFAULT_RRF_K） */
export const DEFAULT_RRF_K = 60;
/** RRF 常数 K 下限（后端要求严格大于 0） */
export const RRF_K_MIN = 1;
/** RRF 常数 K 上限（安全整数上界，仅防脏数据） */
export const RRF_K_MAX = Number.MAX_SAFE_INTEGER;
/** 重排保留条数下限（topK 为正整数，留空表示不限制） */
export const RERANK_TOP_K_MIN = 1;
/** 重排保留条数默认值（对齐后端 RetrievalConstants.RERANK_TOP_K_DEFAULT=50 唯一口径） */
export const DEFAULT_RERANK_TOP_K = 50;
/** 通道召回数量下限（对齐后端 ChannelQueryCountDTO 1~100 校验） */
export const QUERY_COUNT_MIN = 1;
/** 通道召回数量上限（对齐后端 ChannelQueryCountDTO.QUERY_COUNT_MAX） */
export const QUERY_COUNT_MAX = 100;
/** 通道召回数量默认值（对齐后端 RetrievalQuery.DEFAULT_QUERY_COUNT） */
export const DEFAULT_QUERY_COUNT = 20;
/** 通道稠密权重输入下限（后端校验为开区间 (0,1)，控件边界仅允许贴近 0） */
export const CHANNEL_WEIGHT_FLOOR = 0;
/** 通道稠密权重输入上限（后端校验为开区间 (0,1)，控件边界仅允许贴近 1） */
export const CHANNEL_WEIGHT_CEIL = 1;
/** 单通道权重下限（1 刻度 = 0.1；后端要求开区间 (0,1)，0 权通道不可提交） */
export const CHANNEL_WEIGHT_MIN = 0.1;
/** 单通道权重上限（8 刻度 = 0.8；其余两通道各至少保留 1 刻度） */
export const CHANNEL_WEIGHT_MAX = 0.8;
/** 通道权重步进（0.1，即 1 个刻度，滑块单步） */
export const CHANNEL_WEIGHT_STEP = 0.1;
/** 权重刻度总数：三通道权重合计恒为 10 刻度 = 1.0 */
export const CHANNEL_WEIGHT_TOTAL_TICKS = 10;
/** 权重合计比较容差（0.1 步长输入下规避 0.7+0.2+0.1 这类二进制误差误判） */
const WEIGHT_SUM_EPSILON = 1e-9;
/** 加权求和各通道默认权重（键为 RetrievalChannel 枚举名；合计 1.0） */
export const DEFAULT_CHANNEL_WEIGHTS: Record<string, number> = {
  VECTOR: 0.4,
  BM25: 0.3,
  GRAPH: 0.3,
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
 * 权重转整数刻度（就近取整并夹取到 0.1~0.8，即 1~8 刻度）。
 * @param weight 权重值（可能为脏数据）
 * @param fallback 非法值回落的权重（一般为该通道默认权重；后端拒绝 0 权通道，故最小可用为 1 刻度）
 * @returns 整数刻度（1~8）
 */
function toWeightTicks(weight: unknown, fallback: number): number {
  const value = typeof weight === 'number' && Number.isFinite(weight) ? weight : fallback;
  const ticks = Math.round(value * 10);
  const minTicks = Math.round(CHANNEL_WEIGHT_MIN * 10);
  const maxTicks = Math.round(CHANNEL_WEIGHT_MAX * 10);
  return Math.max(minTicks, Math.min(ticks, maxTicks));
}

/**
 * 按比例把 total 个刻度分配给定通道，每通道保底 1 刻度。
 * <p>全程整数运算（先各留 1 刻度的底，剩余额度按当前刻度的比例用最大余数法分配），
 * 避免 0.1 在双精度下的累加误差；比例全为 0 时按等分处理，余量按小数部分从大到小补齐，
 * 同余量保持通道顺序，保证同一入参恒得同一结果。</p>
 * @param ticks 各通道当前刻度（非负整数）
 * @param total 待分配刻度总数
 * @returns 分配后的各通道刻度（和恒为 total，每通道 ≥ 1）
 */
function allocateTicks(ticks: number[], total: number): number[] {
  // 已是目标总和且每路都在合法区间的脏数据，原样返回（回显/往返不产生额外漂移）
  const sum = ticks.reduce((acc, value) => acc + value, 0);
  if (sum === total && ticks.every((value) => value >= 1)) {
    return ticks;
  }
  const extra = Math.max(0, total - ticks.length);
  const shares = ticks.map((value) => (sum > 0 ? (value / sum) * extra : extra / ticks.length));
  const allocated = shares.map((share) => Math.floor(share));
  let remainder = extra - allocated.reduce((acc, value) => acc + value, 0);
  const order = shares
    .map((share, index) => ({ index, fraction: share - Math.floor(share) }))
    .sort((left, right) => right.fraction - left.fraction || left.index - right.index);
  for (const item of order) {
    if (remainder <= 0) {
      break;
    }
    allocated[item.index] += 1;
    remainder -= 1;
  }
  return ticks.map((_, index) => 1 + allocated[index]);
}

/** 刻度数组转为「通道 → 权重」映射（键顺序对齐通道枚举，单值均为 0.1 的整数倍） */
function toWeightRecord(channels: string[], ticks: number[]): Record<string, number> {
  const record: Record<string, number> = {};
  channels.forEach((channel, index) => {
    record[channel] = ticks[index] / 10;
  });
  return record;
}

/**
 * 三通道权重联动调整：把目标通道设为给定权重，其余通道按当前比例分摊剩余额度。
 * <p>口径：三通道权重合计恒为 1.0（10 个 0.1 刻度），单通道落在 0.1~0.8。
 * 目标权重按 0.1 就近取整并夹取边界后，其余两通道自动让位/回补，
 * 因此合计永远不会大于 1，也不会产生后端拒绝的 0 权通道。</p>
 * @param weights 当前三通道权重（键为通道枚举名；缺失或脏值按默认权重处理）
 * @param channel 被调整的通道枚举名（不在三通道内时返回归一后的原权重）
 * @param nextWeight 目标权重（0.1 步进；非数值时保持该通道当前刻度）
 * @returns 调整后的三通道权重（合计恒为 1.0）
 */
export function rebalanceChannelWeights(
  weights: Record<string, number> | undefined,
  channel: string,
  nextWeight: number,
): Record<string, number> {
  const channels = RETRIEVAL_CHANNELS.map((item) => item.value);
  const ticks = channels.map((value) => toWeightTicks(weights?.[value], DEFAULT_CHANNEL_WEIGHTS[value]));
  const targetIndex = channels.indexOf(channel);
  if (targetIndex < 0) {
    return toWeightRecord(channels, allocateTicks(ticks, CHANNEL_WEIGHT_TOTAL_TICKS));
  }
  const targetTicks = typeof nextWeight === 'number' && Number.isFinite(nextWeight)
    ? toWeightTicks(nextWeight, ticks[targetIndex])
    : ticks[targetIndex];
  const others = ticks.filter((_, index) => index !== targetIndex);
  // 目标通道固定，剩余额度按另外两路当前比例分配（每路保底 1 刻度）
  const allocated = allocateTicks(others, CHANNEL_WEIGHT_TOTAL_TICKS - targetTicks);
  const result = ticks.slice();
  result[targetIndex] = targetTicks;
  let cursor = 0;
  channels.forEach((_, index) => {
    if (index === targetIndex) {
      return;
    }
    result[index] = allocated[cursor];
    cursor += 1;
  });
  return toWeightRecord(channels, result);
}

/**
 * 默认检索策略配置：NAIVE / 不改写 / 结果数量 20 / 阈值不过滤（0）/ 重排关闭（topK 预填 50）/
 * RRF（K=60）/ 三通道召回数量各 20。
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
    channelQueryCountConfig: {
      vectorQueryCount: DEFAULT_QUERY_COUNT,
      bm25QueryCount: DEFAULT_QUERY_COUNT,
      graphQueryCount: DEFAULT_QUERY_COUNT,
    },
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
 * 通道数量节点：NAIVE 不提交图谱数量（该策略不消费图谱通道），向量/BM25 恒提交；
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
    channelQueryCountConfig: buildChannelQueryCountJson(strategyType, config.channelQueryCountConfig),
  };
  return JSON.stringify(json);
}

/** 装配 channelQueryCountConfig 节点（NAIVE 不提交图谱数量；各值越界/非法归一默认 20） */
function buildChannelQueryCountJson(
  strategyType: string,
  counts: RetrievalStrategyConfig['channelQueryCountConfig'],
): Record<string, unknown> {
  const node: Record<string, unknown> = {
    vectorQueryCount: numberOrDefault(counts?.vectorQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
      DEFAULT_QUERY_COUNT),
    bm25QueryCount: numberOrDefault(counts?.bm25QueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
      DEFAULT_QUERY_COUNT),
  };
  if (strategyType === STRATEGY_MIX) {
    node.graphQueryCount = numberOrDefault(counts?.graphQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
      DEFAULT_QUERY_COUNT);
  }
  return node;
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

/** 装配 fusionConfig 节点（RRF 与加权求和互斥；加权权重归一为 0.1 刻度、合计 1.0） */
function buildFusionJson(fusion: RetrievalStrategyConfig['fusionConfig']): Record<string, unknown> {
  const fusionType = readString(fusion?.fusionType, FUSION_RRF);
  if (fusionType === FUSION_WEIGHTED_SUM) {
    const channels = RETRIEVAL_CHANNELS.map((channel) => channel.value);
    const ticks = channels.map((channel) => toWeightTicks(
      fusion?.channelDenseWeight?.[channel],
      DEFAULT_CHANNEL_WEIGHTS[channel],
    ));
    return {
      fusionType: FUSION_WEIGHTED_SUM,
      channelDenseWeight: toWeightRecord(channels, allocateTicks(ticks, CHANNEL_WEIGHT_TOTAL_TICKS)),
    };
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
  const countNode = readObject(root.channelQueryCountConfig);
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
    channelQueryCountConfig: {
      vectorQueryCount: numberOrDefault(countNode?.vectorQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
        DEFAULT_QUERY_COUNT),
      bm25QueryCount: numberOrDefault(countNode?.bm25QueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
        DEFAULT_QUERY_COUNT),
      graphQueryCount: numberOrDefault(countNode?.graphQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX,
        DEFAULT_QUERY_COUNT),
    },
  };
}

/** 反解三通道权重（缺失或非数值回退默认权重，再按 0.1 刻度归一到合计 1.0） */
function parseChannelWeights(node: unknown): Record<string, number> {
  const source = readObject(node);
  const channels = RETRIEVAL_CHANNELS.map((channel) => channel.value);
  const ticks = channels.map((channel) => toWeightTicks(
    source?.[channel],
    DEFAULT_CHANNEL_WEIGHTS[channel],
  ));
  return toWeightRecord(channels, allocateTicks(ticks, CHANNEL_WEIGHT_TOTAL_TICKS));
}

/**
 * 提交前置校验：字段级错误集合（键为字段路径，值为用户可读文案，空对象表示通过）。
 * <p>口径严格对齐 RetrievalConfigValidator：数量 1~100、阈值 0~1、重排开启必须有模型、
 * RRF 的 K 必须 > 0、加权求和的三通道权重必填且落在开区间 (0,1)、三路权重合计不得大于 1
 * （后端对越界值直接 400）。通道召回数量同样按 1~100 整数校验；图谱数量仅在 MIX 策略下校验
 * （NAIVE 序列化不提交该字段，界面上也不渲染对应滑块，校验隐藏字段无意义）。</p>
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

  const counts = config.channelQueryCountConfig;
  if (!isIntInRange(counts?.vectorQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX)) {
    errors['channelQueryCountConfig.vectorQueryCount'] =
      `VECTOR 通道召回数量须为 ${QUERY_COUNT_MIN}~${QUERY_COUNT_MAX} 的整数`;
  }
  if (!isIntInRange(counts?.bm25QueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX)) {
    errors['channelQueryCountConfig.bm25QueryCount'] =
      `BM25 通道召回数量须为 ${QUERY_COUNT_MIN}~${QUERY_COUNT_MAX} 的整数`;
  }
  if (strategyType === STRATEGY_MIX && !isIntInRange(counts?.graphQueryCount, QUERY_COUNT_MIN, QUERY_COUNT_MAX)) {
    errors['channelQueryCountConfig.graphQueryCount'] =
      `GRAPH 通道召回数量须为 ${QUERY_COUNT_MIN}~${QUERY_COUNT_MAX} 的整数`;
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
      errors[key] = `请填写「${channel.label}」的稠密权重`;
      return;
    }
    if (!Number.isFinite(weight) || weight <= CHANNEL_WEIGHT_FLOOR || weight >= CHANNEL_WEIGHT_CEIL) {
      errors[key] = `「${channel.label}」权重须落在 ${CHANNEL_WEIGHT_FLOOR}~${CHANNEL_WEIGHT_CEIL} 之间（不含两端，步进 ${CHANNEL_WEIGHT_STEP}）`;
    }
  });
  // 三路权重是占比：已提供通道的合计不得大于 1（与后端 RetrievalConfigValidator 同口径）
  const weightTotal = RETRIEVAL_CHANNELS.reduce((total, channel) => {
    const weight = fusion?.channelDenseWeight?.[channel.value];
    // 单值越界已由上面的分支单独报错，此处只累加合法权重，避免同一问题重复提示
    if (typeof weight !== 'number' || !Number.isFinite(weight)) {
      return total;
    }
    return weight > CHANNEL_WEIGHT_FLOOR && weight < CHANNEL_WEIGHT_CEIL ? total + weight : total;
  }, 0);
  if (weightTotal > CHANNEL_WEIGHT_CEIL + WEIGHT_SUM_EPSILON) {
    errors['fusionConfig.channelDenseWeight'] =
      `三路权重合计不能大于 ${CHANNEL_WEIGHT_CEIL}（当前 ${weightTotal.toFixed(2)}）`;
  }
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
