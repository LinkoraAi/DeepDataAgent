import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { SessionListItem, Message, ChatMessage, AnalysisSnapshot } from '../types';
import * as sessionApi from '@/shared/api/sessionApi';

/**
 * 将后端 Message 转换为前端 ChatMessage
 */
function convertMessageToChatMessage(msg: Message): ChatMessage {
  let analysisState: AnalysisSnapshot | undefined;

  if (msg.role === 'assistant' && msg.metadata) {
    try {
      analysisState = JSON.parse(msg.metadata) as AnalysisSnapshot;
    } catch {
      // metadata 无法解析，降级为仅显示文本
      analysisState = undefined;
    }
  }

  return {
    id: String(msg.id),
    role: msg.role === 'assistant' ? 'agent' : 'user',
    content: msg.content,
    timestamp: new Date(msg.createdAt).getTime(),
    analysisState,
  };
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<SessionListItem[]>([]);
  const currentSessionId = ref<string | null>(null);
  const messages = ref<Message[]>([]);
  const chatMessages = ref<ChatMessage[]>([]);
  const loading = ref(false);

  /**
   * Load sessions
   */
  async function loadSessions() {
    loading.value = true;
    try {
      sessions.value = await sessionApi.listSessions();
    } catch (err) {
      console.error('Failed to load sessions:', err);
    } finally {
      loading.value = false;
    }
  }

  /**
   * Create session
   */
  async function createSession(datasourceId: number, modelConfigId: number) {
    const session = await sessionApi.createSession(datasourceId, modelConfigId);
    currentSessionId.value = session.id;
    await loadSessions();
    return session;
  }

  /**
   * Switch session
   */
  async function switchSession(sessionId: string) {
    currentSessionId.value = sessionId;
    await loadMessages(sessionId);
  }

  /**
   * Load messages from backend and convert to ChatMessage
   */
  async function loadMessages(sessionId: string, limit: number = 50, offset: number = 0) {
    try {
      messages.value = await sessionApi.getMessages(sessionId, limit, offset);
      chatMessages.value = messages.value.map(convertMessageToChatMessage);
    } catch (err) {
      console.error('Failed to load messages:', err);
    }
  }

  /**
   * Add a local ChatMessage (from SSE flow or user input)
   */
  function addLocalChatMessage(msg: ChatMessage) {
    chatMessages.value.push(msg);
  }

  /**
   * Close session
   */
  async function closeSession(sessionId: string) {
    await sessionApi.closeSession(sessionId);
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null;
      messages.value = [];
      chatMessages.value = [];
    }
    await loadSessions();
  }

  /**
   * Clear current session
   */
  function clearCurrentSession() {
    currentSessionId.value = null;
    messages.value = [];
    chatMessages.value = [];
  }

  return {
    sessions,
    currentSessionId,
    messages,
    chatMessages,
    loading,
    loadSessions,
    createSession,
    switchSession,
    loadMessages,
    addLocalChatMessage,
    closeSession,
    clearCurrentSession,
  };
});
