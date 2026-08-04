import { post } from './http';
import type { DatasourceConnection } from '@/modules/agent/types';
import type { PaginatedResponse } from './http';

/**
 * List datasources
 */
export async function listDatasources(
  page: number = 1, 
  size: number = 100, 
  status?: string, 
  type?: string, 
  keyword?: string
): Promise<PaginatedResponse<DatasourceConnection>> {
  return post<PaginatedResponse<DatasourceConnection>>('/datasource/list', {
    page,
    size,
    status,
    type,
    keyword
  });
}

/**
 * Create datasource
 */
export async function createDatasource(params: {
  name: string;
  type: string;
  subType?: string;
  description?: string;
  jdbcConfig?: {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    schema?: string;
  };
}): Promise<void> {
  return post('/datasource/create', params);
}

/**
 * Update datasource
 */
export async function updateDatasource(params: {
  id: number;
  name: string;
  description?: string;
  jdbcConfig?: {
    host: string;
    port: number;
    database: string;
    username: string;
    password?: string;
    schema?: string;
  };
}): Promise<void> {
  return post('/datasource/update', params);
}

/**
 * Delete datasource
 */
export async function deleteDatasource(id: number): Promise<void> {
  return post('/datasource/delete', { id });
}

/**
 * Test datasource connection
 */
export async function testConnection(params: {
  id?: number;
  name: string;
  type: string;
  subType?: string;
  jdbcConfig?: {
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    schema?: string;
  };
}): Promise<string> {
  return post('/datasource/test-connection', params);
}

/**
 * Enable datasource
 */
export async function enableDatasource(id: number): Promise<void> {
  return post('/datasource/enable', { id });
}

/**
 * Disable datasource
 */
export async function disableDatasource(id: number): Promise<void> {
  return post('/datasource/disable', { id });
}

/**
 * Sync datasource metadata
 */
export async function syncDatasource(id: number): Promise<void> {
  return post('/datasource/sync', { id });
}

/**
 * List tables in datasource
 */
export interface TableResponse {
  id: number;
  type: 'JDBC' | 'API';
  databaseSchemaId?: number;
  connectionId?: number;
  tableName: string;
  tableComment?: string;
  tableCustomComment?: string;
  description?: string;
  url?: string;
  method?: string;
  jsonPath?: string;
  fields?: ApiFieldResponse[];
  paginationConfig?: any;
  createdAt?: string;
  updatedAt?: string;
}

export interface ApiFieldResponse {
  id: number;
  apiSchemaId: number;
  originalName: string;
  fieldType: string;
  description?: string;
  jsonPath?: string;
  createdAt?: string;
  updatedAt?: string;
}

export async function listTables(
  connectionId: number,
  type: string,
  keyword?: string,
  page: number = 1,
  size: number = 100
): Promise<PaginatedResponse<TableResponse>> {
  return post<PaginatedResponse<TableResponse>>('/datasource/table/list', {
    connectionId,
    type,
    keyword,
    page,
    size
  });
}

/**
 * List columns in table
 */
export interface ColumnInfoResponse {
  id: number;
  tableId: number;
  columnName: string;
  dataType: string;
  columnComment?: string;
  columnCustomComment?: string;
  createdAt?: string;
  updatedAt?: string;
}

export async function listColumns(
  tableId?: number,
  schemaId?: number,
  type?: string,
  page: number = 1,
  size: number = 100
): Promise<ColumnInfoResponse[]> {
  return post<ColumnInfoResponse[]>('/datasource/column/list', {
    tableId,
    schemaId,
    type,
    page,
    size
  });
}

/**
 * Preview table data
 */
export async function previewTableData(
  connectionId: number,
  tableName: string,
  type: string,
  limit: number = 100
): Promise<any[]> {
  return post<any[]>('/datasource/table/preview', {
    connectionId,
    tableName,
    type,
    limit
  });
}

/**
 * Get API schema detail
 */
export interface ApiSchemaDetailResponse {
  id: number;
  connectionId: number;
  name: string;
  url: string;
  method: string;
  headers?: Record<string, string>;
  params?: Record<string, string>;
  body?: string;
  bodyType?: string;
  jsonPathConfig?: string;
  timeout?: number;
  retryCount?: number;
  authConfig?: any;
  paginationConfig?: any;
  preOperationConfigs?: any[];
  fields?: ApiFieldResponse[];
  createdAt?: string;
  updatedAt?: string;
}

export async function getApiSchemaDetail(schemaId: number): Promise<ApiSchemaDetailResponse> {
  return post<ApiSchemaDetailResponse>('/datasource/api-schema/detail', { id: schemaId });
}
