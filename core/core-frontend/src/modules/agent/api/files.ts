/**
 * 文件接口模块（对齐后端 FileController：/api/v1/cloud/files，文件一等资源）。
 * <p>上传走 multipart（purpose 必填、≤50MB 文本类）；列表为游标分页
 * （purpose / scope_id 过滤）；下载为二进制直链（downloadable=false 时服务端 403）。
 * 该端点与云端资源统一前缀（/api/v1/cloud），响应字段 snake_case（对齐后端规格）。</p>
 */
import { fetchJson } from '@/shared/api/http';

/** 文件用途（五态契约值）。 */
export const FILE_PURPOSES = [
  'user_upload',
  'tool_output',
  'skill_output',
  'session_resource',
  'agent_output',
] as const;

export type FilePurpose = (typeof FILE_PURPOSES)[number];

/** 文件作用域（关联会话时为 {id, type: "session"}，未关联为 null）。 */
export interface FileScopeDto {
  id: string;
  type: string;
}

/** 文件 DTO（对齐后端 FileResponse，元数据不含内容；对外字段名 snake_case）。 */
export interface FileDto {
  id: string;
  type: string;
  filename: string;
  mime_type: string;
  size_bytes: number;
  purpose: FilePurpose | string;
  status: string;
  downloadable: boolean;
  scope: FileScopeDto | null;
  metadata: string;
  created_at: string;
  updated_at: string;
}

/** 文件列表响应（游标分页形态，对齐 FileListResponse）。 */
export interface FileListResult {
  data: FileDto[];
  nextCursor: string | null;
}

const FILES_API_BASE = '/api/v1/cloud/files';

/** multipart 上传文件（purpose 必填；metadata 为 JSON 文本，可空）。 */
export function uploadFile(file: File, purpose: FilePurpose, metadata?: string): Promise<FileDto> {
  const form = new FormData();
  form.append('file', file);
  form.append('purpose', purpose);
  if (metadata) {
    form.append('metadata', metadata);
  }
  // 不显式设置 Content-Type，由 fetch 自动生成 multipart boundary
  return fetchJson<FileDto>(FILES_API_BASE, { method: 'POST', body: form });
}

/** 游标分页列出文件（purpose / scopeId 过滤，limit ≤ 0 时取服务端缺省）。 */
export function listFiles(options?: {
  purpose?: FilePurpose;
  scopeId?: string;
  cursor?: string;
  limit?: number;
}): Promise<FileListResult> {
  const query = new URLSearchParams();
  if (options?.purpose) {
    query.set('purpose', options.purpose);
  }
  if (options?.scopeId) {
    query.set('scope_id', options.scopeId);
  }
  if (options?.cursor) {
    query.set('cursor', options.cursor);
  }
  if (options?.limit) {
    query.set('limit', String(options.limit));
  }
  const suffix = query.toString() ? `?${query}` : '';
  return fetchJson<FileListResult>(`${FILES_API_BASE}${suffix}`);
}

/** 文件详情（元数据，不含内容）。 */
export function getFile(fileId: string): Promise<FileDto> {
  return fetchJson<FileDto>(`${FILES_API_BASE}/${encodeURIComponent(fileId)}`);
}

/** 文件内容下载直链（浏览器直接导航 / a[download] 使用）。 */
export function fileDownloadUrl(fileId: string): string {
  return `${FILES_API_BASE}/${encodeURIComponent(fileId)}/content`;
}
