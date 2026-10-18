import { get, post, buildQuery, requestEnvelope } from '@/shared/api/http';
import { uploadDocument as uploadDocumentForm } from '@/shared/api/upload';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 文档端点（后端 DocumentController，前缀 /api/v1/knowledge-base/documents）。
 * <p>契约要点（对齐后端真实代码）：</p>
 * <ul>
 *   <li>列表为页码分页（kbId 必填，fileName/status 可选，page 默认 1、size 默认 10 上限 100），
 *       响应 PaginatedResp&lt;DocumentWire&gt;；</li>
 *   <li>DocumentWire 的 s3File / chunkStrategy 为后端二次编码的「JSON 字符串」（可能为 null），
 *       与文件内容判重哈希 fileContentHash（64 位小写十六进制）一并原样透传；</li>
 *   <li>删除端点返回 HTTP 200 + body code="202"（异步受理、清退后台推进），经 requestEnvelope 感知 code
 *       回 {accepted} 供上层进入 DELETING 并轮询收敛；</li>
 *   <li>上传为 multipart（file + meta 两个 part），meta 仅登记 kbId/fileName/fileType（源对象引用、
 *       文件大小、判重哈希由服务端派生）；REJECT 冲突后端 409（message 直显）、SKIP 回 skipped=true、
 *       OVERWRITE 回 overwritten 旧文档列表；</li>
 *   <li>content / download 为二进制流（鉴权预览 / 附件下载），路径由本模块导出供 blob 基座拼接。</li>
 * </ul>
 */

/** 统一前缀（版本 v1 由 http 基座按后端约定拼接）。 */
const BASE = '/api/v1/knowledge-base/documents';

/** 删除受理响应业务码（异步受理，HTTP 外层仍为 200）。 */
const ACCEPTED_CODE = '202';

/** 文档六态（DocumentStatus 枚举名直透，已删除以行缺失表达不再暴露）。 */
export type DocumentStatusWire =
  | 'PENDING'
  | 'PROCESSING'
  | 'PROCESSED'
  | 'FAILED'
  | 'DELETING'
  | 'DELETE_FAILED'
  | string;

/**
 * 文档线格式响应（DocumentResponse 同构）。
 * <p>配置类字段（s3File、chunkStrategy、sourceFileProfile）为 JSON 字符串（可为 null）。</p>
 */
export interface DocumentWire {
  /** 主键 */
  id: number;
  /** 所属知识库ID */
  kbId: number;
  /** 文件名 */
  fileName: string;
  /** 文件格式（FileType 枚举名） */
  fileType: string;
  /** 处理状态（六态） */
  status: DocumentStatusWire;
  /** 失败原因（仅 FAILED / DELETE_FAILED 携带） */
  errorMessage: string | null;
  /** 文件大小（字节） */
  fileSize: number | null;
  /** 分块（切片）数量 */
  chunkCount: number | null;
  /** 导入方式（枚举名） */
  importType: string | null;
  /** 源文件对象存储引用 JSON 字符串 */
  s3File: string | null;
  /** 判重内容哈希（64 位小写十六进制 SHA-256） */
  fileContentHash: string | null;
  /** 文档级分块策略 JSON 字符串 */
  chunkStrategy: string | null;
  /** 源文件关键信息 JSON 字符串 */
  sourceFileProfile: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 判重预检命中的重复文档摘要（DuplicateDocumentResponse 同构；matchAxes 为对外契约字面量 fileName|contentHash）。 */
export interface DuplicateWire {
  /** 命中的既有文档ID */
  id: number;
  /** 命中的既有文档文件名 */
  fileName: string;
  /** 命中判定轴标签列表（'fileName' / 'contentHash'） */
  matchAxes: string[];
}

/** 上传结果（UploadDocumentResponse 同构）。 */
export interface UploadResultWire {
  /** 是否因命中库内重复且动作为 SKIP 被跳过 */
  skipped: boolean;
  /** 新登记文档或被跳过的既有文档 */
  document: DocumentWire;
  /** OVERWRITE 路径下被受理为 DELETING 的旧文档列表（非覆盖为空） */
  overwritten: DocumentWire[];
}

/** 文档列表查询参数（kbId 必填）。 */
export type DocumentListParams = {
  /** 所属知识库ID（必填） */
  kbId: number;
  /** 文件名关键字 */
  fileName?: string;
  /** 状态过滤（DocumentStatus 枚举名） */
  status?: string;
  /** 页码（从 1 起） */
  page?: number;
  /** 每页条数（上限 100） */
  size?: number;
};

/** 预检请求参数（kbId 必填，fileName 与 contentHash 至少其一）。 */
export interface PrecheckPayload {
  /** 目标知识库ID */
  kbId: number;
  /** 待检文件名 */
  fileName?: string;
  /** 待检内容哈希（64 位十六进制 SHA-256，大小写不敏感） */
  contentHash?: string;
}

/** 上传登记元数据（multipart 的 meta part；后端 UploadDocumentRequest 同构，可选字段留空表示继承库级配置）。 */
export interface UploadMetaPayload {
  /** 所属知识库ID */
  kbId: number;
  /** 文件名（文件名判重轴） */
  fileName: string;
  /** 文件格式（FileType 枚举名或扩展名） */
  fileType: string;
  /** 源文件关键信息 JSON（可选） */
  sourceFileProfile?: string;
  /** 文档级分块策略 JSON（可选，为空继承知识库级策略） */
  chunkStrategy?: string;
}

/** 重新解析请求体（可为空表示完全沿用知识库当前分块配置）。 */
export interface ReparsePayload {
  /** 本次重建的分块策略 JSON 覆盖值 */
  chunkStrategyOverride?: string;
}

/** 分页查询指定知识库下的文档列表。 */
export function listDocuments(params: DocumentListParams): Promise<PaginatedResp<DocumentWire>> {
  return get<PaginatedResp<DocumentWire>>(`${BASE}${buildQuery(params)}`);
}

/** 上传判重预检（只读探测，命中重复文档摘要列表；无命中或库未启用检测时为空列表）。 */
export function precheckUpload(payload: PrecheckPayload): Promise<DuplicateWire[]> {
  return post<DuplicateWire[]>(`${BASE}/precheck`, payload);
}

/** 上传文档（multipart file + meta 双 part；服务端派生对象引用/大小/判重哈希并按库级策略处置重复）。 */
export function uploadDocument(file: File, meta: UploadMetaPayload): Promise<UploadResultWire> {
  return uploadDocumentForm<UploadResultWire>(BASE, file, meta);
}

/**
 * 删除文档（异步受理）。
 * <p>HTTP 200 + body code="202" 视为受理成功；调用方据 accepted 进入删除中态并轮询收敛。</p>
 * @returns accepted 为 true 表示删除已受理
 */
export async function deleteDocument(id: number): Promise<{ accepted: boolean }> {
  const envelope = await requestEnvelope<DocumentWire>(`${BASE}/${id}`, { method: 'DELETE' });
  return { accepted: envelope.code === ACCEPTED_CODE };
}

/**
 * 重新解析文档（刷新分块策略快照并重置状态，实际解析由异步摄入任务接管）。
 * <p>BREAKING 语义：解析成功整篇替换将清除该文档<b>全部</b>分块（含人工新增）并按解析结果重建。</p>
 * @param payload 可选分块策略覆盖；不传表示沿用知识库当前配置
 */
export function reparseDocument(id: number, payload?: ReparsePayload): Promise<DocumentWire> {
  return post<DocumentWire>(`${BASE}/${id}/reparse`, payload);
}

/** 查询文档详情。 */
export function getDocument(id: number): Promise<DocumentWire> {
  return get<DocumentWire>(`${BASE}/${id}`);
}

/** 在线预览原文端点路径（inline 流式，供 fetchBlob 拼接）。 */
export function documentContentPath(id: number): string {
  return `${BASE}/${id}/content`;
}

/** 附件下载端点路径（attachment 流式，供 downloadFile 拼接）。 */
export function documentDownloadPath(id: number): string {
  return `${BASE}/${id}/download`;
}

/**
 * 办公类文档纯文本预览响应（DocumentPreviewTextResponse 同构）。
 * <p>DOC / DOCX / XLS / XLSX / PPT / PPTX 浏览器无法原生渲染，由后端 Tika 提取纯文本回传。</p>
 */
export interface DocumentPreviewTextWire {
  /** 文档ID */
  documentId: number;
  /** 文档文件名 */
  fileName: string;
  /** 文件格式（FileType 枚举名） */
  fileType: string;
  /** 提取出的纯文本（无有效文本时为空字符串） */
  text: string;
  /** 是否因达到预览长度上限被截断（true 时前端提示下载查看完整内容） */
  truncated: boolean;
}

/**
 * 在线预览办公类文档的纯文本（DOC / DOCX / XLS / XLSX / PPT / PPTX）。
 * <p>PDF / 图片 / 文本类走 {@code /content} 二进制原文端点，勿调用本端点。</p>
 */
export function previewDocumentText(id: number): Promise<DocumentPreviewTextWire> {
  return get<DocumentPreviewTextWire>(`${BASE}/${id}/preview-text`);
}
