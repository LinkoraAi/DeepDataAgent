import { CHUNK_MODES, FILE_TYPES } from '@/shared/enums/dict';
import {
  CHUNK_TOKEN_NUM_MAX,
  CHUNK_TOKEN_NUM_MIN,
  OVERLAPPED_PERCENT_MAX,
  OVERLAPPED_PERCENT_MIN,
  defaultGeneral,
} from './kbDraft';
import type { GeneralChunkParams } from './kbDraft';
import type { DuplicateWire, UploadMetaPayload } from './api/documents';

/**
 * 文档域纯函数工具（上传前置拦截、扩展名映射、预检结果展示文案与文档级分块策略解析/装配/校验）。
 * <p>仅做可离线测试的纯逻辑：扩展名 → FileType 映射、白名单 + 体积校验、上传 meta 装配、
 * 判重预检命中轴的展示文案分组、分块策略 JSON 的防御解析 / 干净装配 / 值域与类型矩阵校验。
 * 网络与浏览器 File 依赖留在组件层。</p>
 */

/** FileType 联合（FILE_TYPES 白名单派生，13 类）。 */
export type FileTypeCode = (typeof FILE_TYPES)[number];

/** 单文件上传体积上限（100MB，对齐后端 spring.servlet.multipart.max-file-size 默认值）。 */
export const MAX_DOCUMENT_BYTES = 100 * 1024 * 1024;

/** 体积上限的 MB 数值（用于文案展示）。 */
const MAX_DOCUMENT_MB = 100;

/** 白名单展示串（校验失败文案用）。 */
const FILE_TYPE_WHITELIST_HINT = 'PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/TXT/MD/CSV/PNG/JPG/JPEG';

/** 文件结构最小约定（File 子集，node 测试环境无需 DOM File 构造）。 */
export interface DocFileLike {
  /** 文件名（含扩展名） */
  name: string;
  /** 字节大小 */
  size: number;
}

/** 上传前置校验结果。 */
export interface UploadValidation {
  /** 是否通过 */
  ok: boolean;
  /** 未通过原因（通过时为 undefined，供 UI 直显） */
  reason?: string;
}

/**
 * 按文件名扩展名映射 FileType（大写枚举名，命中白名单返回对应编码，否则空串）。
 * <p>口径与后端 FileType.fromFileName 对齐：取最后一个点号后的扩展名并转大写。</p>
 * @param name 文件名（含扩展名）
 * @returns FileType 编码，无法识别或不在白名单时返回空串
 */
export function fileTypeFromName(name: string): FileTypeCode | '' {
  const dot = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) {
    return '';
  }
  const ext = name.slice(dot + 1).toUpperCase();
  return (FILE_TYPES as readonly string[]).includes(ext) ? (ext as FileTypeCode) : '';
}

/**
 * 上传前置校验：类型白名单 + 单文件 ≤100MB（任一不通过即拒绝加入队列）。
 * @param file 待校验文件（File 或结构等价对象）
 * @returns 校验结果；失败时 reason 为可直接展示的中文文案
 */
export function validateUploadFile(file: DocFileLike): UploadValidation {
  const fileType = fileTypeFromName(file.name);
  if (fileType === '') {
    return { ok: false, reason: `不支持的文件类型，仅接受 ${FILE_TYPE_WHITELIST_HINT}` };
  }
  if (file.size > MAX_DOCUMENT_BYTES) {
    return { ok: false, reason: `文件大小超过 ${MAX_DOCUMENT_MB}MB 限制` };
  }
  return { ok: true };
}

/**
 * 装配上传 meta 载荷（源对象引用、大小、判重哈希由服务端派生，此处登记三元基础字段 + 可选文档级分块策略）。
 * <p>假定调用方已通过 validateUploadFile；fileType 取扩展名映射的大写枚举名。
 * chunkStrategy 为空表示继承库级策略：MUST NOT 写入键位，后端收到缺省即落 NULL（与既有行为一致）。</p>
 * @param kbId 目标知识库ID
 * @param file 待上传文件
 * @param chunkStrategy 文档级分块策略 JSON（可选，经 buildChunkStrategyJson 装配的干净形态）
 * @returns 后端 UploadDocumentRequest（meta part）同构载荷
 */
export function buildMeta(kbId: number, file: DocFileLike, chunkStrategy?: string): UploadMetaPayload {
  const meta: UploadMetaPayload = {
    kbId,
    fileName: file.name,
    fileType: fileTypeFromName(file.name),
  };
  if (chunkStrategy && chunkStrategy.trim() !== '') {
    meta.chunkStrategy = chunkStrategy;
  }
  return meta;
}

/** 判重命中轴对外契约字面量 → 中文标签。 */
const MATCH_AXIS_LABELS: Record<string, string> = {
  fileName: '文件名',
  contentHash: '文件内容',
};

/**
 * 单条命中轴标签（未知轴原样返回，保证契约漂移时不丢失信息）。
 * @param axis 命中轴字面量（'fileName' / 'contentHash'）
 */
export function matchAxisLabel(axis: string): string {
  return MATCH_AXIS_LABELS[axis] ?? axis;
}

/**
 * 命中轴集合的展示文案（多轴以顿号连接）。
 * @param axes 命中轴列表
 */
export function describeMatchAxes(axes: string[]): string {
  return axes.map(matchAxisLabel).join('、');
}

/** 预检命中结果按主命中轴分组项。 */
export interface PrecheckHitGroup {
  /** 分组轴标签（如「文件名」「文件内容」；多轴命中归入「文件名 + 文件内容」） */
  axisLabel: string;
  /** 该组命中的既有文档（按 fileName 展示） */
  hits: Array<{ id: number; fileName: string }>;
}

/**
 * 预检命中结果分组：按「命中轴组合」聚合同类命中文档，供上传前展示决策面板。
 * <p>无命中返回空数组；每个 hit 以其 matchAxes 排序后组合为分组键，保证同轴组合的文档聚在一起。</p>
 * @param hits 后端返回的重复摘要列表
 */
export function groupPrecheckHits(hits: DuplicateWire[]): PrecheckHitGroup[] {
  const bucket = new Map<string, PrecheckHitGroup>();
  for (const hit of hits) {
    const axes = [...(hit.matchAxes ?? [])].sort();
    const key = axes.join('+');
    const axisLabel = describeMatchAxes(axes);
    const existing = bucket.get(key);
    if (existing) {
      existing.hits.push({ id: hit.id, fileName: hit.fileName });
    } else {
      bucket.set(key, { axisLabel, hits: [{ id: hit.id, fileName: hit.fileName }] });
    }
  }
  return Array.from(bucket.values());
}

/**
 * 分块策略解析视图（文档级与库级共用）。
 * <p>chunkMode 为空串表示「未配置 / 脏数据回退未配置」，展示口径按后端规则等同 GENERAL；
 * general 参数在缺失或越界时逐字段回退默认值（与 kbDraft.clampInt 口径一致）。</p>
 */
export interface ChunkStrategyView {
  /** 分块模式（CHUNK_MODES 白名单值；未配置为空串） */
  chunkMode: string;
  /** GENERAL 模式参数（非 GENERAL 模式不读取但恒给默认值便于回显初值） */
  general: GeneralChunkParams;
}

/** 分块模式 → 允许的文档类型集合（逐条镜像后端 ChunkStrategyFileTypeValidator.ALLOWED_TYPES_BY_MODE）。 */
export const CHUNK_MODE_ALLOWED_FILE_TYPES: Record<string, readonly string[]> = {
  GENERAL: FILE_TYPES,
  BOOK: ['PDF', 'DOC', 'DOCX', 'TXT', 'MD'],
  LAWS: ['PDF', 'DOC', 'DOCX', 'TXT', 'MD'],
  QA: ['DOC', 'DOCX', 'XLS', 'XLSX', 'TXT', 'MD', 'CSV'],
  TABLE: ['XLS', 'XLSX', 'TXT', 'CSV'],
  PRESENTATION: ['PDF', 'PPT', 'PPTX'],
  ONE: ['PDF', 'DOC', 'DOCX', 'XLS', 'XLSX', 'PPT', 'PPTX', 'TXT', 'MD'],
};

/** 分块模式：通用（唯一携带参数的模式，与后端 / kbDraft 口径一致）。 */
const CHUNK_MODE_GENERAL = 'GENERAL';

/** 安全解析 JSON 为对象（非对象 / 非法返回 null）。 */
function parseObjectOrNull(raw: unknown): Record<string, unknown> | null {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * 解包分块策略 JSON 文本为对象节点。
 * <p>兼容「策略 JSON 被再包一层字符串」的存量形态（后端 ChunkStrategyConfigValidator 同款解包口径）。</p>
 */
function unwrapStrategyObject(raw: string): Record<string, unknown> | null {
  const first = parseObjectOrNull(raw);
  if (first !== null) {
    return first;
  }
  // 再包层形态：原文是 JSON 字符串字面量，内层才是策略对象
  try {
    const inner: unknown = JSON.parse(raw);
    return parseObjectOrNull(inner);
  } catch {
    return null;
  }
}

/** 读取闭区间整数（非整数或越界回退默认，与后端「显式写出必须落值域」相比展示层取宽松回退）。 */
function readIntInRange(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max) {
    return value;
  }
  return fallback;
}

/** 读取非空字符串（空串与非文本回退默认；空白字符本身合法）。 */
function readNonEmptyString(value: unknown, fallback: string): string {
  return typeof value === 'string' && value !== '' ? value : fallback;
}

/**
 * 防御式解析分块策略 JSON（文档级 `document.chunkStrategy` 与库级提取结果通用）。
 * <p>口径镜像后端：空白 / 非法 / 缺失字段按「未配置」回退（模式空串 = 展示等同 GENERAL，
 * 参数逐字段回退默认）；模式名 trim + 大写归一后必须命中七枚举白名单才视为已配置。</p>
 * @param raw 分块策略 JSON 原文（可为 null/空白/字符串再包层形态）
 * @returns 解析视图（永不抛错）
 */
export function parseChunkStrategy(raw: string | null | undefined): ChunkStrategyView {
  const fallback: ChunkStrategyView = { chunkMode: '', general: defaultGeneral() };
  if (!raw || raw.trim() === '') {
    return fallback;
  }
  const strategy = unwrapStrategyObject(raw);
  if (strategy === null) {
    return fallback;
  }
  const mode = typeof strategy.chunkMode === 'string' ? strategy.chunkMode.trim().toUpperCase() : '';
  const chunkMode = CHUNK_MODES.some((item) => item.value === mode) ? mode : '';
  const modeConfig = strategy.modeConfig;
  const params =
    modeConfig !== null && typeof modeConfig === 'object' && !Array.isArray(modeConfig)
      ? parseObjectOrNullSafe((modeConfig as Record<string, unknown>).params)
      : null;
  if (params === null) {
    return { chunkMode, general: defaultGeneral() };
  }
  const defaults = defaultGeneral();
  // 重叠比例规范键缺失时兼容历史键 overlap（与后端 validateOverlappedPercent 兼容口径一致）
  const overlapValue = params.overlapped_percent ?? params.overlap;
  return {
    chunkMode,
    general: {
      chunkTokenNum: readIntInRange(params.chunk_token_num, CHUNK_TOKEN_NUM_MIN, CHUNK_TOKEN_NUM_MAX, defaults.chunkTokenNum),
      overlappedPercent: readIntInRange(overlapValue, OVERLAPPED_PERCENT_MIN, OVERLAPPED_PERCENT_MAX, defaults.overlappedPercent),
      delimiter: readNonEmptyString(params.delimiter, defaults.delimiter),
    },
  };
}

/** 读取嵌套对象节点（非对象 / 数组返回 null）。 */
function parseObjectOrNullSafe(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/**
 * 从库级 `rag_engine_config` JSON 字符串中提取 `chunkStrategy` 子对象原文。
 * <p>用于列表「继承库级」展示与弹窗回显的库级默认值；缺失 / 非法 / 非对象返回 null。</p>
 * @param ragEngineConfigJson 知识库线格式的 ragEngineConfig（JSON 字符串，可为 null）
 * @returns 子对象序列化后的策略 JSON 原文；不可用时 null
 */
export function extractKbChunkStrategy(ragEngineConfigJson: string | null | undefined): string | null {
  const rag = parseObjectOrNull(ragEngineConfigJson ?? null);
  if (rag === null) {
    return null;
  }
  const node = rag.chunkStrategy;
  if (node === null || node === undefined) {
    return null;
  }
  if (typeof node === 'string') {
    // 字符串再包层：解包为对象后归一序列化，避免下游二次解包
    const inner = unwrapStrategyObject(node);
    return inner === null ? null : JSON.stringify(inner);
  }
  const asObject = parseObjectOrNullSafe(node);
  return asObject === null ? null : JSON.stringify(asObject);
}

/**
 * 分块模式展示标签（空 / 未配置按 GENERAL 口径返回「通用」；白名单外原样返回保证信息不丢失）。
 * @param mode 分块模式枚举名（可为 null/空）
 */
export function chunkModeLabel(mode: string | null | undefined): string {
  if (!mode || mode.trim() === '') {
    return '通用';
  }
  const hit = CHUNK_MODES.find((item) => item.value === mode.trim().toUpperCase());
  return hit?.label ?? mode;
}

/**
 * 文档列表「分块策略」列文案。
 * <p>口径：文档级策略已配置 → 模式名；文档级为空（继承）→「继承库级（<库级模式名>）」，
 * 库级缺失 / 脏数据回退「通用」（与后端 resolveMode 的空白按 GENERAL 一致）。</p>
 * @param docJson 文档级策略 JSON 原文（DocumentWire.chunkStrategy）
 * @param kbJson 库级策略 JSON 原文（extractKbChunkStrategy 产物）
 */
export function describeDocChunkStrategy(
  docJson: string | null | undefined,
  kbJson: string | null | undefined,
): string {
  const doc = parseChunkStrategy(docJson);
  if (doc.chunkMode !== '') {
    return chunkModeLabel(doc.chunkMode);
  }
  const kb = parseChunkStrategy(kbJson);
  return `继承库级（${chunkModeLabel(kb.chunkMode)}）`;
}

/**
 * 按文件类型过滤允许的分块模式（镜像后端「策略 × 文档类型」允许矩阵）。
 * <p>返回顺序恒为 CHUNK_MODES 声明序，保证 UI 候选稳定；文件类型为空或不在白名单时
 * 不做限制（对齐后端「无类型可校验直接通过」的口径），返回全部模式。</p>
 * @param fileType FileType 枚举名（大写）
 * @returns 允许的 chunkMode 值列表
 */
export function allowedChunkModesForFileType(fileType: string | null | undefined): string[] {
  const normalized = (fileType ?? '').trim().toUpperCase();
  if (normalized === '' || !(FILE_TYPES as readonly string[]).includes(normalized)) {
    return CHUNK_MODES.map((item) => item.value);
  }
  return CHUNK_MODES
    .filter((item) => (CHUNK_MODE_ALLOWED_FILE_TYPES[item.value] ?? []).includes(normalized))
    .map((item) => item.value);
}

/**
 * 判断分块模式与文件类型是否兼容（类型矩阵的显式判定入口，供不兼容前置提示复用）。
 * @param mode 分块模式枚举名
 * @param fileType FileType 枚举名（大写）
 */
export function isChunkModeCompatible(mode: string, fileType: string | null | undefined): boolean {
  return allowedChunkModesForFileType(fileType).includes(mode);
}

/**
 * 装配干净的文档级分块策略 JSON（后端校验只拒不改写：前端发什么库里存什么，故 MUST 装配规范形态）。
 * <p>非 GENERAL 不携带 modeConfig（后端「非 GENERAL 携带参数即拒绝」）；
 * GENERAL 仅写三个规范键（chunk_token_num / overlapped_percent / delimiter）。</p>
 * @param mode 分块模式枚举名
 * @param general GENERAL 模式参数（非 GENERAL 忽略）
 * @returns 策略 JSON 字符串
 */
export function buildChunkStrategyJson(mode: string, general: GeneralChunkParams): string {
  const strategy: Record<string, unknown> = { chunkMode: mode };
  if (mode === CHUNK_MODE_GENERAL) {
    strategy.modeConfig = {
      params: {
        chunk_token_num: general.chunkTokenNum,
        overlapped_percent: general.overlappedPercent,
        delimiter: general.delimiter,
      },
    };
  }
  return JSON.stringify(strategy);
}

/**
 * 文档级策略草稿值域校验（镜像后端 ChunkStrategyConfigValidator 的拒绝式规则，提交前拦截避免 400）。
 * <p>键为字段名（chunkMode / chunkTokenNum / overlappedPercent / delimiter），值为可展示中文错误；
 * 空对象表示通过。非 GENERAL 模式不校验参数（参数不提交）。</p>
 * @param mode 所选分块模式
 * @param general GENERAL 参数
 */
export function validateChunkStrategyDraft(mode: string, general: GeneralChunkParams): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!CHUNK_MODES.some((item) => item.value === mode)) {
    errors.chunkMode = '请选择合法的分块模式';
    return errors;
  }
  if (mode === CHUNK_MODE_GENERAL) {
    if (!Number.isInteger(general.chunkTokenNum)
      || general.chunkTokenNum < CHUNK_TOKEN_NUM_MIN || general.chunkTokenNum > CHUNK_TOKEN_NUM_MAX) {
      errors.chunkTokenNum = `分块 token 预算须为 ${CHUNK_TOKEN_NUM_MIN}~${CHUNK_TOKEN_NUM_MAX} 的整数`;
    }
    if (!Number.isInteger(general.overlappedPercent)
      || general.overlappedPercent < OVERLAPPED_PERCENT_MIN || general.overlappedPercent > OVERLAPPED_PERCENT_MAX) {
      errors.overlappedPercent = `重叠比例须为 ${OVERLAPPED_PERCENT_MIN}~${OVERLAPPED_PERCENT_MAX} 的整数`;
    }
    if (general.delimiter === '') {
      errors.delimiter = '分隔符不能为空';
    }
  }
  return errors;
}
