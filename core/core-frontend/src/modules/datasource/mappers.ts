import type { DatasourceTypeResponse } from './api/datasource';
import type {
  ApiAuthType,
  ApiBodyType,
  ApiFieldRequest,
  ApiHttpMethod,
  ApiKeyValue,
  ApiPaginationType,
  ApiParamLocation,
  ApiSchemaRequest,
  ApiSchemaResponse,
  ApiFieldResponse,
  ApiPreOperationConfigResponse,
  ParseApiResponseRequest,
  ParsedFieldResponse,
  ParamMappingRequest,
  PreOperationConfigRequest,
  PreOperationTestRequest,
} from './api/apiSchema';

/**
 * 数据源模块的纯函数集合：校验、字段树转换、请求/响应映射。
 * 本文件不引入 Vue 运行时，可在 Node 环境下由 Vitest 直接测试。
 */

/** 名称正则：中英文或字母开头，可含中英文/数字/下划线/连字符，总长 1~100（与后端业务规则一致）。 */
export const NAME_PATTERN = /^[一-龥a-zA-Z][一-龥a-zA-Z0-9_-]{0,99}$/;

/** JDBC 各子类型的默认端口（与后端 JdbcType 对齐）。 */
export const JDBC_DEFAULT_PORTS: Record<'MYSQL' | 'CLICKHOUSE' | 'POSTGRESQL', number> = {
  MYSQL: 3306,
  CLICKHOUSE: 8123,
  POSTGRESQL: 5432,
};

/** PostgreSQL 禁止选择的系统内置 schema（黑名单前缀 + 精确名）。 */
export const PG_SYSTEM_SCHEMA_EXACT = ['pg_catalog', 'information_schema'];
/** PostgreSQL 禁止选择的系统 schema 前缀（如 pg_temp / pg_toast 及其编号变体）。 */
export const PG_SYSTEM_SCHEMA_PREFIX = ['pg_temp', 'pg_toast'];

/** API 数据表默认超时（秒）。 */
export const API_DEFAULT_TIMEOUT = 180;

/** API 数据表默认重试次数。 */
export const API_DEFAULT_RETRY = 0;

/** PostgreSQL 默认 schema 名。 */
export const PG_DEFAULT_SCHEMA = 'public';

/** 描述长度上限。 */
export const MAX_DESCRIPTION_LENGTH = 500;

/** 注释长度上限（表/字段/API 字段描述共用）。 */
export const MAX_COMMENT_LENGTH = 150;

/** 预览可选行数（后端钳制 1~100）。 */
export const PREVIEW_LIMITS = [10, 50, 100] as const;

/** 静态回退的类型清单（后端 GET /types 未返回时的兜底展示）。 */
export const DEFAULT_DATASOURCE_TYPES: DatasourceTypeResponse[] = [
  { type: 'JDBC', subType: 'MYSQL', name: 'MySQL', category: 'OLTP', defaultPort: 3306 },
  { type: 'JDBC', subType: 'POSTGRESQL', name: 'PostgreSQL', category: 'OLTP', defaultPort: 5432 },
  { type: 'JDBC', subType: 'CLICKHOUSE', name: 'ClickHouse', category: 'OLAP', defaultPort: 8123 },
  { type: 'API', subType: 'API', name: 'API', category: 'API', defaultPort: null },
];

/** 字段类型枚举（后端 BodyType）。 */
export const BODY_TYPES: ApiBodyType[] = ['JSON', 'FORM_URLENCODED', 'RAW'];

/** 请求方法枚举（后端 HttpMethod）。 */
export const HTTP_METHODS: ApiHttpMethod[] = ['GET', 'POST'];

/** 前置操作参数注入位置（后端 paramLocation 小写）。 */
export const PARAM_LOCATIONS: ApiParamLocation[] = ['header', 'query', 'body'];

/** 鉴权类型（后端 ApiAuthType）。 */
export const AUTH_TYPES: ApiAuthType[] = ['NO_AUTH', 'BASIC_AUTH'];

/** 分页类型（后端 ApiPaginationType）。 */
export const PAGINATION_TYPES: ApiPaginationType[] = ['NONE', 'PAGE_BASED'];

// ==================== UI 侧「草稿」类型（与后端 DTO 解耦） ====================

/** API 鉴权草稿（编辑态 password 留空 = 沿用原值，与响应 authConfig 无 password 对应）。 */
export interface ApiAuthDraft {
  /** 鉴权类型 */
  authType: ApiAuthType;
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
  /** 编辑态：后端返回的凭证配置状态；供 UI 显示"已配置/未配置" */
  credentialConfigured: boolean;
}

/** API 分页草稿。 */
export interface ApiPaginationDraft {
  /** 分页类型 */
  paginationType: ApiPaginationType;
  /** 页码参数名 */
  pageParamName: string;
  /** 页大小参数名 */
  sizeParamName: string;
  /** 每页大小 */
  pageSize: number;
  /** 最大页数 */
  maxPages: number;
  /** 总数 JsonPath */
  totalCountJsonPath: string;
}

/** 前置操作参数映射草稿。 */
export interface ApiParamMappingDraft {
  /** 参数名 */
  paramName: string;
  /** 注入位置 */
  paramLocation: ApiParamLocation;
  /** 取值 JsonPath */
  jsonPath: string;
}

/** 前置操作草稿。 */
export interface ApiPreOperationDraft {
  /** 是否启用 */
  enabled: boolean;
  /** 请求地址 */
  url: string;
  /** 请求方法 */
  method: ApiHttpMethod;
  /** 请求头 */
  headers: ApiKeyValue;
  /** 查询参数 */
  params: ApiKeyValue;
  /** 请求体 */
  body: string;
  /** 请求体类型 */
  bodyType: ApiBodyType;
  /** 参数映射列表 */
  paramMappings: ApiParamMappingDraft[];
}

/** API 字段映射载荷（对应后端 ApiFieldRequest）。 */
export type ApiFieldPayload = ApiFieldRequest;

/** API 数据表草稿（表单模型；保存时映射为 ApiSchemaRequest）。 */
export interface ApiSchemaDraft {
  /** 编辑时的表 id；新建为 undefined */
  id?: number;
  /** 表名 */
  name: string;
  /** 请求 URL */
  url: string;
  /** 请求方法 */
  method: ApiHttpMethod;
  /** 请求头 */
  headers: ApiKeyValue;
  /** 查询参数 */
  params: ApiKeyValue;
  /** 请求体 */
  body: string;
  /** 请求体类型 */
  bodyType: ApiBodyType;
  /** 根 JsonPath；对应 request.jsonPathConfig、parse.rootPath */
  rootPath: string;
  /** 超时（秒） */
  timeout: number;
  /** 重试次数 */
  retryCount: number;
  /** 鉴权草稿 */
  auth: ApiAuthDraft;
  /** 分页草稿 */
  pagination: ApiPaginationDraft;
  /** 前置操作列表 */
  preOperations: ApiPreOperationDraft[];
  /** 字段映射载荷（保存时全量覆盖；勾选后填充） */
  fields: ApiFieldPayload[];
}

/** 字段树节点（供 t-tree 渲染；value 用 jsonPath 保唯一，label 展示"原始名(类型)"）。 */
export interface FieldTreeNode {
  /** t-tree value（用 jsonPath） */
  value: string;
  /** t-tree label */
  label: string;
  /** 原始字段名 */
  originalName: string;
  /** 字段 JsonPath */
  jsonPath: string;
  /** 推断类型 */
  fieldType: string;
  /** 子节点 */
  children: FieldTreeNode[];
}

// ==================== 校验函数（返回错误消息；空串代表通过） ====================

/** 是否为空白字符串（undefined/null/仅空格视为空白）。 */
function isBlank(value: string | null | undefined): boolean {
  if (typeof value !== 'string') {
    return true;
  }
  return value.trim().length === 0;
}

/** 校验数据源名称（对应后端 CreateDatasourceRequest.name 与业务规则）。 */
export function validateName(name: string | null | undefined): string {
  if (isBlank(name)) {
    return '名称不能为空';
  }
  if (!NAME_PATTERN.test(name as string)) {
    return '名称需以中英文或字母开头，可含中英文/数字/下划线/连字符，最长 100 字符';
  }
  return '';
}

/** 校验描述长度（≤500）。 */
export function validateDescription(description: string | null | undefined): string {
  if (description && description.length > MAX_DESCRIPTION_LENGTH) {
    return `描述不能超过 ${MAX_DESCRIPTION_LENGTH} 字符`;
  }
  return '';
}

/** 校验端口（1~65535 整数）。 */
export function validatePort(port: number | null | undefined): string {
  if (typeof port !== 'number' || !Number.isFinite(port) || !Number.isInteger(port)) {
    return '端口必须为整数';
  }
  if (port < 1 || port > 65535) {
    return '端口必须在 1~65535 之间';
  }
  return '';
}

/** 按 JDBC 子类型取默认端口；非 JDBC 返回 null。 */
export function portForSubType(subType: string | null | undefined): number | null {
  if (!subType) {
    return null;
  }
  const upper = String(subType).toUpperCase();
  if (upper === 'MYSQL' || upper === 'CLICKHOUSE' || upper === 'POSTGRESQL') {
    return JDBC_DEFAULT_PORTS[upper];
  }
  return null;
}

/** 归一化 JDBC 子类型（供 t-form 直接使用；空白返回 null）。 */
export function normalizeSubType(subType: string | null | undefined): 'MYSQL' | 'CLICKHOUSE' | 'POSTGRESQL' | null {
  if (isBlank(subType)) {
    return null;
  }
  const upper = String(subType).toUpperCase();
  if (upper === 'MYSQL' || upper === 'CLICKHOUSE' || upper === 'POSTGRESQL') {
    return upper;
  }
  return null;
}

/** 归一化 PostgreSQL schema（trim + 空则回落 public）。 */
export function normalizePgSchema(schema: string | null | undefined): string {
  if (isBlank(schema)) {
    return PG_DEFAULT_SCHEMA;
  }
  return String(schema).trim();
}

/** 校验 PostgreSQL schema：仅 POSTGRESQL 生效；系统内置 schema 禁选（黑名单：pg_catalog / information_schema / pg_temp* / pg_toast*）。 */
export function validatePgSchema(schema: string | null | undefined, subType: string | null | undefined): string {
  if (normalizeSubType(subType) !== 'POSTGRESQL') {
    return '';
  }
  if (isBlank(schema)) {
    return '';
  }
  const value = String(schema).trim().toLowerCase();
  if (PG_SYSTEM_SCHEMA_EXACT.includes(value)) {
    return `PostgreSQL 禁用系统 schema：${value}`;
  }
  for (const prefix of PG_SYSTEM_SCHEMA_PREFIX) {
    if (value === prefix || value.startsWith(`${prefix}_`)) {
      return `PostgreSQL 禁用系统 schema：${value}`;
    }
  }
  return '';
}

/** 校验注释长度（0~150；空视为 0 通过）。 */
export function validateComment(comment: string | null | undefined): string {
  if (comment && comment.length > MAX_COMMENT_LENGTH) {
    return `注释内容不能超过 ${MAX_COMMENT_LENGTH} 字符`;
  }
  return '';
}

/** 返回注释剩余字数（可为负值表示超限）。 */
export function commentRemaining(comment: string | null | undefined): number {
  return MAX_COMMENT_LENGTH - (comment?.length ?? 0);
}

/** 校验 API 根 JsonPath 的数组约定；返回非阻塞提示（空串代表无需提示）。 */
export function rootPathHint(rootPath: string | null | undefined): string {
  if (isBlank(rootPath)) {
    return '';
  }
  const value = String(rootPath).trim();
  // 单根 `$` 无需提示；以 `.*` 结尾视为已按数组约定填写
  if (value === '$' || value.endsWith('.*')) {
    return '';
  }
  return '若根 JsonPath 指向数组，需以 .* 结尾（例：$.data.list.*）';
}

/** 校验单个前置操作中的动态参数映射：三项都必填；返回错误消息串（无问题为空）。 */
export function validateDynamicParams(mappings: ApiParamMappingDraft[] | null | undefined): string {
  if (!mappings || mappings.length === 0) {
    return '';
  }
  for (const m of mappings) {
    if (isBlank(m.paramName)) {
      return '参数映射缺少 paramName';
    }
    if (isBlank(m.paramLocation)) {
      return `参数 ${m.paramName} 缺少注入位置`;
    }
    if (!PARAM_LOCATIONS.includes(m.paramLocation)) {
      return `参数 ${m.paramName} 注入位置非法：仅支持 header / query / body`;
    }
    if (isBlank(m.jsonPath)) {
      return `参数 ${m.paramName} 缺少 JsonPath`;
    }
  }
  return '';
}

/** API 草稿校验上下文（区分新建与编辑；编辑态允许鉴权密码留空 = 沿用）。 */
export interface ApiSchemaValidateContext {
  /** 编辑态：后端 authConfig.credentialConfigured=true 时允许密码留空 */
  mode: 'create' | 'edit';
}

/** API 草稿校验错误映射（key 为表单字段路径，value 为首个错误消息；空代表通过）。 */
export type ApiSchemaValidateResult = Record<string, string>;

/**
 * 综合校验 API 数据表草稿。
 * 覆盖：name/url 必填、BASIC 账密必填、PAGE_BASED 必填项、pageSize/maxPages 范围、retryCount 0~5、timeout>0、前置操作参数映射。
 */
export function validateApiSchemaDraft(draft: ApiSchemaDraft, ctx: ApiSchemaValidateContext): ApiSchemaValidateResult {
  const errors: ApiSchemaValidateResult = {};
  const nameErr = validateName(draft.name);
  if (nameErr) {
    errors.name = nameErr;
  }
  if (isBlank(draft.url)) {
    errors.url = '请求地址不能为空';
  }
  if (!HTTP_METHODS.includes(draft.method)) {
    errors.method = '请求方法仅支持 GET / POST';
  }
  if (draft.bodyType && !BODY_TYPES.includes(draft.bodyType)) {
    errors.bodyType = '请求体类型仅支持 JSON / FORM_URLENCODED / RAW';
  }
  if (typeof draft.timeout !== 'number' || !Number.isFinite(draft.timeout) || draft.timeout <= 0) {
    errors.timeout = '超时须为正数（秒）';
  }
  if (typeof draft.retryCount !== 'number' || !Number.isInteger(draft.retryCount) || draft.retryCount < 0 || draft.retryCount > 5) {
    errors.retryCount = '重试次数须在 0~5 之间';
  }
  // 鉴权
  if (!AUTH_TYPES.includes(draft.auth.authType)) {
    errors.authType = '鉴权类型仅支持 NO_AUTH / BASIC_AUTH';
  } else if (draft.auth.authType === 'BASIC_AUTH') {
    if (isBlank(draft.auth.username)) {
      errors.authUsername = 'BASIC 鉴权必须填写用户名';
    }
    if (isBlank(draft.auth.password) && !(ctx.mode === 'edit' && draft.auth.credentialConfigured)) {
      errors.authPassword = 'BASIC 鉴权必须填写密码';
    }
  }
  // 分页
  if (!PAGINATION_TYPES.includes(draft.pagination.paginationType)) {
    errors.paginationType = '分页类型仅支持 NONE / PAGE_BASED';
  } else if (draft.pagination.paginationType === 'PAGE_BASED') {
    if (isBlank(draft.pagination.pageParamName)) {
      errors.pageParamName = 'PAGE_BASED 需填写页码参数名';
    }
    if (isBlank(draft.pagination.sizeParamName)) {
      errors.sizeParamName = 'PAGE_BASED 需填写页大小参数名';
    }
    const ps = draft.pagination.pageSize;
    if (typeof ps !== 'number' || !Number.isInteger(ps) || ps < 1 || ps > 500) {
      errors.pageSize = '每页大小须在 1~500 之间';
    }
    const mp = draft.pagination.maxPages;
    if (typeof mp !== 'number' || !Number.isInteger(mp) || mp < 1 || mp > 10000) {
      errors.maxPages = '最大页数须在 1~10000 之间';
    }
  }
  // 前置操作：仅对启用的项要求 url/method；paramMappings 三项齐全
  draft.preOperations.forEach((pre, i) => {
    if (!pre.enabled) {
      return;
    }
    if (isBlank(pre.url)) {
      errors[`preOperations.${i}.url`] = '启用的前置操作必须填写 URL';
    }
    if (!HTTP_METHODS.includes(pre.method)) {
      errors[`preOperations.${i}.method`] = '前置操作请求方法仅支持 GET / POST';
    }
    const dynErr = validateDynamicParams(pre.paramMappings);
    if (dynErr) {
      errors[`preOperations.${i}.paramMappings`] = dynErr;
    }
  });
  return errors;
}

// ==================== 字段树构建与勾选 → ApiFieldPayload ====================

/**
 * 将后端解析字段树（ParsedFieldResponse）转换为 t-tree 数据（递归）；value 使用 jsonPath 保唯一。
 */
export function buildParsedFieldTree(nodes: ParsedFieldResponse[] | null | undefined): FieldTreeNode[] {
  if (!nodes || nodes.length === 0) {
    return [];
  }
  return nodes.map((n) => {
    const children = buildParsedFieldTree(n.children);
    return {
      value: n.jsonPath,
      label: `${n.originalName} (${n.fieldType})`,
      originalName: n.originalName,
      jsonPath: n.jsonPath,
      fieldType: n.fieldType,
      children,
    };
  });
}

/** 深度展开字段树，返回按 jsonPath 索引的扁平 Map（勾选 → 载荷查表用）。 */
export function flattenFieldTree(tree: FieldTreeNode[]): Map<string, FieldTreeNode> {
  const index = new Map<string, FieldTreeNode>();
  const walk = (nodes: FieldTreeNode[]): void => {
    for (const node of nodes) {
      index.set(node.jsonPath, node);
      if (node.children.length > 0) {
        walk(node.children);
      }
    }
  };
  walk(tree);
  return index;
}

/**
 * 由勾选的 jsonPath 列表构造字段映射载荷；未勾选的节点不进入。
 * displayName 默认取 originalName；description 默认留空由用户在 FieldMappingTable 编辑。
 */
export function checkedToFieldPayload(
  checkedKeys: string[] | null | undefined,
  index: Map<string, FieldTreeNode>,
): ApiFieldPayload[] {
  if (!checkedKeys || checkedKeys.length === 0) {
    return [];
  }
  const result: ApiFieldPayload[] = [];
  for (const key of checkedKeys) {
    const node = index.get(key);
    if (!node) {
      continue;
    }
    result.push({
      originalName: node.originalName,
      displayName: node.originalName,
      jsonPath: node.jsonPath,
      fieldType: node.fieldType,
      description: '',
    });
  }
  return result;
}

// ==================== 请求 / 响应 ↔ 草稿 映射 ====================

/** 生成空白草稿（新建态默认值）。 */
export function createEmptyApiSchemaDraft(): ApiSchemaDraft {
  return {
    name: '',
    url: '',
    method: 'GET',
    headers: {},
    params: {},
    body: '',
    bodyType: 'JSON',
    rootPath: '$',
    timeout: API_DEFAULT_TIMEOUT,
    retryCount: API_DEFAULT_RETRY,
    auth: { authType: 'NO_AUTH', username: '', password: '', credentialConfigured: false },
    pagination: {
      paginationType: 'NONE',
      pageParamName: '',
      sizeParamName: '',
      pageSize: 20,
      maxPages: 100,
      totalCountJsonPath: '',
    },
    preOperations: [],
    fields: [],
  };
}

/** 生成空白前置操作草稿。 */
export function createEmptyPreOperationDraft(): ApiPreOperationDraft {
  return {
    enabled: true,
    url: '',
    method: 'POST',
    headers: {},
    params: {},
    body: '',
    bodyType: 'JSON',
    paramMappings: [],
  };
}

/** 生成空白参数映射草稿。 */
export function createEmptyParamMappingDraft(): ApiParamMappingDraft {
  return { paramName: '', paramLocation: 'header', jsonPath: '' };
}

/** 归一化 bodyType（后端可能返回 null；草稿用枚举值，默认 JSON）。 */
function toBodyType(v: string | null | undefined): ApiBodyType {
  if (v && BODY_TYPES.includes(v as ApiBodyType)) {
    return v as ApiBodyType;
  }
  return 'JSON';
}

/** 归一化 HTTP 方法。 */
function toMethod(v: string | null | undefined): ApiHttpMethod {
  if (v === 'POST') {
    return 'POST';
  }
  return 'GET';
}

/** 归一化鉴权类型。 */
function toAuthType(v: string | null | undefined): ApiAuthType {
  return v === 'BASIC_AUTH' ? 'BASIC_AUTH' : 'NO_AUTH';
}

/** 归一化分页类型。 */
function toPaginationType(v: string | null | undefined): ApiPaginationType {
  return v === 'PAGE_BASED' ? 'PAGE_BASED' : 'NONE';
}

/** 归一化参数注入位置。 */
function toParamLocation(v: string | null | undefined): ApiParamLocation {
  if (v === 'query' || v === 'body' || v === 'header') {
    return v;
  }
  return 'header';
}

/** 前置操作响应 → 草稿。 */
function preOpResponseToDraft(pre: ApiPreOperationConfigResponse): ApiPreOperationDraft {
  return {
    enabled: pre.enabled,
    url: pre.url ?? '',
    method: toMethod(pre.method),
    headers: pre.headers ?? {},
    params: pre.params ?? {},
    body: pre.body ?? '',
    bodyType: toBodyType(pre.bodyType),
    paramMappings: (pre.paramMappings ?? []).map((m) => ({
      paramName: m.paramName ?? '',
      paramLocation: toParamLocation(m.paramLocation),
      jsonPath: m.jsonPath ?? '',
    })),
  };
}

/**
 * API 表响应 + 字段映射响应 → 编辑态草稿；鉴权密码不回显（password 置空，credentialConfigured 透传供 UI 提示）。
 */
export function apiSchemaToDraft(schema: ApiSchemaResponse, fields: ApiFieldResponse[]): ApiSchemaDraft {
  return {
    id: schema.id,
    name: schema.name,
    url: schema.url,
    method: toMethod(schema.method),
    headers: schema.headers ?? {},
    params: schema.params ?? {},
    body: schema.body ?? '',
    bodyType: toBodyType(schema.bodyType),
    rootPath: schema.jsonPathConfig ?? '$',
    timeout: schema.timeout ?? API_DEFAULT_TIMEOUT,
    retryCount: schema.retryCount ?? API_DEFAULT_RETRY,
    auth: {
      authType: toAuthType(schema.authConfig?.authType),
      username: schema.authConfig?.username ?? '',
      password: '',
      credentialConfigured: schema.authConfig?.credentialConfigured === true,
    },
    pagination: {
      paginationType: toPaginationType(schema.paginationConfig?.paginationType),
      pageParamName: schema.paginationConfig?.pageParamName ?? '',
      sizeParamName: schema.paginationConfig?.sizeParamName ?? '',
      pageSize: schema.paginationConfig?.pageSize ?? 20,
      maxPages: schema.paginationConfig?.maxPages ?? 100,
      totalCountJsonPath: schema.paginationConfig?.totalCountJsonPath ?? '',
    },
    preOperations: (schema.preOperationConfigs ?? []).map(preOpResponseToDraft),
    fields: (fields ?? []).map((f) => ({
      originalName: f.originalName ?? '',
      displayName: f.displayName ?? f.originalName ?? '',
      jsonPath: f.jsonPath ?? '',
      fieldType: f.fieldType ?? 'string',
      description: f.description ?? '',
    })),
  };
}

/** 前置操作草稿 → 请求。 */
function preOpDraftToRequest(pre: ApiPreOperationDraft): PreOperationConfigRequest {
  const mappings: ParamMappingRequest[] = (pre.paramMappings ?? []).map((m) => ({
    paramName: m.paramName,
    paramLocation: m.paramLocation,
    jsonPath: m.jsonPath,
  }));
  return {
    enabled: pre.enabled,
    url: pre.url,
    method: pre.method,
    headers: pre.headers,
    params: pre.params,
    body: pre.body,
    bodyType: pre.bodyType,
    paramMappings: mappings,
  };
}

/** 草稿中的空白映射字段过滤（提交前保留有内容的 header/param 行，与 KvEditor 语义一致）。 */
function trimKeyValue(map: ApiKeyValue): ApiKeyValue {
  const out: ApiKeyValue = {};
  for (const [k, v] of Object.entries(map)) {
    if (k.trim().length === 0) {
      continue;
    }
    out[k.trim()] = v ?? '';
  }
  return out;
}

/**
 * API 表草稿 → ApiSchemaRequest（完成请求体侧的字段名转换：rootPath→jsonPathConfig；
 * pagination.pageParamName→pageNumberParamName；pagination.sizeParamName→pageSizeParamName；auth 展平）。
 * 编辑态密码留空表示沿用：`authPassword` 传 undefined，序列化时省略字段。
 */
export function draftToApiSchemaRequest(draft: ApiSchemaDraft, ctx: ApiSchemaValidateContext): ApiSchemaRequest {
  const isBasicAuth = draft.auth.authType === 'BASIC_AUTH';
  const isPageBased = draft.pagination.paginationType === 'PAGE_BASED';
  const keepExistingPassword = ctx.mode === 'edit' && draft.auth.credentialConfigured && isBlank(draft.auth.password);
  const request: ApiSchemaRequest = {
    name: draft.name,
    method: draft.method,
    url: draft.url,
    headers: trimKeyValue(draft.headers),
    params: trimKeyValue(draft.params),
    body: draft.body,
    bodyType: draft.bodyType,
    jsonPathConfig: draft.rootPath,
    timeout: draft.timeout,
    retryCount: draft.retryCount,
    authType: draft.auth.authType,
    authUsername: isBasicAuth ? draft.auth.username : undefined,
    authPassword: isBasicAuth && !keepExistingPassword && !isBlank(draft.auth.password) ? draft.auth.password : undefined,
    paginationType: draft.pagination.paginationType,
    pageNumberParamName: isPageBased ? draft.pagination.pageParamName : undefined,
    pageSizeParamName: isPageBased ? draft.pagination.sizeParamName : undefined,
    totalCountJsonPath: isPageBased ? draft.pagination.totalCountJsonPath : undefined,
    pageSize: isPageBased ? draft.pagination.pageSize : undefined,
    maxPages: isPageBased ? draft.pagination.maxPages : undefined,
    preOperationConfigs: draft.preOperations.map(preOpDraftToRequest),
    fields: draft.fields.length > 0 ? draft.fields : undefined,
  };
  return request;
}

/**
 * API 表草稿 → 试解析请求（ParseApiResponseRequest；字段命名回到响应侧的 pageParamName/sizeParamName，rootPath 保持 rootPath）。
 */
export function draftToParseRequest(draft: ApiSchemaDraft, connectionId?: number): ParseApiResponseRequest {
  const isPageBased = draft.pagination.paginationType === 'PAGE_BASED';
  return {
    connectionId,
    url: draft.url,
    method: draft.method,
    headers: trimKeyValue(draft.headers),
    params: trimKeyValue(draft.params),
    body: draft.body,
    bodyType: draft.bodyType,
    authType: draft.auth.authType,
    authUsername: draft.auth.authType === 'BASIC_AUTH' ? draft.auth.username : undefined,
    authPassword: draft.auth.authType === 'BASIC_AUTH' ? draft.auth.password : undefined,
    timeout: draft.timeout,
    retryCount: draft.retryCount,
    rootPath: draft.rootPath,
    paginationType: draft.pagination.paginationType,
    pageParamName: isPageBased ? draft.pagination.pageParamName : undefined,
    sizeParamName: isPageBased ? draft.pagination.sizeParamName : undefined,
    totalCountJsonPath: isPageBased ? draft.pagination.totalCountJsonPath : undefined,
    pageSize: isPageBased ? draft.pagination.pageSize : undefined,
    maxPages: isPageBased ? draft.pagination.maxPages : undefined,
    preOperationConfigs: draft.preOperations.map(preOpDraftToRequest),
  };
}

/** 前置操作草稿 + 当前鉴权 → PreOperationTestRequest（试跑单卡）。 */
export function preOpToTestRequest(pre: ApiPreOperationDraft, auth: ApiAuthDraft): PreOperationTestRequest {
  return {
    preOperationConfig: preOpDraftToRequest(pre),
    authType: auth.authType,
    authUsername: auth.authType === 'BASIC_AUTH' ? auth.username : undefined,
    authPassword: auth.authType === 'BASIC_AUTH' ? auth.password : undefined,
  };
}

/** 取类型清单：优先使用后端返回；空数组/null 时回落静态。 */
export function subTypeOptions(types: DatasourceTypeResponse[] | null | undefined): DatasourceTypeResponse[] {
  if (!types || types.length === 0) {
    return DEFAULT_DATASOURCE_TYPES;
  }
  return types;
}

/** 归一化 JDBC 主机地址（trim）；供 JdbcFormDrawer 提交前使用。 */
export function normalizeHost(host: string | null | undefined): string {
  if (isBlank(host)) {
    return '';
  }
  return String(host).trim();
}

/** 归一化数据库名。 */
export function normalizeDatabase(database: string | null | undefined): string {
  if (isBlank(database)) {
    return '';
  }
  return String(database).trim();
}

/** 校验 JDBC 表单必填项：host/database/username（后端 @NotBlank）；返回错误映射。 */
export function validateJdbcForm(config: {
  host: string;
  port: number;
  database: string;
  username: string;
  schema?: string;
  subType?: string;
}): Record<string, string> {
  const errors: Record<string, string> = {};
  if (isBlank(config.host)) {
    errors.host = '主机名不能为空';
  }
  const portErr = validatePort(config.port);
  if (portErr) {
    errors.port = portErr;
  }
  if (isBlank(config.database)) {
    errors.database = '数据库名不能为空';
  }
  if (isBlank(config.username)) {
    errors.username = '用户名不能为空';
  }
  const schemaErr = validatePgSchema(config.schema, config.subType);
  if (schemaErr) {
    errors.schema = schemaErr;
  }
  return errors;
}
