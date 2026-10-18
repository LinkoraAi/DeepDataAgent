import { get, post, put, del, buildQuery } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 切片端点（后端 ChunkController，前缀 /api/v1/knowledge-base/chunks）。
 * <p>契约要点（对齐后端真实代码）：</p>
 * <ul>
 *   <li>列表页码分页（kbId 必填，documentId/sequence/keyword 可选，page 默认 1、size 默认 20 上限 100），
 *       按文档与序号升序；</li>
 *   <li>新增请求 sequence 被后端忽略（序号恒为该文档当前最大序号加一），来源恒打「人工新增」标；</li>
 *   <li>更新仅可改 chunkContent / tokens；</li>
 *   <li>单条删除仅允许 MANUAL 来源，命中 PARSED 后端 400（附引导文案）；批量删除混入 PARSED 整批 400；
 *       前端 MUST 先行拦截，不向 PARSED 混批发起请求；</li>
 *   <li>media 端点为鉴权图片流（inline），供 useAuthenticatedMedia 拼接。</li>
 * </ul>
 */

/** 统一前缀。 */
const BASE = '/api/v1/knowledge-base/chunks';

/** 切片来源（ChunkSource 枚举名：PARSED=解析产生 / MANUAL=人工新增；仅 MANUAL 可删除）。 */
export type ChunkSourceWire = 'PARSED' | 'MANUAL' | string;

/**
 * 切片线格式响应（ChunkResponse 同构）。
 * <p>originalItem 为后端二次编码的「JSON 字符串」（可为 null）。</p>
 */
export interface ChunkWire {
  /** 主键 */
  id: number;
  /** 所属知识库ID */
  kbId: number;
  /** 所属文档ID */
  documentId: number;
  /** 切片在文档内的序号 */
  sequence: number | null;
  /** token 数量 */
  tokens: number | null;
  /** 切片内容 */
  chunkContent: string;
  /** 多模态原始信息 JSON 字符串 */
  originalItem: string | null;
  /** 内容形态（ChunkContentType 枚举名） */
  chunkContentType: string | null;
  /** 来源文件名 */
  sourceFileName: string | null;
  /** 分块来源标识（PARSED / MANUAL） */
  sourceType: ChunkSourceWire;
  /** 多模态图片在线预览相对 URL（/api/v1/knowledge-base/chunks/{id}/media）；未关联图片为 null */
  mediaFile: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 切片列表查询参数（kbId 必填）。 */
export type ChunkListParams = {
  /** 所属知识库ID（必填） */
  kbId: number;
  /** 文档ID过滤 */
  documentId?: number;
  /** 切片序号过滤 */
  sequence?: number;
  /** 切片内容关键字 */
  keyword?: string;
  /** 页码（从 1 起） */
  page?: number;
  /** 每页条数（默认 20，上限 100） */
  size?: number;
};

/** 新增切片请求体（sequence 后端忽略，sourceType 恒 MANUAL）。 */
export interface ChunkCreatePayload {
  /** 所属知识库ID */
  kbId: number;
  /** 所属文档ID */
  documentId: number;
  /** 切片内容（必填） */
  chunkContent: string;
  /** token 数量（可选） */
  tokens?: number;
  /** 多模态原始信息 JSON（可选） */
  originalItem?: string;
  /** 内容形态（ChunkContentType 枚举名，为空默认 TEXT） */
  chunkContentType?: string;
  /** 来源文件名（可选） */
  sourceFileName?: string;
}

/** 更新切片请求体（仅可改内容与 token）。 */
export interface ChunkUpdatePayload {
  /** 切片内容（必填） */
  chunkContent: string;
  /** token 数量（可选） */
  tokens?: number;
}

/** 分页查询切片列表。 */
export function listChunks(params: ChunkListParams): Promise<PaginatedResp<ChunkWire>> {
  return get<PaginatedResp<ChunkWire>>(`${BASE}${buildQuery(params)}`);
}

/** 查询切片详情。 */
export function getChunk(id: number): Promise<ChunkWire> {
  return get<ChunkWire>(`${BASE}/${id}`);
}

/** 新增切片（人工来源，序号由后端分配）。 */
export function createChunk(payload: ChunkCreatePayload): Promise<ChunkWire> {
  return post<ChunkWire>(BASE, payload);
}

/** 更新切片内容与 token。 */
export function updateChunk(id: number, payload: ChunkUpdatePayload): Promise<ChunkWire> {
  return put<ChunkWire>(`${BASE}/${id}`, payload);
}

/** 删除单条切片（仅 MANUAL；命中 PARSED 后端 400 附引导文案）。 */
export function deleteChunk(id: number): Promise<void> {
  return del<void>(`${BASE}/${id}`);
}

/** 批量删除切片（服务端去重后分批；混入 PARSED 整批 400，前端 MUST 先拦截）。 */
export function batchDeleteChunks(ids: number[]): Promise<void> {
  return post<void>(`${BASE}/batch-delete`, { ids });
}

/** 切片媒体图片端点路径（inline 鉴权流，供 useAuthenticatedMedia 拼接）。 */
export function chunkMediaPath(id: number): string {
  return `${BASE}/${id}/media`;
}
