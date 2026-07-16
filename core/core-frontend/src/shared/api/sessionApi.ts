import { post } from './http';
import type { Session, SessionListItem, Message } from '@/modules/agent/types';

/**
 * Create session
 */
export async function createSession(datasourceId: number, modelConfigId: number): Promise<Session> {
  return post<Session>('/agent/sessions/create', { datasourceId, modelConfigId });
}

/**
 * List sessions
 */
export async function listSessions(): Promise<SessionListItem[]> {
  return post<SessionListItem[]>('/agent/sessions/list', {});
}

/**
 * Get session detail
 */
export async function getSession(sessionId: string): Promise<Session> {
  return post<Session>('/agent/sessions/get', { sessionId });
}

/**
 * Close session
 */
export async function closeSession(sessionId: string): Promise<void> {
  return post<void>('/agent/sessions/close', { sessionId });
}

/**
 * Get session messages
 */
export async function getMessages(sessionId: string, limit: number, offset: number): Promise<Message[]> {
  return post<Message[]>('/agent/sessions/messages', { sessionId, limit, offset });
}
