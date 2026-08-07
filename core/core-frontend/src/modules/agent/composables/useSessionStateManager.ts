/**
 * 按会话状态管理器
 * <p>管理每个会话的独立 AnalysisState。使用 Map<sessionId, AnalysisState> 维护，
 * 切换会话时保存/恢复状态，后台会话持续更新不触发渲染。</p>
 */
import { ref, readonly } from 'vue';
import { useAnalysisStore } from '../stores/analysis';
import { parseQueryDataArray } from '@/shared/utils/queryData';
import type { AnalysisState, AnalysisSnapshot, AgentEvent, SearchResultItem, ToolCallTimelineItem } from '../types';

/**
 * 单例实例持有者
 */
let singletonInstance: ReturnType<typeof createSessionStateManager> | null = null;

/**
 * 获取会话状态管理器单例
 */
export function useSessionStateManager() {
  if (!singletonInstance) {
    singletonInstance = createSessionStateManager();
  }
  return singletonInstance;
}

/**
 * 创建会话状态管理器实例
 */
function createSessionStateManager() {
  const analysisStore = useAnalysisStore();

  /**
   * 按会话存储的分析状态快照 Map
   */
  const stateMap = new Map<string, AnalysisState>();

  /**
   * 活跃会话数（正在分析中的会话数）
   */
  const activeSessionCount = ref(0);

  /**
   * 更新活跃会话计数
   */
  function updateActiveCount() {
    let count = 0;
    for (const state of stateMap.values()) {
      if (state.isAnalyzing) {
        count++;
      }
    }
    activeSessionCount.value = count;
  }

  /**
   * 从 analysisStore 导出当前状态快照并保存到 Map
   * <p>用于切换会话前保存当前会话状态。</p>
   * <p><b>关键修复：</b>保留 currentRoundId，避免会话切换后丢失当前轮次信息，
   * 导致后续事件创建新轮次而重复渲染已输出内容。</p>
   *
   * @param sessionId 会话 ID
   */
  function saveState(sessionId: string | null) {
    if (!sessionId) return;

    const snapshot = analysisStore.createSnapshot();
    stateMap.set(sessionId, {
      isAnalyzing: snapshot.isAnalyzing ?? false,
      rounds: snapshot.rounds.map(round => ({
        ...round,
        thinking: { ...round.thinking },
        toolCalls: round.toolCalls.map(t => ({ ...t })),
      })),
      currentRoundId: analysisStore.state.currentRoundId,
      isTimelineExpanded: snapshot.isTimelineExpanded ?? true,
      currentSQL: snapshot.currentSQL,
      queryData: [...snapshot.queryData],
      chartConfig: snapshot.chartConfig,
      chartType: snapshot.chartType,
      analysisReport: snapshot.analysisReport,
      report: snapshot.report ? { ...snapshot.report } : null,
      searchResults: snapshot.searchResults ? [...snapshot.searchResults] : null,
      isEmptyResult: snapshot.isEmptyResult ?? false,
      errorMessage: snapshot.errorMessage,
      analysisStartTime: snapshot.analysisStartTime,
      analysisEndTime: snapshot.analysisEndTime,
      suggestions: [...snapshot.suggestions],
    });
    updateActiveCount();
  }

  /**
   * 从 Map 恢复目标会话状态到 analysisStore
   * <p>用于切换会话后恢复目标会话的分析状态。</p>
   *
   * @param sessionId 会话 ID
   */
  function restoreState(sessionId: string | null) {
    if (!sessionId) return;

    const savedState = stateMap.get(sessionId);
    if (savedState) {
      importStateToStore(savedState);
    } else {
      // 无保存状态，重置分析状态
      analysisStore.reset();
    }
    // 关键修复：恢复会话时同步 analysisSessionId，否则切回仍在分析中的会话后，
    // MessageList 的会话匹配检查（analysisSessionId === currentSessionId）会失败，
    // 导致"正在分析"的实时消息不渲染。
    analysisStore.analysisSessionId = sessionId;
  }

  /**
   * 将 AnalysisState 导入 analysisStore
   */
  function importStateToStore(state: AnalysisState) {
    // 直接替换整个 state.value
    Object.assign(analysisStore.state, {
      isAnalyzing: state.isAnalyzing,
      rounds: state.rounds.map(round => ({
        ...round,
        thinking: { ...round.thinking },
        toolCalls: round.toolCalls.map(t => ({ ...t })),
      })),
      currentRoundId: state.currentRoundId,
      isTimelineExpanded: state.isTimelineExpanded,
      currentSQL: state.currentSQL,
      queryData: [...state.queryData],
      chartConfig: state.chartConfig,
      chartType: state.chartType,
      analysisReport: state.analysisReport,
      report: state.report ? { ...state.report } : null,
      searchResults: state.searchResults ? [...state.searchResults] : null,
      isEmptyResult: state.isEmptyResult,
      errorMessage: state.errorMessage,
      analysisStartTime: state.analysisStartTime,
      analysisEndTime: state.analysisEndTime,
      suggestions: [...state.suggestions],
    });
  }

  /**
   * 更新指定会话的状态（不触发渲染，直接修改 Map 中的状态对象）
   * <p>当事件到达时，如果是当前会话，直接更新 analysisStore（触发渲染）；
   * 如果是后台会话，保存当前 analysisStore 状态，处理事件后更新后台会话的 Map 条目，
   * 再恢复当前会话状态。</p>
   *
   * @param sessionId 事件所属会话 ID
   * @param event Agent 事件
   * @param currentSessionId 当前会话 ID
   * @param toolResultBuffers 工具结果缓冲区（由 useSSE 管理）
   * @param toolCallInputBuffers 工具入参缓冲区（由 useSSE 管理）
   * @param onComplete 完成回调
   * @param onError 错误回调
   */
  function updateState(
    sessionId: string,
    event: AgentEvent,
    currentSessionId: string | null,
    toolResultBuffers: Map<string, string>,
    toolCallInputBuffers: Map<string, string>,
    onComplete?: (sessionId: string) => void,
    onError?: (sessionId: string, message: string) => void,
  ) {
    if (sessionId === currentSessionId) {
      // 当前会话 - 直接处理事件（触发渲染）
      processEvent(event, toolResultBuffers, toolCallInputBuffers, sessionId, onComplete, onError);
    } else {
      // 后台会话 - 临时保存当前状态，处理事件，保存到 Map，恢复当前状态
      const prevSessionId = currentSessionId;
      // 保存当前会话状态到 Map
      if (prevSessionId) {
        saveState(prevSessionId);
      }
      // 若有后台会话的已保存状态，先恢复后再处理事件
      const savedState = stateMap.get(sessionId);
      if (savedState) {
        importStateToStore(savedState);
      } else {
        analysisStore.reset();
        analysisStore.state.isAnalyzing = true;
      }
      // 处理事件（这会更新 analysisStore）
      processEvent(event, toolResultBuffers, toolCallInputBuffers, sessionId, (sid) => {
        // 完成时保存到 Map
        saveState(sid);
        onComplete?.(sid);
      }, (sid, msg) => {
        saveState(sid);
        onError?.(sid, msg);
      });
      // 保存后台会话的更新后状态到 Map
      saveState(sessionId);
      // 恢复之前会话的状态
      if (prevSessionId) {
        restoreState(prevSessionId);
      } else {
        // 无当前会话（如新建会话后 currentSessionId 为 null）：
        // 处理完后台事件后恢复空态，避免后台会话的 isAnalyzing=true 残留在全局，
        // 导致新会话发送按钮被禁用却迟迟无法恢复。
        analysisStore.reset();
      }
    }
  }

  /**
   * 处理单个 Agent 事件，直接操作 analysisStore
   */
  function processEvent(
    event: AgentEvent,
    toolResultBuffers: Map<string, string>,
    toolCallInputBuffers: Map<string, string>,
    sessionId: string,
    onComplete?: (sessionId: string) => void,
    onError?: (sessionId: string, message: string) => void,
  ) {
    if (!event || !event.type) {
      console.warn('[SessionStateManager] Received invalid event:', event);
      return;
    }

    switch (event.type) {
      case 'AGENT_START':
        // 设置分析所属的会话 ID，用于防止不同会话的内容混合显示
        analysisStore.analysisSessionId = sessionId;
        break;

      case 'THINKING_BLOCK_START':
        analysisStore.startNewRound();
        break;

      case 'THINKING_BLOCK_DELTA':
        if (!event.delta) return;
        analysisStore.appendThinkingToCurrentRound(event.delta);
        break;

      case 'THINKING_BLOCK_END':
        analysisStore.finalizeCurrentRoundThinking();
        break;

      case 'MODEL_CALL_START':
      case 'MODEL_CALL_END':
        break;

      case 'TOOL_RESULT_START':
        break;

      case 'TOOL_CALL_START':
        // 工具调用开始。按用户决策，TEXT_BLOCK_DELTA 一律实时渲染进报告区、不搬移叙述，
        // 故此处无需搬移待定文本到 thinking。
        if (!event.toolCallName) return;
        // toolCallId 是 AgentScope 内部标识符（如 chatcmpl-tool-xxx），不是用户可见的输入参数
        // 真正的输入参数通过 TOOL_CALL_DELTA 事件累积，此处不传入无意义的 toolCallId
        analysisStore.addToolCallToCurrentRound(event.toolCallName);
        break;

      case 'TOOL_CALL_DELTA':
        // 按 toolCallId 累积工具入参增量，供 TOOL_CALL_END 回填
        if (event.toolCallId && event.delta) {
          const existing = toolCallInputBuffers.get(event.toolCallId) || '';
          toolCallInputBuffers.set(event.toolCallId, existing + event.delta);
        }
        break;

      case 'TOOL_CALL_END':
        // 取回累积入参并回填到当前工具调用项
        if (event.toolCallName) {
          const input = event.toolCallId ? toolCallInputBuffers.get(event.toolCallId) : undefined;
          analysisStore.addToolCallToCurrentRound(event.toolCallName, input);
          if (event.toolCallId) {
            toolCallInputBuffers.delete(event.toolCallId);
          }
        }
        break;

      case 'TOOL_RESULT_TEXT_DELTA':
        if (event.toolCallId && event.delta) {
          const existing = toolResultBuffers.get(event.toolCallId) || '';
          toolResultBuffers.set(event.toolCallId, existing + event.delta);
        }
        break;

      case 'TOOL_RESULT_END':
        if (event.toolCallName) {
          const resultContent = event.toolCallId ? toolResultBuffers.get(event.toolCallId) || '' : '';
          const success = event.state !== 'error';
          analysisStore.updateToolCallInCurrentRound(event.toolCallName, resultContent, success);
          if (resultContent) {
            handleToolResult(event.toolCallName, resultContent);
          }
          if (event.toolCallId) {
            toolResultBuffers.delete(event.toolCallId);
          }
        }
        break;

      case 'TEXT_BLOCK_START':
        break;

      case 'TEXT_BLOCK_DELTA':
        if (!event.delta) return;
        // 文本增量实时追加进报告区（真流式），报告随 agent 生成逐字增长，不缓冲、不搬移。
        analysisStore.upsertReportItem(event.delta, false);
        break;

      case 'TEXT_BLOCK_END':
        break;

      case 'AGENT_RESULT':
        // 收尾/兜底：报告已由 TEXT_BLOCK_DELTA 实时追加，此处用权威最终文本覆盖（内容一致则无感）；
        // 当某些 provider 不发增量、仅此处携带全文时，作为兜底填充报告。
        if (event.result?.textContent) {
          analysisStore.upsertReportItem(event.result.textContent, true);
        }
        break;

      case 'AGENT_END':
        // Agent 结束：报告已由 TEXT_BLOCK_DELTA 实时渲染，完成收尾
        analysisStore.completeAnalysis();
        saveState(sessionId);
        onComplete?.(sessionId);
        break;

      case 'EXCEED_MAX_ITERS':
        analysisStore.completeAnalysis();
        saveState(sessionId);
        onComplete?.(sessionId);
        break;

      case 'ERROR':
        if (event.message) {
          const cleanedErrorMessage = parseBackendError(event.message);
          analysisStore.setError(cleanedErrorMessage);
          saveState(sessionId);
          onError?.(sessionId, cleanedErrorMessage);
        }
        break;

      default:
        console.warn('[SessionStateManager] Unknown event type:', event.type);
    }
  }

  /**
   * 处理工具结果，根据工具名提取特定信息
   */
  function handleToolResult(toolName: string, resultContent: string) {
    switch (toolName) {
      case 'generate_sql':
        const sql = stripCodeFences(resultContent);
        analysisStore.setSQL(sql);
        break;

      case 'execute_sql':
      case 'execute_api_query':
        // 工具结果可能是纯 JSON 数组、带 "查询返回 N 行数据：" 前缀的文本，
        // 或经 AgentScope 二次序列化的 JSON 字符串，统一交由 parseQueryDataArray 处理
        {
          const data = parseQueryDataArray(resultContent);
          if (data) {
            analysisStore.setQueryData(data);
          }
        }
        break;

      case 'generate_chart':
        try {
          const chartContent = tryUnescapeJson(resultContent);
          if (chartContent && chartContent.trim()) {
            const chartData = JSON.parse(chartContent);
            // 兼容二次序列化为字符串的情况，转成 option 对象以提取图表类型
            const chartOption = typeof chartData === 'string' ? JSON.parse(chartData) : chartData;
            const chartType = extractChartType(chartOption);
            const chartOptionStr = typeof chartData === 'string' ? chartData : JSON.stringify(chartData);
            analysisStore.setChart(chartType || 'TABLE', chartOptionStr);
          }
        } catch (err) {
          console.error('[SessionStateManager] Failed to parse chart:', err);
        }
        break;

      case 'web_search':
        const searchResults = parseWebSearchResults(resultContent);
        if (searchResults.length > 0) {
          analysisStore.setSearchResults(searchResults);
        }
        break;

      default:
        break;
    }
  }

  /**
   * 清理已完成会话的状态
   * @param sessionId 会话 ID
   */
  function cleanupState(sessionId: string) {
    stateMap.delete(sessionId);
    updateActiveCount();
  }

  /**
   * 检查是否还有活跃会话
   * <p>遍历 Map 检查 isAnalyzing 状态。</p>
   */
  function hasActiveSession(): boolean {
    for (const state of stateMap.values()) {
      if (state.isAnalyzing) {
        return true;
      }
    }
    return false;
  }

  /**
   * 获取活跃会话数
   */
  function getActiveCount() {
    return readonly(activeSessionCount);
  }

  /**
   * 获取指定会话的保存状态
   * @param sessionId 会话 ID
   */
  function getSavedState(sessionId: string): AnalysisState | undefined {
    return stateMap.get(sessionId);
  }

  return {
    saveState,
    restoreState,
    updateState,
    cleanupState,
    hasActiveSession,
    getActiveCount,
    getSavedState,
  };
}

// ========== 工具函数（从 useSSE.ts 迁移） ==========

/**
 * 尝试从消息中提取 JSON 格式的错误信息
 */
function tryParseJsonError(rawMessage: string): string | null {
  const jsonStart = rawMessage.indexOf('{');
  if (jsonStart !== -1) {
    let depth = 0;
    let jsonEnd = -1;
    for (let i = jsonStart; i < rawMessage.length; i++) {
      if (rawMessage[i] === '{') depth++;
      else if (rawMessage[i] === '}') {
        depth--;
        if (depth === 0) {
          jsonEnd = i;
          break;
        }
      }
    }
    if (jsonEnd !== -1) {
      try {
        const jsonStr = rawMessage.substring(jsonStart, jsonEnd + 1);
        const parsed = JSON.parse(jsonStr);
        if (parsed.message) {
          return parsed.message;
        }
      } catch {
        // Not valid JSON
      }
    }
  }
  try {
    const fullParsed = JSON.parse(rawMessage);
    if (fullParsed.message) {
      return fullParsed.message;
    }
  } catch {
    // Not JSON
  }
  return null;
}

/**
 * 尝试从管道符格式的消息中提取错误信息
 */
function tryParsePipeFormat(rawMessage: string): string | null {
  if (!rawMessage.includes('|')) {
    return null;
  }
  const parts = rawMessage.split('|');
  for (const part of parts) {
    const trimmed = part.trim();
    if (trimmed.includes('Access denied') || trimmed.includes('error') || trimmed.includes('failed')) {
      return trimmed;
    }
  }
  return parts[parts.length - 1].trim();
}

/**
 * 兜底提取错误信息
 */
function fallbackExtract(rawMessage: string): string {
  return rawMessage.trim();
}

/**
 * 解析并清理后端错误消息
 */
function parseBackendError(rawMessage: string): string {
  return tryParseJsonError(rawMessage)
    ?? tryParsePipeFormat(rawMessage)
    ?? fallbackExtract(rawMessage);
}

/**
 * 解析 web_search 工具结果 JSON
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
    console.error('[SessionStateManager] Failed to parse web search results:', err);
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
 * <p>处理后端发送的 JSON 格式字符串，如 "\"## 一、分析概述\\n...\""</p>
 * <p>如果内容是 JSON 字符串，返回解析后的实际文本；否则返回原始内容。</p>
 */
function tryUnescapeJson(text: string): string | null {
  if (!text) return null;
  try {
    const parsed = JSON.parse(text);
    // 如果解析结果是字符串，返回解析后的内容（去除了外层引号和转义）
    if (typeof parsed === 'string') {
      return parsed;
    }
    // 如果解析结果是对象或数组，返回原始文本
    return text;
  } catch {
    // 不是 JSON 格式，返回原始文本
    return text;
  }
}

/**
 * 从图表数据中提取图表类型
 * <p>兼容两种格式：旧格式顶层 chartType 字段；新格式纯 ECharts option（series[0].type）。</p>
 */
function extractChartType(data: any): string | null {
  if (!data) return null;
  // 旧格式：顶层 chartType 字段
  if (data.chartType) return data.chartType;
  // 新格式：纯 ECharts option，从 series[0].type 提取（如 line/bar/pie）
  if (data.series?.[0]?.type) return data.series[0].type;
  return null;
}