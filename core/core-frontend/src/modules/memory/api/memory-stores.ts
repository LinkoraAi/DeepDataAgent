/**
 * 记忆库接口模块（对齐后端 MemoryStoreController：/api/v1/cloud/memory-stores）。
 * <p>store 无 update 端点（仅 archive，幂等；归档后读可写拒 409）；entry 更新为
 * OCC 乐观并发（PATCH 携带 version，不匹配 409）；列表项与 redact 后详情省略
 * content（NON_NULL）；版本快照不可变，redact 抹除正文保留审计。
 * 被未删除 Session 引用的 store 删除返回 409。</p>
 */
import { fetchJson, type PaginatedResponse } from '@/shared/api/http';

/** 记忆库 DTO（对齐后端 MemoryStoreResponse，含统计字段）。 */
export interface MemoryStoreDto {
  storeId: string;
  name: string;
  description: string | null;
  status: string;
  entryCount: number;
  totalSize: number;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 记忆条目列表项（对齐后端 MemoryResponse，不含 content）。 */
export interface MemoryEntryDto {
  memoryId: string;
  storeId: string;
  path: string;
  version: number;
  size: number;
  contentSha256: string;
  metadata: Record<string, string> | null;
  createdAt: string;
  updatedAt: string;
}

/** 记忆条目详情（对齐后端 MemoryDetailResponse，content 可因 redact 缺失）。 */
export interface MemoryDetailDto extends MemoryEntryDto {
  content?: string | null;
}

/** 记忆版本快照（对齐后端 MemoryVersionResponse）。 */
export interface MemoryVersionDto {
  versionId: string;
  storeId: string;
  entryId: string;
  entryPath: string;
  version: number;
  action: string;
  content?: string | null;
  size: number | null;
  contentSha256: string | null;
  redacted: boolean;
  redactedAt: string | null;
  createdAt: string;
}

/** 创建记忆库请求（name ≤64、description ≤500）。 */
export interface CreateMemoryStorePayload {
  name: string;
  description?: string | null;
}

/** 创建记忆条目请求（path 相对无首斜杠 ≤512、content ≤100KB、metadata ≤16 对）。 */
export interface CreateMemoryEntryPayload {
  path: string;
  content: string;
  metadata?: Record<string, string> | null;
}

/** 更新记忆条目请求（OCC：version 必为正整数，不匹配 409；metadata 非空整体替换）。 */
export interface UpdateMemoryEntryPayload {
  content: string;
  version: number;
  metadata?: Record<string, string> | null;
}

const MEMORY_API_BASE = '/api/v1/cloud/memory-stores';

/** 分页查询记忆库列表（仅活跃，不含归档）。 */
export function listMemoryStores(page = 1, size = 20): Promise<PaginatedResponse<MemoryStoreDto>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return fetchJson<PaginatedResponse<MemoryStoreDto>>(`${MEMORY_API_BASE}?${query}`);
}

/** 创建记忆库。 */
export function createMemoryStore(payload: CreateMemoryStorePayload): Promise<MemoryStoreDto> {
  return fetchJson<MemoryStoreDto>(MEMORY_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 记忆库详情。 */
export function getMemoryStore(storeId: string): Promise<MemoryStoreDto> {
  return fetchJson<MemoryStoreDto>(`${MEMORY_API_BASE}/${encodeURIComponent(storeId)}`);
}

/** 归档记忆库（幂等返回现状）。 */
export function archiveMemoryStore(storeId: string): Promise<MemoryStoreDto> {
  return fetchJson<MemoryStoreDto>(`${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/archive`, {
    method: 'POST',
  });
}

/** 删除记忆库（连带条目与版本；被未删 Session 引用 409）。 */
export async function deleteMemoryStore(storeId: string): Promise<void> {
  await fetchJson<void>(`${MEMORY_API_BASE}/${encodeURIComponent(storeId)}`, { method: 'DELETE' });
}

/** 创建记忆条目。 */
export function createMemoryEntry(storeId: string, payload: CreateMemoryEntryPayload): Promise<MemoryDetailDto> {
  return fetchJson<MemoryDetailDto>(`${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 条目列表（仅元数据，无分页返回全量）。 */
export function listMemoryEntries(storeId: string): Promise<MemoryEntryDto[]> {
  return fetchJson<MemoryEntryDto[]>(`${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories`);
}

/** 条目详情（含 content）。 */
export function getMemoryEntry(storeId: string, memoryId: string): Promise<MemoryDetailDto> {
  return fetchJson<MemoryDetailDto>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories/${encodeURIComponent(memoryId)}`,
  );
}

/** 更新记忆条目（PATCH + OCC version，冲突 409）。 */
export function updateMemoryEntry(
  storeId: string,
  memoryId: string,
  payload: UpdateMemoryEntryPayload,
): Promise<MemoryDetailDto> {
  return fetchJson<MemoryDetailDto>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories/${encodeURIComponent(memoryId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  );
}

/** 删除记忆条目（ tombstone 软删，版本历史保留）。 */
export async function deleteMemoryEntry(storeId: string, memoryId: string): Promise<void> {
  await fetchJson<void>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories/${encodeURIComponent(memoryId)}`,
    { method: 'DELETE' },
  );
}

/** 条目版本历史列表。 */
export function listMemoryVersions(storeId: string, memoryId: string): Promise<MemoryVersionDto[]> {
  return fetchJson<MemoryVersionDto[]>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memories/${encodeURIComponent(memoryId)}/versions`,
  );
}

/** 版本快照详情。 */
export function getMemoryVersion(storeId: string, versionId: string): Promise<MemoryVersionDto> {
  return fetchJson<MemoryVersionDto>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memory-versions/${encodeURIComponent(versionId)}`,
  );
}

/** 遮蔽版本（不可逆抹除正文保留审计，幂等）。 */
export function redactMemoryVersion(storeId: string, versionId: string): Promise<MemoryVersionDto> {
  return fetchJson<MemoryVersionDto>(
    `${MEMORY_API_BASE}/${encodeURIComponent(storeId)}/memory-versions/${encodeURIComponent(versionId)}/redact`,
    { method: 'POST' },
  );
}
