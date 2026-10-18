import { get, post, put, del } from '@/shared/api/http';

/**
 * API 数据表端点（后端单一 DatasourceController 承载，前缀 /api/v1/datasource）。
 *
 * 字段命名以后端真实 DTO 为准（camelCase）。核对要点：
 * - 根 JsonPath：请求/响应统一为 `jsonPathConfig`；但「试解析」请求体用的是 `rootPath`。
 * - 分页参数名：请求体为 `pageNumberParamName` / `pageSizeParamName`；
 *   响应体 `paginationConfig` 内为 `pageParamName` / `sizeParamName`；试解析请求亦为 `pageParamName` / `sizeParamName`。
 * - 鉴权：请求体扁平（`authType` / `authUsername` / `authPassword`）；
 *   响应体嵌套 `authConfig`（`authType` / `username` / `credentialConfigured`），密码永不回显。
 */

/** HTTP 方法（GET / POST）。 */
export type ApiHttpMethod = 'GET' | 'POST';

/** 请求体类型（JSON / FORM_URLENCODED / RAW）。 */
export type ApiBodyType = 'JSON' | 'FORM_URLENCODED' | 'RAW';

/** 鉴权类型（NO_AUTH / BASIC_AUTH）。 */
export type ApiAuthType = 'NO_AUTH' | 'BASIC_AUTH';

/** 分页类型（NONE / PAGE_BASED）。 */
export type ApiPaginationType = 'NONE' | 'PAGE_BASED';

/** 前置操作参数注入位置（header / query / body）。 */
export type ApiParamLocation = 'header' | 'query' | 'body';

/** 键值对（请求头 / 查询参数；后端 Map<String,String>，响应可能为 null）。 */
export type ApiKeyValue = Record<string, string>;

/** API 字段类型（后端推断：string / number / boolean / object / array）。 */
export type ApiFieldType = string;

// ==================== 请求体（request DTO） ====================

/** 前置操作参数映射请求（ParamMappingRequest）。 */
export interface ParamMappingRequest {
  /** 参数名 */
  paramName?: string;
  /** 注入位置（header / query / body） */
  paramLocation?: ApiParamLocation | string;
  /** 从响应抽取值的 JsonPath */
  jsonPath?: string;
}

/** 前置操作配置请求（PreOperationConfigRequest）。 */
export interface PreOperationConfigRequest {
  /** 是否启用（启用时 url 与 method 必填） */
  enabled: boolean;
  /** 请求地址 */
  url?: string;
  /** 请求方法 */
  method?: ApiHttpMethod | string;
  /** 请求头 */
  headers?: ApiKeyValue;
  /** 查询参数 */
  params?: ApiKeyValue;
  /** 请求体内容 */
  body?: string;
  /** 请求体类型 */
  bodyType?: ApiBodyType | string;
  /** 参数映射列表 */
  paramMappings?: ParamMappingRequest[];
}

/** API 字段映射请求（ApiFieldRequest）。 */
export interface ApiFieldRequest {
  /** 原始字段名 */
  originalName?: string;
  /** 展示名 */
  displayName?: string;
  /** 字段 JsonPath */
  jsonPath?: string;
  /** 字段类型 */
  fieldType?: ApiFieldType;
  /** 字段描述 */
  description?: string;
}

/** API 数据表配置请求（ApiSchemaRequest，创建与更新共用；更新缺省项沿用原值）。 */
export interface ApiSchemaRequest {
  /** 表名称（必填） */
  name: string;
  /** 请求方法（缺省 GET） */
  method?: ApiHttpMethod | string;
  /** 请求地址（必填） */
  url: string;
  /** 请求头 */
  headers?: ApiKeyValue;
  /** 查询参数 */
  params?: ApiKeyValue;
  /** 请求体内容 */
  body?: string;
  /** 请求体类型 */
  bodyType?: ApiBodyType | string;
  /** 根 JsonPath（数组须以 .* 结尾） */
  jsonPathConfig?: string;
  /** 超时（秒，缺省 180） */
  timeout?: number;
  /** 重试次数（0-5） */
  retryCount?: number;
  /** 鉴权类型 */
  authType?: ApiAuthType | string;
  /** 鉴权用户名 */
  authUsername?: string;
  /** 鉴权密码 */
  authPassword?: string;
  /** 分页类型 */
  paginationType?: ApiPaginationType | string;
  /** 页大小参数名（PAGE_BASED 必填） */
  pageSizeParamName?: string;
  /** 页码参数名（PAGE_BASED 必填） */
  pageNumberParamName?: string;
  /** 总条数 JsonPath */
  totalCountJsonPath?: string;
  /** 每页大小（1-500） */
  pageSize?: number;
  /** 最大页数（1-10000） */
  maxPages?: number;
  /** 前置操作列表 */
  preOperationConfigs?: PreOperationConfigRequest[];
  /** 字段映射列表（非空则全量重建） */
  fields?: ApiFieldRequest[];
}

/** API 响应试解析请求（ParseApiResponseRequest，不产生持久化；根路径字段名为 rootPath）。 */
export interface ParseApiResponseRequest {
  /** 基线数据源 id（可空） */
  connectionId?: number;
  /** 请求地址 */
  url?: string;
  /** 请求方法 */
  method?: ApiHttpMethod | string;
  /** 请求头 */
  headers?: ApiKeyValue;
  /** 查询参数 */
  params?: ApiKeyValue;
  /** 请求体内容 */
  body?: string;
  /** 请求体类型 */
  bodyType?: ApiBodyType | string;
  /** 鉴权类型 */
  authType?: ApiAuthType | string;
  /** 鉴权用户名 */
  authUsername?: string;
  /** 鉴权密码 */
  authPassword?: string;
  /** 超时（秒） */
  timeout?: number;
  /** 重试次数 */
  retryCount?: number;
  /** 根 JsonPath（缺省 $） */
  rootPath?: string;
  /** 分页类型 */
  paginationType?: ApiPaginationType | string;
  /** 页码参数名 */
  pageParamName?: string;
  /** 页大小参数名 */
  sizeParamName?: string;
  /** 总条数 JsonPath */
  totalCountJsonPath?: string;
  /** 每页大小 */
  pageSize?: number;
  /** 最大页数 */
  maxPages?: number;
  /** 前置操作列表 */
  preOperationConfigs?: PreOperationConfigRequest[];
}

/** 前置操作试跑请求（PreOperationTestRequest）。 */
export interface PreOperationTestRequest {
  /** 待试跑的前置操作配置（必填） */
  preOperationConfig: PreOperationConfigRequest;
  /** 试跑携带的鉴权类型（可空） */
  authType?: ApiAuthType | string;
  /** 试跑携带的鉴权用户名 */
  authUsername?: string;
  /** 试跑携带的鉴权密码 */
  authPassword?: string;
}

// ==================== 响应体（response DTO） ====================

/** API 鉴权响应（ApiAuthResponse，密码不回显，仅回是否已配置）。 */
export interface ApiAuthResponse {
  /** 鉴权类型 */
  authType: ApiAuthType | string;
  /** 鉴权用户名 */
  username: string | null;
  /** 是否已配置凭证 */
  credentialConfigured: boolean;
}

/** 分页配置响应（ApiSchemaResponse.PaginationConfig，字段名与请求不同）。 */
export interface ApiPaginationConfigResponse {
  /** 分页类型 */
  paginationType: ApiPaginationType | string;
  /** 页码参数名（响应侧命名） */
  pageParamName: string | null;
  /** 页大小参数名（响应侧命名） */
  sizeParamName: string | null;
  /** 总条数 JsonPath */
  totalCountJsonPath: string | null;
  /** 每页大小 */
  pageSize: number | null;
  /** 最大页数 */
  maxPages: number | null;
}

/** 参数映射响应（ApiSchemaResponse.ParamMapping）。 */
export interface ApiParamMappingResponse {
  /** 参数名 */
  paramName: string | null;
  /** 注入位置 */
  paramLocation: string | null;
  /** 取值 JsonPath */
  jsonPath: string | null;
}

/** 前置操作响应（ApiSchemaResponse.PreOperationConfig）。 */
export interface ApiPreOperationConfigResponse {
  /** 是否启用 */
  enabled: boolean;
  /** 请求地址 */
  url: string | null;
  /** 请求方法 */
  method: string | null;
  /** 请求头 */
  headers: ApiKeyValue | null;
  /** 查询参数 */
  params: ApiKeyValue | null;
  /** 请求体内容 */
  body: string | null;
  /** 请求体类型 */
  bodyType: string | null;
  /** 参数映射列表 */
  paramMappings: ApiParamMappingResponse[];
}

/** API 数据表响应（ApiSchemaResponse，鉴权脱敏）。 */
export interface ApiSchemaResponse {
  /** 表 id */
  id: number;
  /** 所属连接 id */
  connectionId: number;
  /** 表名称 */
  name: string;
  /** 请求地址 */
  url: string;
  /** 请求方法 */
  method: string;
  /** 请求头 */
  headers: ApiKeyValue | null;
  /** 查询参数 */
  params: ApiKeyValue | null;
  /** 请求体内容 */
  body: string | null;
  /** 请求体类型 */
  bodyType: string | null;
  /** 根 JsonPath */
  jsonPathConfig: string | null;
  /** 超时（秒） */
  timeout: number | null;
  /** 重试次数 */
  retryCount: number | null;
  /** 鉴权配置（脱敏） */
  authConfig: ApiAuthResponse | null;
  /** 分页配置（null 表示不分页） */
  paginationConfig: ApiPaginationConfigResponse | null;
  /** 前置操作列表 */
  preOperationConfigs: ApiPreOperationConfigResponse[];
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** API 字段映射响应（ApiFieldResponse）。 */
export interface ApiFieldResponse {
  /** 字段 id */
  id: number;
  /** 所属 API 表 id */
  apiSchemaId: number;
  /** 原始字段名 */
  originalName: string | null;
  /** 展示名 */
  displayName: string | null;
  /** 字段 JsonPath */
  jsonPath: string | null;
  /** 字段类型 */
  fieldType: string | null;
  /** 字段描述 */
  description: string | null;
}

/** API 数据表详情响应（ApiSchemaDetailResponse：表配置 + 字段映射）。 */
export interface ApiSchemaDetailResponse {
  /** 表配置（已脱敏） */
  schema: ApiSchemaResponse;
  /** 字段映射列表 */
  fields: ApiFieldResponse[];
}

/** 解析字段树节点响应（ParsedFieldResponse，递归）。 */
export interface ParsedFieldResponse {
  /** 原始字段名 */
  originalName: string;
  /** 字段 JsonPath */
  jsonPath: string;
  /** 推断类型 */
  fieldType: string;
  /** 子字段列表（叶子为空数组） */
  children: ParsedFieldResponse[];
}

/** 响应试解析结果（ParseApiResponseResponse：字段树 + 样例行）。 */
export interface ParseApiResponseResponse {
  /** 递归字段树 */
  fieldTree: ParsedFieldResponse[];
  /** 样例行（最多 10 行） */
  rows: Record<string, unknown>[];
}

/** 前置操作试跑响应（PreOperationTestResponse）。 */
export interface PreOperationTestResponse {
  /** 是否成功 */
  success: boolean;
  /** 原始响应文本 */
  rawResponse: string | null;
}

const BASE = '/api/v1/datasource';

/** 查询某连接下的 API 数据表列表（脱敏）。 */
export function listApiSchemas(connectionId: number): Promise<ApiSchemaResponse[]> {
  return get<ApiSchemaResponse[]>(`${BASE}/connections/${connectionId}/api-schemas`);
}

/** 在某 API 数据源下创建 API 数据表。 */
export function createApiSchema(connectionId: number, request: ApiSchemaRequest): Promise<ApiSchemaResponse> {
  return post<ApiSchemaResponse>(`${BASE}/connections/${connectionId}/api-schemas`, request);
}

/** 查询 API 数据表详情（表配置 + 字段映射）。 */
export function getApiSchemaDetail(schemaId: number): Promise<ApiSchemaDetailResponse> {
  return get<ApiSchemaDetailResponse>(`${BASE}/api-schemas/${schemaId}`);
}

/** 更新 API 数据表（增量合并；fields 非空则全量重建字段映射）。 */
export function updateApiSchema(schemaId: number, request: ApiSchemaRequest): Promise<ApiSchemaResponse> {
  return put<ApiSchemaResponse>(`${BASE}/api-schemas/${schemaId}`, request);
}

/** 删除 API 数据表（级联删除其字段映射）。 */
export function deleteApiSchema(schemaId: number): Promise<void> {
  return del<void>(`${BASE}/api-schemas/${schemaId}`);
}

/** 发送一次请求并解析响应（返回字段树与前 10 行样例，不持久化）。 */
export function parseApiResponse(request: ParseApiResponseRequest): Promise<ParseApiResponseResponse> {
  return post<ParseApiResponseResponse>(`${BASE}/api-schemas/parse`, request);
}

/** 试跑单个前置操作（返回原始响应文本）。 */
export function testPreOperation(request: PreOperationTestRequest): Promise<PreOperationTestResponse> {
  return post<PreOperationTestResponse>(`${BASE}/api-schemas/pre-operation-test`, request);
}
