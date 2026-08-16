/**
 * Agent 定义与版本管理接口模块（对齐后端 AgentController：/api/v1/agent/agents）。
 * <p>「配置即版本」：创建与发布版本共用同一请求体；发布时缺省字段视为清空。</p>
 */
import { fetchJson, type PaginatedResponse } from '@/shared/api/http';

/** Agent 定义 DTO（列表项：定义信息 + 最新发布号）。 */
export interface AgentDto {
  agentId: string;
  name: string;
  description: string | null;
  archived: boolean;
  archivedAt: string | null;
  latestVersion: number;
  createdAt: string;
  updatedAt: string;
}

/** Agent 版本 DTO（版本快照，含模型配置引用与技能挂载）。 */
export interface AgentVersionDto {
  versionId: string;
  agentId: string;
  versionNumber: number;
  name: string;
  description: string | null;
  system: string;
  modelProfileId: string;
  inferenceParams: string | null;
  skillIds: string | null;
  knowledgeBaseIds: string | null;
  dataSourceIds: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Agent 详情 DTO（定义信息 + 最新版本快照）。 */
export interface AgentDetailDto {
  agent: AgentDto;
  latestVersion: AgentVersionDto | null;
}

/** Agent 配置请求（创建 / 发布版本共用）。 */
export interface AgentConfigPayload {
  name: string;
  description?: string | null;
  system?: string;
  modelProfileId: string;
  inferenceParams?: string | null;
  skillIds?: string | null;
  knowledgeBaseIds?: string | null;
  dataSourceIds?: string | null;
}

const AGENTS_API_BASE = '/api/v1/agent/agents';

/** 创建 Agent（生成 v1）。 */
export function createAgent(payload: AgentConfigPayload): Promise<AgentDto> {
  return fetchJson<AgentDto>(AGENTS_API_BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 分页查询 Agent 列表。 */
export function listAgents(options?: {
  keyword?: string;
  includeArchived?: boolean;
  page?: number;
  size?: number;
}): Promise<PaginatedResponse<AgentDto>> {
  const query = new URLSearchParams({
    page: String(options?.page ?? 1),
    size: String(options?.size ?? 20),
  });
  if (options?.keyword) {
    query.set('keyword', options.keyword);
  }
  if (options?.includeArchived) {
    query.set('includeArchived', 'true');
  }
  return fetchJson<PaginatedResponse<AgentDto>>(`${AGENTS_API_BASE}?${query}`);
}

/** Agent 详情（含最新版本快照）。 */
export function getAgent(agentId: string): Promise<AgentDetailDto> {
  return fetchJson<AgentDetailDto>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}`);
}

/** 发布新版本（MAX+1）。 */
export function publishAgentVersion(agentId: string, payload: AgentConfigPayload): Promise<AgentVersionDto> {
  return fetchJson<AgentVersionDto>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/versions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 版本列表（倒序）。 */
export function listAgentVersions(agentId: string): Promise<AgentVersionDto[]> {
  return fetchJson<AgentVersionDto[]>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/versions`);
}

/** 归档 Agent（拒绝创建新会话，不可恢复）。 */
export async function archiveAgent(agentId: string): Promise<void> {
  await fetchJson<void>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}/archive`, { method: 'POST' });
}

/** 删除 Agent（级联删除全部版本）。 */
export async function deleteAgent(agentId: string): Promise<void> {
  await fetchJson<void>(`${AGENTS_API_BASE}/${encodeURIComponent(agentId)}`, { method: 'DELETE' });
}