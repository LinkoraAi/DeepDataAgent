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
  type: string;
  subType?: string;
  description?: string;
  jdbcConfig?: {
    host: string;
    port: number;
    database: string;
    username: string;
    password?: string;
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
