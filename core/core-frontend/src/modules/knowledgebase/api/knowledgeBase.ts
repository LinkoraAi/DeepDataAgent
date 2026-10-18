import { get, post, put, buildQuery, requestEnvelope } from '@/shared/api/http';
import type { PaginatedResp } from '@/shared/types/api';

/**
 * 知识库端点（后端 KnowledgeBaseController，前缀 /api/v1/knowledge-base/knowledge-bases）。
 * <p>契约要点（对齐后端真实行为）：</p>
 * <ul>
 *   <li>响应为 camelCase（KnowledgeBaseResponse）；7 个配置字段线上传输为「JSON 字符串」（二次编码），
 *       前端在 kbDraft.ts 内做 JSON.parse / JSON.stringify 装配；</li>
 *   <li>删除端点返回 HTTP 200 + body code="202"（异步受理），故 deleteKnowledgeBase 经
 *       requestEnvelope 感知 code，回 {accepted} 供上层轮询；</li>
 *   <li>检索策略 / 实体类型走专用 PUT 端点，请求体仅承载对应 JSON 字符串字段。</li>
 * </ul>
 */

/** 统一前缀（版本 v1 由 http 基座按后端约定拼接） */
const BASE = '/api/v1/knowledge-base/knowledge-bases';

/** 删除受理响应业务码（异步受理，HTTP 外层仍为 200） */
const ACCEPTED_CODE = '202';

/**
 * 知识库线格式响应（KnowledgeBaseResponse 同构）。
 * <p>配置字段均为 JSON 字符串（可能为 null）；生命周期三态与删除失败留痕原样回显。</p>
 */
export interface KnowledgeBaseWire {
  /** 主键 */
  id: number;
  /** 名称 */
  name: string;
  /** 描述 */
  description: string | null;
  /** 语言（十一全名，默认 Chinese） */
  language: string | null;
  /** 生命周期状态：ACTIVE / DELETING / DELETE_FAILED */
  lifecycleStatus: string | null;
  /** 删除失败留痕（仅 DELETE_FAILED 携带） */
  errorMessage: string | null;
  /** RAG 引擎配置 JSON 字符串（含 parseEngine 与 chunkStrategy） */
  ragEngineConfig: string | null;
  /** 去重策略 JSON 字符串 */
  dedupPolicy: string | null;
  /** 检索策略 JSON 字符串 */
  retrievalStrategy: string | null;
  /** 嵌入模型配置 JSON 字符串 */
  embeddingConfig: string | null;
  /** 多模态模型配置 JSON 字符串 */
  multiModelConfig: string | null;
  /** 实体类型自定义配置 JSON 字符串 */
  entityTypeConfig: string | null;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 知识库总览统计（KnowledgeBaseStatsResponse 同构） */
export interface KnowledgeBaseStatsWire {
  /** 知识库总数（仅统计 ACTIVE） */
  totalKnowledgeBases: number;
  /** 文档总数 */
  totalDocuments: number;
  /** 切片总数 */
  totalChunks: number;
}

/** 创建请求（CreateKnowledgeBaseRequest 同构；配置字段为 JSON 字符串） */
export interface KnowledgeBaseCreatePayload {
  name: string;
  description?: string;
  language?: string;
  ragEngineConfig: string;
  dedupPolicy: string;
  embeddingConfig: string;
  multiModelConfig: string;
  entityTypeConfig?: string;
}

/**
 * 更新请求（UpdateKnowledgeBaseRequest 同构）。
 * <p>后端「空白=不覆盖」语义；embeddingConfig 创建后锁定、后端静默忽略，故更新载荷不携带嵌入模型配置。</p>
 */
export interface KnowledgeBaseUpdatePayload {
  name?: string;
  description?: string;
  language?: string;
  ragEngineConfig?: string;
  dedupPolicy?: string;
  multiModelConfig?: string;
  entityTypeConfig?: string;
}

/** 列表查询参数（type 别名：可隐式适配 buildQuery 的索引签名 Record） */
export type KnowledgeBaseListParams = {
  /** 名称/描述关键字 */
  keyword?: string;
  /** 页码（从 1 起） */
  page?: number;
  /** 每页条数（后端上限 100） */
  size?: number;
  /** 排序字段：name / createdAt / updatedAt */
  sortBy?: string;
  /** 排序方向：asc / desc */
  sortOrder?: string;
};

/** 分页查询知识库列表 */
export function listKnowledgeBases(params: KnowledgeBaseListParams): Promise<PaginatedResp<KnowledgeBaseWire>> {
  return get<PaginatedResp<KnowledgeBaseWire>>(`${BASE}${buildQuery(params)}`);
}

/** 查询知识库详情 */
export function getKnowledgeBase(id: number): Promise<KnowledgeBaseWire> {
  return get<KnowledgeBaseWire>(`${BASE}/${id}`);
}

/** 创建知识库 */
export function createKnowledgeBase(payload: KnowledgeBaseCreatePayload): Promise<KnowledgeBaseWire> {
  return post<KnowledgeBaseWire>(BASE, payload);
}

/** 更新知识库基础信息与配置（嵌入模型配置后端静默忽略） */
export function updateKnowledgeBase(id: number, payload: KnowledgeBaseUpdatePayload): Promise<KnowledgeBaseWire> {
  return put<KnowledgeBaseWire>(`${BASE}/${id}`, payload);
}

/**
 * 删除知识库（异步受理）。
 * <p>返回 HTTP 200 + body code="202" 视为受理成功；调用方据 accepted 进入删除中态并轮询收敛。</p>
 * @returns accepted 为 true 表示删除已受理
 */
export async function deleteKnowledgeBase(id: number): Promise<{ accepted: boolean }> {
  const envelope = await requestEnvelope<KnowledgeBaseWire>(`${BASE}/${id}`, { method: 'DELETE' });
  return { accepted: envelope.code === ACCEPTED_CODE };
}

/** 查询知识库总览统计 */
export function getStats(): Promise<KnowledgeBaseStatsWire> {
  return get<KnowledgeBaseStatsWire>(`${BASE}/stats`);
}

/**
 * 更新检索策略配置（专用端点）。
 * @param retrievalStrategy 检索策略 JSON 字符串
 */
export function updateRetrievalConfig(id: number, retrievalStrategy: string): Promise<KnowledgeBaseWire> {
  return put<KnowledgeBaseWire>(`${BASE}/${id}/retrieval-config`, { retrievalStrategy });
}

/**
 * 更新实体类型配置（专用端点）。
 * @param entityTypeConfig 实体类型配置 JSON 字符串
 */
export function updateEntityTypeConfig(id: number, entityTypeConfig: string): Promise<KnowledgeBaseWire> {
  return put<KnowledgeBaseWire>(`${BASE}/${id}/entity-type-config`, { entityTypeConfig });
}
