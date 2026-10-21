import {
  CHUNK_MODES,
  DEDUP_ACTIONS,
  DEDUP_MATCH_RULES,
  KB_LANGUAGES,
  PARSE_PROVIDERS,
} from '@/shared/enums/dict';
import type { KnowledgeBaseCreatePayload, KnowledgeBaseUpdatePayload, KnowledgeBaseWire } from './api/knowledgeBase';

/**
 * 知识库草稿（Draft）与后端线格式（JSON 字符串二次编码）之间的纯装配/反解/校验层。
 * <p>后端 7 个配置字段以 JSON 字符串传输，本模块负责：draft ↔ wire JSON 双向转换、
 * 提交前的字段级前置校验（口径严格对齐后端各 Validator）。纯函数、无 Vue 依赖，便于单测。</p>
 */

/** MinerU 解析引擎参数（engineConfig.params，仅 MINERU 携带） */
export interface MineruParams {
  /** MinerU 服务地址（MINERU 必填） */
  apiBaseUrl: string;
  /** 解析后端（如 pipeline） */
  backend: string;
  /** 解析方法（auto / txt / ocr） */
  parseMethod: string;
  /** OCR 语言列表（逗号分隔串或列表，如 ch_en） */
  language: string;
  /** 解析质量档位 */
  quality: string;
}

/** GENERAL 分块模式参数（仅 GENERAL 携带） */
export interface GeneralChunkParams {
  /** 分块 token 预算（1~2048，默认 512） */
  chunkTokenNum: number;
  /** 重叠比例（0~30，默认 0） */
  overlappedPercent: number;
  /** 分隔符（非空，默认换行） */
  delimiter: string;
}

/** 上传去重策略 */
export interface DedupDraft {
  /** 匹配轴 */
  matchRule: string;
  /** 冲突动作（matchRule=NONE 时不生效） */
  conflictAction: string;
}

/**
 * 知识库表单草稿：UI 直接绑定的扁平结构，与 wire JSON 一一对应。
 */
export interface KbDraft {
  /** 名称（1~128，全局唯一） */
  name: string;
  /** 描述 */
  description: string;
  /** 语言（十一全名，默认 Chinese） */
  language: string;
  /** 嵌入模型 profileId（创建后锁定） */
  embeddingProfileId: string;
  /** 多模态模型 profileId（必填） */
  multiModelProfileId: string;
  /** 解析引擎提供方（LOCAL / MINERU） */
  parseProvider: string;
  /** MinerU 解析参数 */
  mineru: MineruParams;
  /** 分块模式（七枚举） */
  chunkMode: string;
  /** GENERAL 分块参数 */
  general: GeneralChunkParams;
  /** 去重策略 */
  dedup: DedupDraft;
  /** 自定义实体类型名称列表（≤10） */
  entityTypes: string[];
}

/** 引擎类型：本期仅文档引擎 */
export const ENGINE_TYPE_DOCUMENT = 'DOCUMENT_ENGINE';

/** GENERAL 分块 token 上限（闭区间，对齐后端 CHUNK_TOKEN_NUM_MAX） */
export const CHUNK_TOKEN_NUM_MAX = 2048;
/** GENERAL 分块 token 下限（闭区间：前端按 1 起，后端开区间 (0,2048]） */
export const CHUNK_TOKEN_NUM_MIN = 1;
/** GENERAL 重叠比例下限（闭区间） */
export const OVERLAPPED_PERCENT_MIN = 0;
/** GENERAL 重叠比例上限（闭区间） */
export const OVERLAPPED_PERCENT_MAX = 30;
/** 名称最大长度 */
export const NAME_MAX_LENGTH = 128;
/** 自定义实体类型数量上限 */
export const MAX_ENTITY_TYPES = 10;
/** 实体类型名称最小长度 */
export const ENTITY_TYPE_MIN_LENGTH = 2;
/** 实体类型名称最大长度 */
export const ENTITY_TYPE_MAX_LENGTH = 30;
/** 实体类型名称合法字符：中英文字母、数字、下划线 */
const ENTITY_TYPE_NAME_PATTERN = /^[\u4e00-\u9fa5A-Za-z0-9_]+$/;

/** 去重匹配轴：不检测 */
const MATCH_RULE_NONE = 'NONE';
/** 解析引擎提供方：MinerU */
const PROVIDER_MINERU = 'MINERU';
/** 分块模式：通用 */
const CHUNK_MODE_GENERAL = 'GENERAL';

/**
 * 内置实体类型（从字典 'PERSON（人）' 形态解析出英文代码与中文名，供重名校验，保持单一真相源）。
 */
const BUILTIN_ENTITY_CODES = new Set<string>([
  'PERSON', 'ORGANISM', 'ORGANIZATION', 'LOCATION', 'EVENT', 'CONCEPT',
  'METHOD', 'CONTENT', 'DATA', 'ARTIFACT', 'NATURAL_OBJECT', 'OTHER',
]);
/** 内置实体类型中文名（含强制保留的「其他」） */
const BUILTIN_ENTITY_CN_NAMES = new Set<string>([
  '人', '生物', '组织', '地点', '事件', '概念', '方法', '内容', '数据', '制品', '自然物', '其他',
]);

/** 默认 GENERAL 分块参数（库级草稿与文档级策略回显共用的单一真相源） */
export function defaultGeneral(): GeneralChunkParams {
  return { chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' };
}

/** 默认 MinerU 参数 */
function defaultMineru(): MineruParams {
  return { apiBaseUrl: '', backend: '', parseMethod: '', language: '', quality: '' };
}

/**
 * 新建草稿：所有字段取业务默认值（LOCAL 解析 + GENERAL 分块 + 文件名或内容去重 + Chinese）。
 * @returns 可直接绑定表单的草稿对象
 */
export function defaultDraft(): KbDraft {
  return {
    name: '',
    description: '',
    language: 'Chinese',
    embeddingProfileId: '',
    multiModelProfileId: '',
    parseProvider: 'LOCAL',
    mineru: defaultMineru(),
    chunkMode: CHUNK_MODE_GENERAL,
    general: defaultGeneral(),
    dedup: { matchRule: 'BY_NAME_OR_CONTENT', conflictAction: 'SKIP' },
    entityTypes: [],
  };
}

/** 安全解析 JSON 字符串为对象，非法/非对象返回 null（防御存量脏数据） */
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

/** 读取字符串值（非字符串或空回退默认） */
function readString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

/** 读取整数值（非有限数回退默认，并截断小数） */
function readInt(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.trunc(value);
  }
  return fallback;
}

/** 读取嵌套对象（非对象返回 null） */
function readObject(value: unknown): Record<string, unknown> | null {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
}

/** 仅当值非空白时写入对象（避免提交空字符串覆盖后端默认） */
function putIfPresent(target: Record<string, unknown>, key: string, value: string): void {
  if (value.trim() !== '') {
    target[key] = value;
  }
}

/** 值是否命中给定选项集合（用于枚举白名单回退） */
function isInOptions(value: string, options: Array<{ value: string }>): boolean {
  return options.some((option) => option.value === value);
}

/**
 * 装配 ragEngineConfig 对象（parseEngine + chunkStrategy）。
 * <p>chunkStrategy.modeConfig.params 仅 GENERAL 携带；parseEngine.engineConfig.params 仅 MINERU 携带非空键。</p>
 */
function buildRagEngineConfig(draft: KbDraft): Record<string, unknown> {
  const parseParams: Record<string, unknown> = {};
  if (draft.parseProvider === PROVIDER_MINERU) {
    putIfPresent(parseParams, 'apiBaseUrl', draft.mineru.apiBaseUrl);
    putIfPresent(parseParams, 'backend', draft.mineru.backend);
    putIfPresent(parseParams, 'parseMethod', draft.mineru.parseMethod);
    putIfPresent(parseParams, 'language', draft.mineru.language);
    putIfPresent(parseParams, 'quality', draft.mineru.quality);
  }
  const chunkStrategy: Record<string, unknown> = { chunkMode: draft.chunkMode };
  if (draft.chunkMode === CHUNK_MODE_GENERAL) {
    chunkStrategy.modeConfig = {
      params: {
        chunk_token_num: draft.general.chunkTokenNum,
        overlapped_percent: draft.general.overlappedPercent,
        delimiter: draft.general.delimiter,
      },
    };
  }
  return {
    engineType: ENGINE_TYPE_DOCUMENT,
    parseEngine: { provider: draft.parseProvider, engineConfig: { params: parseParams } },
    chunkStrategy,
  };
}

/** 装配 dedupPolicy 对象（NONE 时 conflictAction 不携带，对齐后端强制置 null） */
function buildDedupPolicy(draft: KbDraft): Record<string, unknown> {
  const policy: Record<string, unknown> = { matchRule: draft.dedup.matchRule };
  if (draft.dedup.matchRule !== MATCH_RULE_NONE && draft.dedup.conflictAction.trim() !== '') {
    policy.conflictAction = draft.dedup.conflictAction;
  }
  return policy;
}

/** 装配 entityTypeConfig 对象（空集合返回 null，表示不携带该配置） */
function buildEntityTypeConfig(draft: KbDraft): Record<string, unknown> | null {
  if (draft.entityTypes.length === 0) {
    return null;
  }
  return { entityTypes: draft.entityTypes.map((entityType) => ({ entityType })) };
}

/**
 * 由草稿装配创建请求（配置字段 JSON.stringify 二次编码）。
 * <p>必填项假定调用方已通过 validateDraft 拦截；本函数只做序列化装配。</p>
 * @param draft 表单草稿
 * @returns 后端 CreateKnowledgeBaseRequest 同构载荷
 */
export function buildCreateRequest(draft: KbDraft): KnowledgeBaseCreatePayload {
  const payload: KnowledgeBaseCreatePayload = {
    name: draft.name,
    description: draft.description,
    language: draft.language,
    ragEngineConfig: JSON.stringify(buildRagEngineConfig(draft)),
    dedupPolicy: JSON.stringify(buildDedupPolicy(draft)),
    embeddingConfig: JSON.stringify({ modelProfileId: draft.embeddingProfileId }),
    multiModelConfig: JSON.stringify({ modelProfileId: draft.multiModelProfileId }),
  };
  const entityConfig = buildEntityTypeConfig(draft);
  if (entityConfig !== null) {
    payload.entityTypeConfig = JSON.stringify(entityConfig);
  }
  return payload;
}

/**
 * 由草稿装配更新请求（不携带 embeddingConfig：后端创建后锁定、更新静默忽略）。
 * @param draft 表单草稿
 * @returns 后端 UpdateKnowledgeBaseRequest 同构载荷
 */
export function buildUpdateRequest(draft: KbDraft): KnowledgeBaseUpdatePayload {
  const payload: KnowledgeBaseUpdatePayload = {
    name: draft.name,
    description: draft.description,
    language: draft.language,
    ragEngineConfig: JSON.stringify(buildRagEngineConfig(draft)),
    dedupPolicy: JSON.stringify(buildDedupPolicy(draft)),
    multiModelConfig: JSON.stringify({ modelProfileId: draft.multiModelProfileId }),
  };
  const entityConfig = buildEntityTypeConfig(draft);
  if (entityConfig !== null) {
    payload.entityTypeConfig = JSON.stringify(entityConfig);
  }
  return payload;
}

/** 从 ragEngineConfig JSON 反解草稿字段（就地覆盖到 draft） */
function applyRagEngineConfig(draft: KbDraft, rag: Record<string, unknown> | null): void {
  if (rag === null) {
    return;
  }
  const parseEngine = readObject(rag.parseEngine);
  if (parseEngine !== null) {
    const provider = readString(parseEngine.provider, draft.parseProvider);
    if (isInOptions(provider, PARSE_PROVIDERS)) {
      draft.parseProvider = provider;
    }
    const engineParams = readObject(readObject(parseEngine.engineConfig)?.params);
    if (engineParams !== null) {
      draft.mineru = {
        apiBaseUrl: readString(engineParams.apiBaseUrl),
        backend: readString(engineParams.backend),
        parseMethod: readString(engineParams.parseMethod),
        language: readString(engineParams.language),
        quality: readString(engineParams.quality),
      };
    }
  }
  const chunkStrategy = readObject(rag.chunkStrategy);
  if (chunkStrategy !== null) {
    const mode = readString(chunkStrategy.chunkMode, draft.chunkMode);
    if (isInOptions(mode, CHUNK_MODES)) {
      draft.chunkMode = mode;
    }
    const params = readObject(readObject(chunkStrategy.modeConfig)?.params);
    if (params !== null) {
      draft.general = {
        chunkTokenNum: clampInt(readInt(params.chunk_token_num, draft.general.chunkTokenNum), CHUNK_TOKEN_NUM_MIN, CHUNK_TOKEN_NUM_MAX, 512),
        overlappedPercent: clampInt(readInt(params.overlapped_percent, draft.general.overlappedPercent), OVERLAPPED_PERCENT_MIN, OVERLAPPED_PERCENT_MAX, 0),
        delimiter: readString(params.delimiter, draft.general.delimiter),
      };
    }
  }
}

/** 整数夹取：越界或非法回退 fallback */
function clampInt(value: number, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value) || value < min || value > max) {
    return fallback;
  }
  return value;
}

/**
 * 由后端线格式响应反解草稿（逐字段防御：JSON 解析失败回退默认值）。
 * @param wire 知识库响应
 * @returns 可继续编辑的草稿对象
 */
export function draftFromKb(wire: KnowledgeBaseWire): KbDraft {
  const draft = defaultDraft();
  draft.name = readString(wire.name);
  draft.description = readString(wire.description);
  if (isInOptions(readString(wire.language), KB_LANGUAGES)) {
    draft.language = readString(wire.language);
  }
  applyRagEngineConfig(draft, parseJsonObject(wire.ragEngineConfig));

  const embedding = parseJsonObject(wire.embeddingConfig);
  draft.embeddingProfileId = embedding === null ? '' : readString(embedding.modelProfileId);
  const multi = parseJsonObject(wire.multiModelConfig);
  draft.multiModelProfileId = multi === null ? '' : readString(multi.modelProfileId);

  const dedup = parseJsonObject(wire.dedupPolicy);
  if (dedup !== null) {
    const matchRule = readString(dedup.matchRule, draft.dedup.matchRule);
    if (isInOptions(matchRule, DEDUP_MATCH_RULES)) {
      draft.dedup.matchRule = matchRule;
    }
    const conflictAction = readString(dedup.conflictAction, draft.dedup.conflictAction);
    if (isInOptions(conflictAction, DEDUP_ACTIONS)) {
      draft.dedup.conflictAction = conflictAction;
    }
  }

  const entity = parseJsonObject(wire.entityTypeConfig);
  if (entity !== null && Array.isArray(entity.entityTypes)) {
    draft.entityTypes = (entity.entityTypes as unknown[])
      .map((item) => readString(readObject(item)?.entityType))
      .filter((name) => name !== '');
  }
  return draft;
}

/** 校验实体类型单条名称：返回错误信息（合法返回 null） */
function validateEntityName(name: string): string | null {
  const trimmed = name.trim();
  if (trimmed.length < ENTITY_TYPE_MIN_LENGTH || trimmed.length > ENTITY_TYPE_MAX_LENGTH) {
    return `实体类型「${name}」长度须在 ${ENTITY_TYPE_MIN_LENGTH}~${ENTITY_TYPE_MAX_LENGTH} 个字符之间`;
  }
  if (!ENTITY_TYPE_NAME_PATTERN.test(trimmed)) {
    return `实体类型「${name}」仅允许中英文字母、数字与下划线`;
  }
  if (BUILTIN_ENTITY_CODES.has(trimmed.toUpperCase()) || BUILTIN_ENTITY_CN_NAMES.has(trimmed)) {
    return `实体类型「${name}」不得与内置类型重名（「其他」为强制保留类型）`;
  }
  return null;
}

/**
 * 提交前置校验：字段级错误集合（键为字段名，值为用户可读错误信息，空对象表示通过）。
 * <p>口径严格对齐后端各 Validator，避免无谓请求。</p>
 * @param draft 表单草稿
 * @returns 字段错误映射
 */
export function validateDraft(draft: KbDraft): Record<string, string> {
  const errors: Record<string, string> = {};

  const name = draft.name.trim();
  if (name === '') {
    errors.name = '知识库名称不能为空';
  } else if (name.length > NAME_MAX_LENGTH) {
    errors.name = `知识库名称不能超过 ${NAME_MAX_LENGTH} 个字符`;
  }

  if (draft.embeddingProfileId.trim() === '') {
    errors.embeddingProfileId = '请选择嵌入模型';
  }
  if (draft.multiModelProfileId.trim() === '') {
    errors.multiModelProfileId = '请选择多模态模型';
  }
  if (!isInOptions(draft.language, KB_LANGUAGES)) {
    errors.language = '请选择合法的语言';
  }

  if (draft.parseProvider === PROVIDER_MINERU && draft.mineru.apiBaseUrl.trim() === '') {
    errors['mineru.apiBaseUrl'] = '使用 MinerU 解析时必须填写服务地址';
  }

  if (draft.chunkMode === CHUNK_MODE_GENERAL) {
    const token = draft.general.chunkTokenNum;
    if (!Number.isInteger(token) || token < CHUNK_TOKEN_NUM_MIN || token > CHUNK_TOKEN_NUM_MAX) {
      errors['general.chunkTokenNum'] = `分块 token 预算须为 ${CHUNK_TOKEN_NUM_MIN}~${CHUNK_TOKEN_NUM_MAX} 的整数`;
    }
    const overlap = draft.general.overlappedPercent;
    if (!Number.isInteger(overlap) || overlap < OVERLAPPED_PERCENT_MIN || overlap > OVERLAPPED_PERCENT_MAX) {
      errors['general.overlappedPercent'] = `重叠比例须为 ${OVERLAPPED_PERCENT_MIN}~${OVERLAPPED_PERCENT_MAX} 的整数`;
    }
    if (draft.general.delimiter === '') {
      errors['general.delimiter'] = '分隔符不能为空';
    }
  }

  if (draft.dedup.matchRule !== MATCH_RULE_NONE && draft.dedup.conflictAction.trim() === '') {
    errors['dedup.conflictAction'] = '请选择去重冲突动作';
  }

  if (draft.entityTypes.length > MAX_ENTITY_TYPES) {
    errors.entityTypes = `自定义实体类型不得超过 ${MAX_ENTITY_TYPES} 个`;
  } else {
    const seen = new Set<string>();
    for (const rawName of draft.entityTypes) {
      const error = validateEntityName(rawName);
      if (error !== null) {
        errors.entityTypes = error;
        break;
      }
      const key = rawName.trim().toLowerCase();
      if (seen.has(key)) {
        errors.entityTypes = `实体类型「${rawName}」重复（大小写归一后）`;
        break;
      }
      seen.add(key);
    }
  }

  return errors;
}
