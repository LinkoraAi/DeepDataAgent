import { post } from './http';
import type { Session, SessionListItem, Message } from '@/modules/agent/types';

/**
 * Create session
 */
export async function createSession(datasourceId: number, modelConfigId: number, text?: string): Promise<Session> {
  return post<Session>('/api/agent/sessions/create', { datasourceId, modelConfigId, text });
}

/**
 * List sessions
 */
export async function listSessions(limit?: number, offset?: number): Promise<SessionListItem[]> {
  const params = limit !== undefined ? { limit, offset } : {};
  return post<SessionListItem[]>('/api/agent/sessions/list', params);
}

/**
 * Get session detail
 */
export async function getSession(sessionId: string): Promise<Session> {
  return post<Session>('/api/agent/sessions/get', { sessionId });
}

/**
 * Close session
 */
export async function closeSession(sessionId: string): Promise<void> {
  return post<void>('/api/agent/sessions/close', { sessionId });
}

/**
 * Get session messages（按轮次游标分页，最新优先）
 * <p>limit 为轮次数（可选，默认 5）；beforeDialogueId 为轮次游标（可选，
 * null 表示取最新轮次，非空表示取 id 更小的更早轮次）。</p>
 */
export async function getMessages(sessionId: string, limit?: number, beforeDialogueId?: number | null): Promise<Message[]> {
  return post<Message[]>('/api/agent/sessions/messages', { sessionId, limit, beforeDialogueId });
}