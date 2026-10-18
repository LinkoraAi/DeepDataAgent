import { get, buildQuery } from '@/shared/api/http';
import { uploadSessionFile } from '@/shared/api/upload';
import { downloadFile } from '@/shared/api/blob';
import type { FileListResp } from '@/shared/types/api';

/**
 * 会话文件端点（后端 FileController，前缀 /api/v1/cloud/files，云资源域 snake_case）。
 * 契约要点（与后端代码逐字段核对）：
 * - 列表参数真实名：purpose / scope_id / cursor / limit（Integer）；
 * - 列表响应为特例形态 {data, nextCursor}（驼峰 nextCursor，非标准 CursorPage）；
 * - 上传 multipart：part 名 file + 文本字段 purpose（必填）/ filename / metadata（JSON 文本 ≤8KB）；
 *   仅文本类文件、单文件 ≤50MB（违规 400，超限 413）；
 * - 下载 GET /{id}/content：downloadable=false → 403，存储缺失 → 500；
 * - 后端无删除 / 搜索端点（清理随 Session 生命周期承载），前端 MUST NOT 提供删除入口。
 */

/** API 前缀（版本化云资源路径）。 */
const BASE = '/api/v1/cloud/files';

/** 文件作用域（后端 FileScopeResponse：未关联时为 null）。 */
export interface FileScopeWire {
  /** 作用域资源 ID（如 sess_...） */
  id: string;
  /** 作用域资源类型（当前仅 session） */
  type: string;
}

/** 文件对象（后端 FileResponse 序列化形态；元数据不含内容）。 */
export interface FileWire {
  /** 文件业务 ID（file_ 前缀） */
  id: string;
  /** 对象类型（恒为 file） */
  type: 'file';
  /** 文件名 */
  filename: string;
  /** 内容类型（服务端探测） */
  mime_type: string;
  /** 内容字节长度 */
  size_bytes: number;
  /** 文件用途（user_upload/tool_output/skill_output/session_resource/agent_output） */
  purpose: string;
  /** 文件状态（当前恒为 ready） */
  status: string;
  /** 是否可经 /content 直接下载（由 purpose 派生；用户上传类为 false） */
  downloadable: boolean;
  /** 文件作用域（未关联为 null） */
  scope: FileScopeWire | null;
  /** 自定义元数据 JSON 文本（线格式为字符串，缺省 "{}"） */
  metadata: string;
  /** 创建时间（ISO 字符串） */
  created_at: string;
  /** 更新时间（ISO 字符串） */
  updated_at: string;
}

/** 列表查询参数（后端 @RequestParam 真实参数名：purpose/scope_id/cursor/limit）。 */
export interface ListFilesParams {
  /** 用途过滤 */
  purpose?: string;
  /** 作用域 ID 过滤（如会话 ID） */
  scope_id?: string;
  /** 翻页游标（上页响应 nextCursor） */
  cursor?: string;
  /** 每页条数 */
  limit?: number;
}

/** 上传可选字段（filename 覆盖原始文件名；metadata 为 JSON 文本）。 */
export interface UploadFileOptions {
  /** 文件用途（必填，管理端上传固定 user_upload） */
  purpose: string;
  /** 显式文件名（可选，优先于 multipart 原始名） */
  filename?: string;
  /** 自定义元数据 JSON 文本（可选，≤8KB） */
  metadata?: string;
}

/** 游标分页列出文件（响应键为 data / nextCursor 驼峰特例）。 */
export function listFiles(params: ListFilesParams = {}): Promise<FileListResp<FileWire>> {
  const query = buildQuery({
    purpose: params.purpose,
    scope_id: params.scope_id,
    cursor: params.cursor,
    limit: params.limit,
  });
  return get<FileListResp<FileWire>>(`${BASE}${query}`);
}

/** 上传文本类文件（multipart part 名 file；违规 400 / 超限 413 抛 ApiError 由调用方直显）。 */
export function uploadFile(file: File, options: UploadFileOptions): Promise<FileWire> {
  return uploadSessionFile<FileWire>(BASE, file, {
    purpose: options.purpose,
    filename: options.filename,
    metadata: options.metadata,
  });
}

/** 文件详情（元数据，不含内容）。 */
export function getFile(id: string): Promise<FileWire> {
  return get<FileWire>(`${BASE}/${encodeURIComponent(id)}`);
}

/** 下载文件内容（二进制流；downloadable=false 时后端 403）。 */
export function downloadFileContent(id: string): Promise<void> {
  return downloadFile(`${BASE}/${encodeURIComponent(id)}/content`, id);
}
