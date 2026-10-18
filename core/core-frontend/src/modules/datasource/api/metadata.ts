import { get, put, buildQuery } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 元数据浏览端点（后端 DatasourceController 的「② 表 / 字段 / 注释 / 数据预览」段，前缀 /api/v1/datasource）。
 */

/** 表 / 字段注释更新请求（UpdateCommentRequest）。 */
export interface UpdateCommentRequest {
  /** 注释内容（0-150 字符） */
  comment: string;
}

/** 表元数据响应（TableInfoResponse）。 */
export interface TableInfoResponse {
  /** 表 id */
  id: number;
  /** 所属 schema id */
  databaseSchemaId: number;
  /** 表名 */
  tableName: string;
  /** 原始表注释 */
  tableComment: string | null;
  /** 自定义表注释（优先展示） */
  tableCustomComment: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 字段元数据响应（ColumnInfoResponse）。 */
export interface ColumnInfoResponse {
  /** 字段 id */
  id: number;
  /** 所属表 id */
  tableId: number;
  /** 字段名 */
  columnName: string;
  /** 数据类型 */
  dataType: string | null;
  /** 原始字段注释 */
  columnComment: string | null;
  /** 自定义字段注释（优先展示） */
  columnCustomComment: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

const BASE = '/api/v1/datasource';

/** 分页查询某连接下的表元数据（默认每页 50）。 */
export function listTables(
  connectionId: number,
  params: { keyword?: string; page?: number; size?: number },
): Promise<PaginatedResp<TableInfoResponse>> {
  return get<PaginatedResp<TableInfoResponse>>(`${BASE}/connections/${connectionId}/tables${buildQuery(params)}`);
}

/** 查询某张表的字段列表（保持原始顺序）。 */
export function listColumns(tableId: number): Promise<ColumnInfoResponse[]> {
  return get<ColumnInfoResponse[]>(`${BASE}/tables/${tableId}/columns`);
}

/** 更新表自定义注释（0-150 字符）。 */
export function updateTableComment(tableId: number, comment: string): Promise<void> {
  return put<void>(`${BASE}/tables/${tableId}/comment`, { comment });
}

/** 更新字段自定义注释（0-150 字符）。 */
export function updateColumnComment(columnId: number, comment: string): Promise<void> {
  return put<void>(`${BASE}/columns/${columnId}/comment`, { comment });
}

/** 预览表数据（只读；仅启用态可预览；行数上限 100）。 */
export function previewTableData(
  connectionId: number,
  tableName: string,
  limit: number,
): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>(
    `${BASE}/connections/${connectionId}/tables/${encodeURIComponent(tableName)}/preview${buildQuery({ limit })}`,
  );
}
