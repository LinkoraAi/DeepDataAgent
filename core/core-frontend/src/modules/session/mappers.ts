import type { CreateSessionPayload } from './types';

/**
 * 会话域纯函数工具（列表查询串装配、挂载路径/环境变量/配额前端拦截、创建载荷组装）。
 * 判定口径一律以后端代码为权威来源：
 * - 挂载路径形态：{@code runtime/domain/model/SessionResource#validateMountPath}；
 * - 挂载总量上限：{@code runtime/domain/service/SessionMountPolicy#MAX_MOUNTED_TOTAL_BYTES}（500MB）；
 * - 环境变量形态：{@code runtime/application/validation/SessionEnvironmentVariablesValidator}；
 * - 时间边界：{@code AgentRuntimeCommandConvert#parseInstant}（{@code OffsetDateTime.parse} 严格 RFC 3339）。
 * 前端拦截只用于避免"必然 400"的提交，后端仍是最终权威。
 */

/** 会话挂载文件总量上限（500 MiB，与后端 MAX_MOUNTED_TOTAL_BYTES 同口径：既有 + 新增合并后超限即拒绝）。 */
export const MAX_MOUNTED_TOTAL_BYTES = 500 * 1024 * 1024;

/** 挂载路径前缀（后端 SessionResource.DEFAULT_MOUNT_PATH_PREFIX，缺省挂载路径 = 本前缀 + file_id）。 */
export const DEFAULT_MOUNT_PATH_PREFIX = 'mounts/';

/** 环境变量条目数上限（后端 MAX_ENTRY_COUNT）。 */
export const MAX_ENV_ENTRIES = 64;

/** 平台保留环境变量名（由平台注入，用户提交同名变量后端 400）。 */
export const RESERVED_ENV_NAMES: readonly string[] = ['SERVER_ENDPOINT', 'USER_ID', 'WORK_DIR'];

/** 平台保留环境变量名前缀（该命名空间归平台所有）。 */
export const RESERVED_ENV_PREFIX = 'CAW_';

/** 环境变量名形态：字母 / 下划线开头，其后仅字母 / 数字 / 下划线（后端 VARIABLE_NAME_PATTERN）。 */
const ENV_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** 仅日期形态（yyyy-MM-dd），需补全为后端可解析的 RFC 3339 时间。 */
const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** 会话列表筛选表单（UI 态，camelCase）。 */
export interface SessionListFilter {
  /** 智能体 ID 过滤（空 = 全部） */
  agentId?: string;
  /** 状态多选（idle/running/rescheduling/terminated，空 = 不过滤） */
  statuses?: string[];
  /** 是否包含已归档会话（undefined = 不提交该参数，走后端缺省） */
  includeArchived?: boolean;
  /** 创建时间范围 [起, 止]（yyyy-MM-dd 或完整 RFC 3339；空 / null = 不过滤） */
  timeRange?: string[] | null;
}

/** 会话列表游标翻页参数（shared Cursor 约定）。 */
export interface SessionListPageParams {
  /** 每页条数（1-100） */
  limit?: number;
  /** 向后翻页游标（上页 last_id / next_page） */
  afterId?: string;
  /** 向前翻页游标（与 afterId 互斥） */
  beforeId?: string;
  /** 排序方向（缺省由后端按创建时间降序） */
  order?: 'asc' | 'desc';
}

/** 挂载文件草稿（对话框内勾选行；sizeBytes 缺省按 0 参与配额估算）。 */
export interface SessionMountDraft {
  /** 文件业务 ID（file_ 前缀） */
  fileId: string;
  /** 文件名（仅展示用） */
  filename?: string;
  /** 文件字节数（配额估算用，缺省 0） */
  sizeBytes?: number | null;
  /** 挂载路径（空 = 提交时省略，由后端补 mounts/{file_id}） */
  mountPath?: string;
}

/** 创建会话表单（UI 态）。 */
export interface SessionCreateForm {
  /** 绑定的智能体 ID（必填；版本省略 = 服务端解析激活版本） */
  agentId: string;
  /** 运行环境 ID（必填，仅 cloud 类型可选） */
  environmentId: string;
  /** 会话标题（可选） */
  title: string;
  /** 会话环境变量（KvEditor 输出，值全字符串提交） */
  envVars: Record<string, string>;
  /** 挂载文件（可选） */
  resources: SessionMountDraft[];
  /** 初始消息（可选；创建成功后由调用方补发 user.message 事件，不入创建载荷） */
  initialMessage: string;
}

/** 环境变量归一结果。 */
export interface EnvVarsResult {
  /** 归一后的字符串键值对（无有效条目为空对象） */
  vars: Record<string, string>;
  /** 校验错误文案（空串 = 通过） */
  error: string;
}

/**
 * 装配会话列表查询串（真实参数名对齐 AgentSessionController#listSessions）。
 * <p>{@code statuses[]} 为可重复参数：{@code buildQuery} 以 {@code set} 语义装配会把同名键互相覆盖，
 * 故此处用 {@code URLSearchParams#append} 逐个拼；时间边界补全为 RFC 3339（后端严格解析）。</p>
 * @param filter UI 筛选条件
 * @param page 游标翻页参数
 * @returns 形如 {@code ?agent_id=ag_1&statuses%5B%5D=idle} 的查询串；无参数时为空串
 */
export function buildSessionListQuery(filter: SessionListFilter = {}, page: SessionListPageParams = {}): string {
  const search = new URLSearchParams();
  putIfText(search, 'agent_id', filter.agentId);
  const bounds = toRfc3339Bounds(filter.timeRange);
  putIfText(search, 'created_at[gte]', bounds.gte);
  putIfText(search, 'created_at[lte]', bounds.lte);
  putIfText(search, 'order', page.order);
  if (page.limit !== undefined && page.limit > 0) {
    search.set('limit', String(page.limit));
  }
  if (filter.includeArchived !== undefined) {
    search.set('include_archived', String(filter.includeArchived));
  }
  putIfText(search, 'after_id', page.afterId);
  putIfText(search, 'before_id', page.beforeId);
  for (const status of filter.statuses ?? []) {
    if (status) {
      search.append('statuses[]', status);
    }
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}

/**
 * 时间范围 → 后端可解析的 RFC 3339 边界（起补 00:00:00、止补 23:59:59，按本地时区换算为 UTC）。
 * @param timeRange [起, 止]，元素为 yyyy-MM-dd 或完整时间串
 * @returns gte / lte（无值为空串）
 */
export function toRfc3339Bounds(timeRange: string[] | null | undefined): { gte: string; lte: string } {
  const list = timeRange ?? [];
  return {
    gte: toBoundary(list[0], false),
    lte: toBoundary(list[1], true),
  };
}

/**
 * 校验挂载路径形态（缺省 = 空，由后端补全 mounts/{file_id}）。
 * @param mountPath 待校验路径
 * @returns 空串 = 通过；否则为可直接展示的中文错误文案
 */
export function validateMountPath(mountPath: string | null | undefined): string {
  const raw = (mountPath ?? '').trim();
  if (!raw) {
    return '';
  }
  if (raw.startsWith('/')) {
    return '挂载路径必须为工作区相对路径（不得以 / 开头）';
  }
  if (raw.includes('\\')) {
    return '挂载路径不得包含反斜杠';
  }
  if (raw === DEFAULT_MOUNT_PATH_PREFIX.slice(0, -1) || !raw.startsWith(DEFAULT_MOUNT_PATH_PREFIX)) {
    return '挂载路径必须位于 mounts/ 目录下且不得等于 mounts 本身';
  }
  const segments = raw.slice(DEFAULT_MOUNT_PATH_PREFIX.length).split('/');
  for (const segment of segments) {
    if (!segment) {
      return '挂载路径不得包含空路径段';
    }
    if (segment === '.') {
      return '挂载路径不得包含当前目录段';
    }
    if (segment === '..') {
      return '挂载路径不得包含上级目录段';
    }
  }
  return '';
}

/**
 * 生成缺省挂载路径（与后端缺省口径一致：mounts/{file_id}）。
 * @param fileId 文件业务 ID
 * @returns 形如 mounts/file_1 的路径
 */
export function defaultMountPath(fileId: string): string {
  return `${DEFAULT_MOUNT_PATH_PREFIX}${fileId}`;
}

/**
 * 估算挂载文件总字节数（非法值按 0 计，与后端"元数据缺失计 0"口径一致）。
 * @param resources 挂载草稿列表
 * @returns 累计字节数
 */
export function estimateResourceBytes(resources: readonly SessionMountDraft[]): number {
  let total = 0;
  for (const item of resources) {
    const size = item.sizeBytes;
    if (typeof size === 'number' && Number.isFinite(size) && size > 0) {
      total += size;
    }
  }
  return total;
}

/**
 * 校验挂载总量配额（既有 + 新增合并 ≤500MB，超限后端 400）。
 * @param resources 挂载草稿列表
 * @returns 空串 = 通过；否则为可直接展示的超限文案
 */
export function validateResourceQuota(resources: readonly SessionMountDraft[]): string {
  const total = estimateResourceBytes(resources);
  if (total > MAX_MOUNTED_TOTAL_BYTES) {
    return `挂载文件总量 ${formatBytes(total)} 超过 500MB 限制，请减少挂载文件`;
  }
  return '';
}

/**
 * 字节数人性化展示（1024 进制，B/KB/MB/GB；会话挂载清单与超限文案共用）。
 * @param bytes 字节数（非法值按 0 处理）
 * @returns 形如 "1.5 MB" 的展示串
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  if (index === 0) {
    return `${value} B`;
  }
  const rounded = Math.round(value * 10) / 10;
  return `${Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)} ${units[index]}`;
}

/**
 * 归一并校验会话环境变量（键非空且形态合法、值一律字符串化）。
 * @param input KvEditor 输出（值理论上均为字符串，非字符串入参防御性拒绝）
 * @returns 归一结果；{@code error} 非空时 {@code vars} 不可提交
 */
export function normalizeEnvVars(
  input: Record<string, string | number | boolean | null | undefined> | null | undefined,
): EnvVarsResult {
  const entries = Object.entries(input ?? {});
  const vars: Record<string, string> = {};
  for (const [rawKey, rawValue] of entries) {
    const key = rawKey.trim();
    if (!key) {
      return { vars: {}, error: '环境变量名不能为空' };
    }
    if (!ENV_NAME_PATTERN.test(key)) {
      return { vars: {}, error: `环境变量名 ${key} 非法：仅允许字母或下划线开头，后接字母 / 数字 / 下划线` };
    }
    if (RESERVED_ENV_NAMES.includes(key) || key.startsWith(RESERVED_ENV_PREFIX)) {
      return { vars: {}, error: `环境变量名 ${key} 为平台保留名称，不可自定义` };
    }
    if (rawValue === null || rawValue === undefined || typeof rawValue === 'object') {
      return { vars: {}, error: `环境变量 ${key} 的值必须为字符串` };
    }
    vars[key] = String(rawValue);
  }
  if (Object.keys(vars).length > MAX_ENV_ENTRIES) {
    return { vars: {}, error: `环境变量条目数超过 ${MAX_ENV_ENTRIES} 条上限` };
  }
  return { vars, error: '' };
}

/**
 * 校验创建表单必填项与前端可拦截的形态错误。
 * @param form 创建表单
 * @returns 字段 → 错误文案（空对象 = 通过）
 */
export function validateSessionCreateForm(form: SessionCreateForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.agentId) {
    errors.agentId = '请选择智能体';
  }
  if (!form.environmentId) {
    errors.environmentId = '请选择运行环境（仅 cloud 类型可用）';
  }
  const mountPaths = (form.resources ?? [])
    .map((item) => validateMountPath(item.mountPath))
    .find((message) => message);
  if (mountPaths) {
    errors.resources = mountPaths;
  }
  const quota = validateResourceQuota(form.resources ?? []);
  if (quota) {
    errors.resources = errors.resources ?? quota;
  }
  const env = normalizeEnvVars(form.envVars);
  if (env.error) {
    errors.envVars = env.error;
  }
  return errors;
}

/**
 * 装配创建会话载荷（对齐 CreateSessionRequest 字段集）。
 * <p>废止字段（{@code environment} / {@code vaults} / {@code vault_ids} / {@code memory_store_ids} /
 * {@code delta_flush_interval_ms}）MUST NOT 出现在载荷中；空可选字段一律省略而非提交空值。</p>
 * @param form 创建表单
 * @returns 创建载荷
 * @throws Error 必填项缺失或形态非法（由调用方以 validateSessionCreateForm 先行拦截）
 */
export function buildCreatePayload(form: SessionCreateForm): CreateSessionPayload {
  const errors = validateSessionCreateForm(form);
  if (errors.agentId) {
    throw new Error(errors.agentId);
  }
  if (errors.environmentId) {
    throw new Error(errors.environmentId);
  }
  if (errors.resources) {
    throw new Error(errors.resources);
  }
  const env = normalizeEnvVars(form.envVars);
  if (env.error) {
    throw new Error(env.error);
  }
  const payload: CreateSessionPayload = {
    agent: form.agentId,
    environment_id: form.environmentId,
  };
  const title = (form.title ?? '').trim();
  if (title) {
    payload.title = title;
  }
  const resources = (form.resources ?? [])
    .filter((item) => item.fileId)
    .map((item) => {
      const mountPath = (item.mountPath ?? '').trim();
      return mountPath
        ? { type: 'file' as const, file_id: item.fileId, mount_path: mountPath }
        : { type: 'file' as const, file_id: item.fileId };
    });
  if (resources.length > 0) {
    payload.resources = resources;
  }
  if (Object.keys(env.vars).length > 0) {
    payload.environment_variables = env.vars;
  }
  return payload;
}

/**
 * 创建表单初始空值（打开对话框 / 关闭后复位用）。
 * @returns 空表单
 */
export function createEmptySessionForm(): SessionCreateForm {
  return {
    agentId: '',
    environmentId: '',
    title: '',
    envVars: {},
    resources: [],
    initialMessage: '',
  };
}

/**
 * 会话 ID 截断展示（长 ID 保留前 10 位 + 省略号；完整 ID 由行内 title 提示）。
 * @param id 会话业务 ID（sess_ 前缀）
 * @returns 截断后的展示串
 */
export function truncateSessionId(id: string | null | undefined): string {
  const raw = (id ?? '').trim();
  if (!raw) {
    return '-';
  }
  return raw.length > 14 ? `${raw.slice(0, 10)}…${raw.slice(-3)}` : raw;
}

/** 文本参数非空才写入查询串。 */
function putIfText(search: URLSearchParams, key: string, value: string | undefined): void {
  const raw = (value ?? '').trim();
  if (raw) {
    search.set(key, raw);
  }
}

/**
 * 单个时间边界归一：yyyy-MM-dd 补全为本地时刻并转 UTC（后端 OffsetDateTime.parse 不接受日期形态）；
 * 已是完整时间串则原样提交。
 */
function toBoundary(value: string | undefined, endOfDay: boolean): string {
  const raw = (value ?? '').trim();
  if (!raw) {
    return '';
  }
  if (!DATE_ONLY_PATTERN.test(raw)) {
    return raw;
  }
  const date = new Date(`${raw}T${endOfDay ? '23:59:59' : '00:00:00'}`);
  return Number.isNaN(date.getTime()) ? raw : date.toISOString();
}
