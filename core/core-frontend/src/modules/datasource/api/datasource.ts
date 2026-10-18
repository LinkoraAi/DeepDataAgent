import { get, post, put, del, buildQuery } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';
import type { ApiSchemaRequest } from './apiSchema';

/**
 * 数据源连接端点（后端 DatasourceController 的「① 连接生命周期与测试」段，前缀 /api/v1/datasource）。
 *
 * 脱敏约定：任何响应 DTO 均不含 JDBC password；编辑密码留空表示沿用原值。
 */

/** 主类型：JDBC / API。 */
export type DatasourceMainType = 'JDBC' | 'API';

/** JDBC 子类型：MYSQL / CLICKHOUSE / POSTGRESQL（API 类型为空）。 */
export type DatasourceSubType = 'MYSQL' | 'CLICKHOUSE' | 'POSTGRESQL';

/** 数据源状态。 */
export type DatasourceStatus = 'ENABLED' | 'DISABLED';

// ==================== 请求体（request DTO） ====================

/** JDBC 连接配置请求（JdbcConfigRequest）。 */
export interface JdbcConfigRequest {
  /** 主机名 / IP（必填） */
  host: string;
  /** 端口（1-65535） */
  port?: number;
  /** 数据库名（必填） */
  database: string;
  /** 用户名（必填） */
  username: string;
  /** 密码（编辑场景留空表示沿用原密码） */
  password?: string;
  /** schema（PostgreSQL 适用；留空按 public 处理） */
  schema?: string;
}

/** 创建数据源请求（CreateDatasourceRequest）。 */
export interface CreateDatasourceRequest {
  /** 名称（必填，1-100，全局唯一） */
  name: string;
  /** 主类型（必填 JDBC / API） */
  type: DatasourceMainType | string;
  /** JDBC 子类型（API 可空） */
  subType?: DatasourceSubType | string;
  /** 描述（可空，≤500） */
  description?: string;
  /** JDBC 连接配置（JDBC 类型必填） */
  jdbcConfig?: JdbcConfigRequest;
  /** API 数据表配置列表（API 类型可选，允许创建时内嵌多张表） */
  apiSchemas?: ApiSchemaRequest[];
}

/** 编辑数据源请求（UpdateDatasourceRequest，增量语义；类型不可改）。 */
export interface UpdateDatasourceRequest {
  /** 新名称（可空，≤100） */
  name?: string;
  /** 新描述（可空，≤500） */
  description?: string;
  /** 新 JDBC 连接配置（可空；携带则整体替换，密码留空沿用原值） */
  jdbcConfig?: JdbcConfigRequest;
}

/** 连接测试请求（TestConnectionRequest，两种场景共用：id 非空按已存连接测，id 空按临时配置测）。 */
export interface TestConnectionRequest {
  /** 已保存数据源 id（可空） */
  id?: number;
  /** 临时配置主类型 */
  type?: DatasourceMainType | string;
  /** JDBC 子类型 */
  subType?: DatasourceSubType | string;
  /** JDBC 主机名 */
  host?: string;
  /** JDBC 端口 */
  port?: number;
  /** JDBC 数据库名 */
  database?: string;
  /** JDBC 用户名 */
  username?: string;
  /** JDBC 密码 */
  password?: string;
  /** JDBC schema */
  schema?: string;
  /** API 请求地址 */
  apiUrl?: string;
  /** API 请求方法 */
  apiMethod?: string;
  /** API 请求头 */
  apiHeaders?: Record<string, string>;
  /** API 查询参数 */
  apiParams?: Record<string, string>;
  /** API 请求体 */
  apiBody?: string;
  /** API 请求体类型 */
  apiBodyType?: string;
  /** API 鉴权类型 */
  apiAuthType?: string;
  /** API 鉴权用户名 */
  apiAuthUsername?: string;
  /** API 鉴权密码 */
  apiAuthPassword?: string;
  /** API 超时（秒） */
  apiTimeout?: number;
  /** API 根 JsonPath */
  apiJsonPath?: string;
}

// ==================== 响应体（response DTO） ====================

/** JDBC 连接配置响应（JdbcConfigResponse，无 password）。 */
export interface JdbcConfigResponse {
  /** 主机名 / IP */
  host: string;
  /** 端口 */
  port: number | null;
  /** 数据库名 */
  database: string;
  /** 用户名 */
  username: string;
  /** schema */
  schema: string | null;
}

/** 数据源连接响应（DatasourceResponse，不含密码）。 */
export interface DatasourceResponse {
  /** 连接 id */
  id: number;
  /** 名称 */
  name: string;
  /** 主类型 */
  type: DatasourceMainType | string;
  /** JDBC 子类型（API 为 null） */
  subType: DatasourceSubType | string | null;
  /** 状态 */
  status: DatasourceStatus | string;
  /** JDBC 连接配置（脱敏，API 为 null） */
  jdbcConfig: JdbcConfigResponse | null;
  /** 描述 */
  description: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 受支持的数据源类型响应（DatasourceTypeResponse）。 */
export interface DatasourceTypeResponse {
  /** 主类型 */
  type: DatasourceMainType | string;
  /** 子类型 */
  subType: DatasourceSubType | string;
  /** 展示名 */
  name: string;
  /** 分类（OLTP / OLAP / API） */
  category: string;
  /** 默认端口（API 为 null） */
  defaultPort: number | null;
}

/** 连接测试响应（TestConnectionResponse）。 */
export interface TestConnectionResponse {
  /** 是否连接成功 */
  success: boolean;
  /** 结果说明 */
  message: string;
}

const BASE = '/api/v1/datasource';

/** 查询受支持的数据源类型清单（MySQL / ClickHouse / PostgreSQL / API）。 */
export function listDatasourceTypes(): Promise<DatasourceTypeResponse[]> {
  return get<DatasourceTypeResponse[]>(`${BASE}/types`);
}

/** 创建数据源（JDBC 保存后自动同步元数据；API 可内嵌多张表）。 */
export function createDatasource(request: CreateDatasourceRequest): Promise<DatasourceResponse> {
  return post<DatasourceResponse>(`${BASE}/connections`, request);
}

/** 分页查询数据源连接（名称关键字、类型、状态过滤）。 */
export function listDatasources(params: {
  keyword?: string;
  type?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PaginatedResp<DatasourceResponse>> {
  return get<PaginatedResp<DatasourceResponse>>(`${BASE}/connections${buildQuery(params)}`);
}

/** 查询数据源连接详情（脱敏）。 */
export function getDatasource(id: number): Promise<DatasourceResponse> {
  return get<DatasourceResponse>(`${BASE}/connections/${id}`);
}

/** 编辑数据源（类型不可改；JDBC 密码留空沿用原值）。 */
export function updateDatasource(id: number, request: UpdateDatasourceRequest): Promise<DatasourceResponse> {
  return put<DatasourceResponse>(`${BASE}/connections/${id}`, request);
}

/** 删除数据源（仅禁用态可删；级联清理元数据）。 */
export function deleteDatasource(id: number): Promise<void> {
  return del<void>(`${BASE}/connections/${id}`);
}

/** 启用数据源（启用前真实测试连接，失败 400 保态）。 */
export function enableDatasource(id: number): Promise<void> {
  return post<void>(`${BASE}/connections/${id}/enable`);
}

/** 禁用数据源（仅启用态可禁用）。 */
export function disableDatasource(id: number): Promise<void> {
  return post<void>(`${BASE}/connections/${id}/disable`);
}

/** 手动同步元数据（仅启用态可同步）。 */
export function syncDatasource(id: number): Promise<void> {
  return post<void>(`${BASE}/connections/${id}/sync`);
}

/** 测试已保存数据源的连接（按 id，不产生持久化）。 */
export function testSavedDatasource(id: number): Promise<TestConnectionResponse> {
  return post<TestConnectionResponse>(`${BASE}/connections/${id}/test`);
}

/** 测试未保存的临时配置（JDBC 或 API，不产生持久化）。 */
export function testTemporaryDatasource(request: TestConnectionRequest): Promise<TestConnectionResponse> {
  return post<TestConnectionResponse>(`${BASE}/connections/test`, request);
}
