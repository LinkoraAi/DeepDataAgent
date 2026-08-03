import { defineStore } from 'pinia';
import { ref } from 'vue';
import { createSession as apiCreateSession, listSessions as apiListSessions, getMessages as apiGetMessages, closeSession as apiCloseSession } from '@/shared/api/sessionApi';
import type { Message, ChatMessage, SessionListItem, AnalysisSnapshot, SearchResultItem } from '../types';
import { useSessionStateManager } from '../composables/useSessionStateManager';
import { useModelStore } from '@/modules/model/stores/model';
import { parseQueryDataArray } from '@/shared/utils/queryData';

/**
 * 将后端消息列表转换为前端 ChatMessage 列表
 * <p>单表 JSON 持久化后，后端返回同一会话内所有轮次的扁平消息列表。
 * 此处按 dialogueId 分组，每个轮次（对话）合并为一条用户消息 + 一条助手消息，
 * 助手消息从该轮次的消息序列重建 analysisState（历史回放）。</p>
 *
 * @param messages 后端返回的消息列表
 * @returns 转换后的 ChatMessage 列表
 */
function convertMessagesToChatMessages(messages: Message[]): ChatMessage[] {
  // 按 dialogueId 分组，同一轮次的多条消息合并处理
  const groups = new Map<number, Message[]>();
  for (const msg of messages) {
    const key = msg.dialogueId ?? 0;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key)!.push(msg);
  }

  const result: ChatMessage[] = [];
  for (const group of groups.values()) {
    // 按 seq(id) 排序，保证思考→工具调用→结果的时序正确
    group.sort((a, b) => a.id - b.id);

    const userMsg = group.find(m => m.role === 'user');
    if (userMsg) {
      result.push({
        id: `msg-${userMsg.id}-user`,
        role: 'user',
        content: userMsg.content,
        timestamp: new Date(userMsg.createdAt).getTime(),
      });
    }

    const analysisState = buildAnalysisStateFromMessages(group);
    const assistantMsg = group.find(m => m.role === 'assistant');
    if (assistantMsg || analysisState) {
      const anchor = assistantMsg ?? group[group.length - 1];
      result.push({
        id: `msg-${anchor.id}-agent`,
        role: 'agent',
        content: assistantMsg?.content ?? '',
        timestamp: new Date(anchor.createdAt).getTime(),
        analysisState,
      });
    }
  }
  return result;
}

/**
 * 统计消息列表中的轮次数（按 dialogueId 去重）
 * <p>用于前端推导分页是否还有更多：返回轮次数 < 请求轮次数即没有更多。</p>
 *
 * @param messages 后端返回的消息列表
 * @returns 轮次数
 */
function countRounds(messages: Message[]): number {
  return new Set(messages.map(m => m.dialogueId ?? 0)).size;
}

/**
 * 从一组消息（同一 dialogueId）中重建分析状态
 * <p>后端不再持久化 AnalysisSnapshot 快照，历史回放需从消息序列推导。
 * 该方法遍历该轮次内的所有消息，提取思考/工具调用/工具结果/助手报告，
 * 重建与流式 createSnapshot() 相同形状的 AnalysisSnapshot。</p>
 *
 * @param messages 同一 dialogueId 的一组消息（无需预先排序）
 * @returns 重建的分析状态；若消息为空则返回 undefined
 */
function buildAnalysisStateFromMessages(messages: Message[]): AnalysisSnapshot | undefined {
  if (!messages || messages.length === 0) {
    return undefined;
  }
  const orderMsgs = [...messages].sort((a, b) => a.id - b.id);

  const thinkingSteps: Array<{ content: string; timestamp: number }> = [];
  const toolCalls: Array<{
    name: string;
    status: 'running' | 'success' | 'error';
    startTime: number;
    endTime?: number;
    input?: string;
    result?: string;
    timestamp: number;
  }> = [];
  // 记录上一个 TOOL_CALL 的工具名，用于 TOOL_RESULT 关联（后端 TOOL_RESULT 消息不再携带 toolCalls 字段）
  let lastToolCallName: string | null = null;
  let currentSQL: string | null = null;
  let queryData: Record<string, any>[] = [];
  let chartConfig: any = null;
  let chartType: string | null = null;
  let searchResults: SearchResultItem[] | null = null;
  let analysisReport: string | null = null;
  let errorMessage: string | null = null;

  for (const msg of orderMsgs) {
    const ts = new Date(msg.createdAt).getTime();

    if (msg.role === 'thinking') {
      if (msg.content) {
        thinkingSteps.push({ content: msg.content, timestamp: ts });
      }
    } else if (msg.role === 'tool') {
      if (msg.toolCalls) {
        // TOOL_CALL 消息：记录工具调用（含入参），并保存工具名供后续 TOOL_RESULT 使用
        lastToolCallName = msg.toolCalls;
        toolCalls.push({
          name: msg.toolCalls,
          status: 'running',
          startTime: ts,
          input: msg.content || undefined,
          timestamp: ts,
        });
      } else if (msg.toolResult) {
        // TOOL_RESULT 消息：关联到最后一个未完成且同名的工具调用
        const target = [...toolCalls].reverse().find(t => !t.result && t.result !== '');
        if (target) {
          target.status = 'success';
          target.result = msg.toolResult;
          target.endTime = ts;
        }
        // 使用最后一个 TOOL_CALL 记录的工具名
        applyToolResultToState(lastToolCallName ?? '', msg.toolResult, {
          setSQL: (v) => { currentSQL = v; },
          setQueryData: (v) => { queryData = v; },
          setChart: (type, option) => { chartType = type; chartConfig = option; },
          setSearchResults: (v) => { searchResults = v; },
          setAnalysisReport: (v) => { analysisReport = v; },
        });
        lastToolCallName = null;
      }
    } else if (msg.role === 'assistant') {
      if (msg.content && !analysisReport) {
        analysisReport = msg.content;
      }
    }
  }

  // 重建 ReAct 轮次
  const rounds = rebuildRoundsFromFlatData(thinkingSteps, toolCalls);

  return {
    rounds,
    isTimelineExpanded: true,
    currentSQL,
    queryData,
    chartConfig,
    chartType,
    analysisReport,
    report: null,
    searchResults,
    isEmptyResult: queryData.length === 0,
    errorMessage,
    analysisStartTime: null,
    analysisEndTime: null,
    suggestions: [],
  };
}

/**
 * 根据 TOOL_RESULT 消息的工具名，将结果提取到分析状态对应字段
 * <p>与 useSessionStateManager.handleToolResult 的提取逻辑保持一致。</p>
 */
function applyToolResultToState(
  toolName: string,
  resultContent: string,
  setters: {
    setSQL: (v: string) => void;
    setQueryData: (v: any[]) => void;
    setChart: (type: string, option: any) => void;
    setSearchResults: (v: SearchResultItem[]) => void;
    setAnalysisReport: (v: string) => void;
  },
) {
  switch (toolName) {
    case 'generate_sql':
      setters.setSQL(stripCodeFences(resultContent));
      break;
    case 'execute_sql':
    case 'execute_api_query': {
      // 工具结果可能是纯 JSON 数组、带 "查询返回 N 行数据：" 前缀的文本，
      // 或经 AgentScope 二次序列化的 JSON 字符串，统一交由 parseQueryDataArray 处理
      const data = parseQueryDataArray(resultContent);
      if (data) {
        setters.setQueryData(data);
      }
      break;
    }
    case 'generate_chart': {
      const chartContent = tryUnescapeJson(resultContent);
      if (chartContent && chartContent.trim()) {
        try {
          const chartData = JSON.parse(chartContent);
          const chartType = extractChartType(resultContent);
          const chartOptionStr = typeof chartData === 'string' ? chartData : JSON.stringify(chartData);
          setters.setChart(chartType || 'TABLE', chartOptionStr);
        } catch (err) {
          console.error('[session] Failed to parse chart:', err);
        }
      }
      break;
    }
    case 'web_search': {
      const results = parseWebSearchResults(resultContent);
      if (results.length > 0) {
        setters.setSearchResults(results);
      }
      break;
    }
    case 'generate_analysis': {
      const analysisContent = tryUnescapeJson(resultContent);
      if (analysisContent) {
        setters.setAnalysisReport(analysisContent);
      }
      break;
    }
    default:
      break;
  }
}

/**
 * 从 web_search 工具结果解析搜索结果
 */
function parseWebSearchResults(payload: string): SearchResultItem[] {
  const results: SearchResultItem[] = [];
  if (!payload) return results;
  try {
    const data = JSON.parse(payload);
    if (data.results && Array.isArray(data.results)) {
      for (const item of data.results) {
        if (item.title || item.url) {
          results.push({
            title: item.title || '',
            url: item.url || '',
            snippet: item.snippet || item.content || '',
          });
        }
      }
    }
  } catch (err) {
    console.error('[session] Failed to parse web search results:', err);
  }
  return results;
}

/**
 * 移除代码块标记
 */
function stripCodeFences(text: string): string {
  return text.replace(/```[\w]*\n?/g, '').trim();
}

/**
 * 尝试解析转义后的 JSON 字符串
 */
function tryUnescapeJson(text: string): string | null {
  if (!text) return null;
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed === 'string') {
      return parsed;
    }
    return text;
  } catch {
    return text;
  }
}

/**
 * 从文本中提取图表类型
 */
function extractChartType(text: string): string | null {
  if (!text) return null;
  try {
    const parsed = JSON.parse(text);
    if (parsed.chartType) return parsed.chartType;
  } catch {
    // ignore
  }
  return null;
}

/**
 * 从扁平的 thinkingSteps + toolCalls 重建 ReAct 轮次
 * <p>用于兼容旧格式的历史消息。按时间戳排序后，
 * 每遇到一个 thinkingStep 就开启新一轮，工具调用归入当前轮。</p>
 *
 * @param thinkingSteps 带时间戳的思考步骤数组
 * @param rawToolCalls 扁平的工具调用数组
 * @returns 重建的 ReAct 轮次数组
 */
function rebuildRoundsFromFlatData(thinkingSteps: Array<{ content: string; timestamp: number }>, rawToolCalls: any[]): any[] {
  // 合并所有事件并按时间戳排序
  const allEvents: any[] = [];
  
  for (const step of thinkingSteps) {
    allEvents.push({
      type: 'thinking',
      content: step.content,
      timestamp: step.timestamp,
    });
  }
  
  for (const tc of rawToolCalls) {
    allEvents.push({
      type: 'tool_call',
      toolName: tc.name || 'unknown',
      status: tc.status || 'success',
      startTime: tc.startTime || Date.now(),
      endTime: tc.endTime,
      result: tc.result,
      timestamp: tc.startTime || Date.now(),
    });
  }
  
  allEvents.sort((a, b) => a.timestamp - b.timestamp);
  
  // 按 thinking 事件分组
  const rounds: any[] = [];
  let currentRound: any = null;
  
  for (const event of allEvents) {
    if (event.type === 'thinking') {
      // 开启新轮次
      currentRound = {
        id: `round-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        startTime: event.timestamp,
        thinking: {
          id: `thinking-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          timestamp: event.timestamp,
          type: 'thinking',
          content: event.content,
          isStreaming: false,
        },
        toolCalls: [],
        isActive: false,
        isCollapsed: true,
      };
      rounds.push(currentRound);
    } else if (event.type === 'tool_call' && currentRound) {
      // 工具调用归入当前轮次
      currentRound.toolCalls.push({
        id: `tool-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        timestamp: event.timestamp,
        type: 'tool_call',
        toolName: event.toolName,
        status: event.status,
        input: event.input,
        result: event.result,
        startTime: event.startTime,
        endTime: event.endTime,
      });
    }
  }
  
  return rounds;
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<SessionListItem[]>([]);
  const currentSessionId = ref<string | null>(null);
  const messages = ref<Message[]>([]);
  const chatMessages = ref<ChatMessage[]>([]);
  // 会话消息缓存：存储每个会话的消息列表，避免会话切换时丢失
  const sessionMessagesMap = ref<Map<string, ChatMessage[]>>(new Map());
  // 会话上次问题缓存：存储每个会话的上次分析问题，用于重试功能
  const sessionLastQuestionMap = ref<Map<string, string>>(new Map());
  const loading = ref(false);
  const hasMore = ref(true);
  const isLoadingMore = ref(false);
  const currentPage = ref(0);
  const PAGE_SIZE = 20;
  /** 消息分页每页轮次数 */
  const ROUND_PAGE_SIZE = 5;
  // 会话消息分页状态：按轮次游标分页，支持向上滚动加载更早轮次
  const hasMoreMessages = ref(true);
  const isLoadingOlder = ref(false);
  // 会话最早已加载轮次 id 缓存（切换会话后恢复向上加载游标）
  const sessionEarliestDialogueIdMap = ref<Map<string, number>>(new Map());

  /**
   * Load sessions
   * <p>只负责加载会话列表和设置 currentSessionId，不加载消息。
   * 消息加载由调用方（ChatPanel.onMounted、ChatPanel watch）负责。</p>
   */
  async function loadSessions() {
    loading.value = true;
    hasMore.value = true;
    currentPage.value = 0;
    try {
      sessions.value = await apiListSessions();

      // 自动恢复：如果当前没有选中会话，且有会话列表，自动选择第一个
      if (currentSessionId.value === null && sessions.value.length > 0) {
        const firstSession = sessions.value[0];
        currentSessionId.value = firstSession.id;
      }
    } catch (err) {
      console.error('Failed to load sessions:', err);
    } finally {
      loading.value = false;
    }
  }

  /**
   * Load more sessions for infinite scroll
   * <p>请求下一页并追加到现有列表底部。</p>
   */
  async function loadMore() {
    if (isLoadingMore.value || !hasMore.value) {
      return;
    }

    isLoadingMore.value = true;
    try {
      const nextPage = currentPage.value + 1;
      const nextSessions = await apiListSessions(PAGE_SIZE, nextPage * PAGE_SIZE);

      if (nextSessions.length > 0) {
        sessions.value.push(...nextSessions);
        currentPage.value = nextPage;
      }

      if (nextSessions.length < PAGE_SIZE) {
        hasMore.value = false;
      }
    } catch (err) {
      console.error('Failed to load more sessions:', err);
    } finally {
      isLoadingMore.value = false;
    }
  }

  /**
   * Create session
   */
  async function createSession(datasourceId: number, modelConfigId: number, userQuestion?: string) {
    const session = await apiCreateSession(datasourceId, modelConfigId, userQuestion);
    currentSessionId.value = session.id;
    await loadSessions();
    return session;
  }

  /**
   * Switch session
   * <p>切换会话时保存当前会话的分析状态快照，恢复目标会话的分析状态。
   * 同时同步模型选择，确保后续分析使用该会话绑定的模型配置。</p>
   * <p>注意：不在此处加载消息，由 ChatPanel.vue 的 watch 统一处理重连或加载逻辑</p>
   */
  function switchSession(sessionId: string) {
    // 点击的是同一会话，无需任何操作
    if (currentSessionId.value === sessionId) {
      console.debug('[session] switchSession: same session, early return', sessionId);
      return;
    }

    console.debug('[session] switchSession: switching', { from: currentSessionId.value, to: sessionId });

    // 使用 SessionStateManager 保存当前会话状态，恢复目标会话状态
    const sessionStateManager = useSessionStateManager();

    // 切换前保存当前会话的分析状态到 Map
    if (currentSessionId.value) {
      sessionStateManager.saveState(currentSessionId.value);
    }

    // 切换前先保存当前会话的消息到缓存（如果存在）
    if (currentSessionId.value && chatMessages.value.length > 0) {
      sessionMessagesMap.value.set(currentSessionId.value, [...chatMessages.value]);
      console.debug('[session] switchSession: saved messages to cache', {
        sessionId: currentSessionId.value,
        count: chatMessages.value.length
      });
    }

    // 切换会话
    currentSessionId.value = sessionId;

    // 恢复目标会话的分析状态到 analysisStore
    sessionStateManager.restoreState(sessionId);

    // 重置消息分页状态：消息列表与游标由 loadMessages/loadOlderMessages 按会话重建
    messages.value = [];
    hasMoreMessages.value = true;
    isLoadingOlder.value = false;

    // 从缓存恢复目标会话的消息，如果没有缓存则清空
    const cachedMessages = sessionMessagesMap.value.get(sessionId);
    if (cachedMessages) {
      chatMessages.value = [...cachedMessages];
      console.debug('[session] switchSession: restored messages from cache', {
        sessionId,
        count: cachedMessages.length
      });
    } else {
      chatMessages.value = [];
      console.debug('[session] switchSession: no cache, cleared messages', { sessionId });
    }

    // 同步模型选择：切换会话时，根据会话绑定的 modelConfigId 更新全局选择
    const session = sessions.value.find(s => s.id === sessionId);
    if (session && typeof session.modelConfigId === 'number') {
      const modelStore = useModelStore();
      modelStore.setSelectedConfig(session.modelConfigId);
    }
  }

  /**
   * Load messages from backend and convert to ChatMessage（按轮次分页，最新优先）
   * <p>初始加载最新 ROUND_PAGE_SIZE 轮，每轮消息全量返回，保证轮次完整；
   * 加载后保存到 sessionMessagesMap 缓存，并根据返回轮次数推导是否还有更早轮次。</p>
   * <p>注意：若后端返回空（如消息尚未持久化），保留已有的 chatMessages，
   * 避免覆盖本地添加的用户消息（如 submitQuestion 中在创建会话前添加的本地消息）。</p>
   */
  async function loadMessages(sessionId: string) {
    try {
      messages.value = await apiGetMessages(sessionId, ROUND_PAGE_SIZE);
      hasMoreMessages.value = countRounds(messages.value) >= ROUND_PAGE_SIZE;
      const newMessages = convertMessagesToChatMessages(messages.value);
      // 仅当后端有消息时才替换，否则保留本地已有消息（如刚提交但尚未持久化的用户消息）
      if (newMessages.length > 0) {
        chatMessages.value = newMessages;
        sessionMessagesMap.value.set(sessionId, [...newMessages]);
        const earliest = messages.value[0]?.dialogueId;
        if (earliest !== undefined) {
          sessionEarliestDialogueIdMap.value.set(sessionId, earliest);
        }
      } else {
        hasMoreMessages.value = false;
      }
    } catch (err) {
      console.error('Failed to load messages:', err);
    }
  }

  /**
   * Load older rounds（向上滚动分页）
   * <p>以当前已加载的最早轮次 id 为游标请求更早轮次，结果转换后前置插入
   * chatMessages 与 sessionMessagesMap 缓存，保持时间正序。</p>
   */
  async function loadOlderMessages() {
    if (isLoadingOlder.value || !hasMoreMessages.value || currentSessionId.value === null) {
      return;
    }
    const sessionId = currentSessionId.value;
    const earliestDialogueId =
      messages.value[0]?.dialogueId ?? sessionEarliestDialogueIdMap.value.get(sessionId);
    if (earliestDialogueId === undefined) {
      hasMoreMessages.value = false;
      return;
    }
    isLoadingOlder.value = true;
    try {
      const older = await apiGetMessages(sessionId, ROUND_PAGE_SIZE, earliestDialogueId);
      if (older.length > 0) {
        // 前置插入消息列表（保持升序），并更新游标
        messages.value = [...older, ...messages.value];
        const oldest = messages.value[0]?.dialogueId;
        if (oldest !== undefined) {
          sessionEarliestDialogueIdMap.value.set(sessionId, oldest);
        }
        const olderChatMessages = convertMessagesToChatMessages(older);
        if (olderChatMessages.length > 0) {
          chatMessages.value = [...olderChatMessages, ...chatMessages.value];
          sessionMessagesMap.value.set(sessionId, [...chatMessages.value]);
        }
      }
      hasMoreMessages.value = countRounds(older) >= ROUND_PAGE_SIZE;
    } catch (err) {
      console.error('Failed to load older messages:', err);
    } finally {
      isLoadingOlder.value = false;
    }
  }

  /**
   * Add a local ChatMessage (from SSE flow or user input)
   */
  function addLocalChatMessage(msg: ChatMessage) {
    chatMessages.value.push(msg);
  }

  /**
   * Add a ChatMessage to a specific session's cache
   * <p>用于 SSE 回调中，将消息写入指定会话的缓存，避免写入错误的会话</p>
   * @param sessionId 目标会话 ID
   * @param msg 要添加的消息
   */
  function addMessageToSession(sessionId: string, msg: ChatMessage) {
    // 添加到缓存
    const cachedMessages = sessionMessagesMap.value.get(sessionId) || [];
    cachedMessages.push(msg);
    sessionMessagesMap.value.set(sessionId, cachedMessages);
    
    // 如果目标是当前会话，同时更新 chatMessages
    if (currentSessionId.value === sessionId) {
      chatMessages.value.push(msg);
    }
  }

  /**
   * Close session
   * <p>仅做前端本地关闭：调用后端删除接口后，直接从当前会话列表移除该项，
   * 不再重拉 /agent/sessions/list，避免多余请求和消息重载。</p>
   * <p>如果会话分析已完成，清理 SessionStateManager 中保存的状态。</p>
   */
  async function closeSession(sessionId: string) {
    await apiCloseSession(sessionId);

    // 清理 SessionStateManager 中该会话的状态
    const sessionStateManager = useSessionStateManager();
    sessionStateManager.cleanupState(sessionId);

    const isCurrentSession = currentSessionId.value === sessionId;
    sessions.value = sessions.value.filter(session => session.id !== sessionId);

    if (isCurrentSession) {
      if (sessions.value.length > 0) {
        currentSessionId.value = sessions.value[0].id;
      } else {
        currentSessionId.value = null;
      }
      messages.value = [];
      chatMessages.value = [];
    }
  }

  /**
   * Clear current session
   */
  function clearCurrentSession() {
    console.debug('[session] clearCurrentSession called');
    currentSessionId.value = null;
    messages.value = [];
    chatMessages.value = [];
  }

  /**
   * 静默更新当前会话标题
   * <p>收到后端 title_update 事件后调用，直接替换当前会话对象的 title 属性，
   * 不重新拉取会话列表，避免额外请求。</p>
   *
   * @param title 新标题
   */
  function updateSessionTitle(title: string) {
    if (!title || currentSessionId.value === null) {
      return;
    }
    const session = sessions.value.find(s => s.id === currentSessionId.value);
    if (session) {
      session.title = title;
    }
  }

  /**
   * 设置会话的上次分析问题
   * <p>用于重试功能，在提交分析时调用，将问题存储到对应会话的缓存中。</p>
   *
   * @param sessionId 会话 ID
   * @param question 用户问题
   */
  function setSessionLastQuestion(sessionId: string, question: string) {
    sessionLastQuestionMap.value.set(sessionId, question);
  }

  /**
   * 获取会话的上次分析问题
   * <p>用于重试功能，点击重新生成时调用，从缓存中获取上次的问题。</p>
   *
   * @param sessionId 会话 ID
   * @returns 上次的问题，如果不存在返回空字符串
   */
  function getSessionLastQuestion(sessionId: string): string {
    return sessionLastQuestionMap.value.get(sessionId) || '';
  }

  return {
    sessions,
    currentSessionId,
    messages,
    chatMessages,
    sessionMessagesMap,
    loading,
    hasMore,
    isLoadingMore,
    currentPage,
    hasMoreMessages,
    isLoadingOlder,
    loadSessions,
    loadMore,
    createSession,
    switchSession,
    loadMessages,
    loadOlderMessages,
    addLocalChatMessage,
    addMessageToSession,
    closeSession,
    clearCurrentSession,
    updateSessionTitle,
    setSessionLastQuestion,
    getSessionLastQuestion,
  };
});
