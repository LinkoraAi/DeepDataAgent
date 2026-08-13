import { defineStore } from 'pinia';
import { ref } from 'vue';
import type {
  AnalysisState,
  AnalysisSnapshot,
  ContentItem,
  ContentItemStatus,
  SearchResultItem,
  Suggestion,
} from '../types';
import { generateTimelineId } from '../composables/useTimelineItem';

export const useAnalysisStore = defineStore('analysis', () => {
  const state = ref<AnalysisState>({
    isAnalyzing: false,
    contentItems: [],
    contentSeq: 0,
    currentSQL: null,
    queryData: [],
    chartConfig: null,
    chartType: null,
    analysisReport: null,
    searchResults: null,
    isEmptyResult: false,
    errorMessage: null,
    analysisStartTime: null,
    suggestions: [],
    analysisEndTime: null,
  });

  /**
   * 当前用户问题（用于完成时创建 agent 消息）
   */
  const currentUserQuestion = ref<string>('');

  /**
   * 当前分析所属的会话 ID
   * <p>用于在 MessageList 中检查会话匹配，防止不同会话的内容混合显示。</p>
   */
  const analysisSessionId = ref<string | null>(null);

  /**
   * 重置状态
   */
  function reset() {
    state.value = {
      isAnalyzing: false,
      contentItems: [],
      contentSeq: 0,
      currentSQL: null,
      queryData: [],
      chartConfig: null,
      chartType: null,
      analysisReport: null,
      searchResults: null,
      isEmptyResult: false,
      errorMessage: null,
      analysisStartTime: null,
      suggestions: [],
      analysisEndTime: null,
    };
    analysisSessionId.value = null;
  }

  /**
   * 启动分析
   * @param sessionId 会话 ID，用于标识当前分析所属的会话
   */
  function startAnalysis(sessionId?: string) {
    reset();
    state.value.isAnalyzing = true;
    state.value.analysisStartTime = Date.now();
    if (sessionId) {
      analysisSessionId.value = sessionId;
    }
  }

  /**
   * 创建并追加一个新的内容流项
   *
   * @param type 内容项类型
   * @param status 内容项状态
   * @param extra 内容项附加字段（内容/工具名/入参/结果等）
   * @returns 创建的内容项
   */
  function pushContentItem(type: ContentItem['type'], status: ContentItemStatus, extra: Partial<ContentItem> = {}): ContentItem {
    const item: ContentItem = {
      id: `${type}-${generateTimelineId()}`,
      seq: state.value.contentSeq++,
      type,
      status,
      startTime: Date.now(),
      ...extra,
    };
    state.value.contentItems.push(item);
    return item;
  }

  /**
   * 查找最后一个进行中状态的指定类型内容项
   *
   * @param type 内容项类型
   * @returns 进行中的内容项；不存在则返回 undefined
   */
  function findInProgressItem(type: ContentItem['type']): ContentItem | undefined {
    for (let i = state.value.contentItems.length - 1; i >= 0; i--) {
      const item = state.value.contentItems[i];
      if (item.type === type && item.status === 'in_progress') {
        return item;
      }
    }
    return undefined;
  }

  /**
   * 追加思考增量：无进行中思考项则新建，有则原地追加并返回该消息项
   *
   * @param delta 思考内容增量
   */
  function appendThinkingDelta(delta: string) {
    if (!delta) return;
    let item = findInProgressItem('thinking');
    if (!item) {
      item = pushContentItem('thinking', 'in_progress', { content: '' });
    }
    item.content = (item.content || '') + delta;
  }

  /**
   * 完成当前思考：最后一个进行中思考项收敛为 completed
   */
  function completeThinking() {
    const item = findInProgressItem('thinking');
    if (item) {
      item.status = 'completed';
      item.endTime = Date.now();
    }
  }

  /**
   * 新增工具调用内容项（进行中）
   *
   * @param toolName 工具名称
   * @param input 工具输入参数（JSON 字符串）
   * @param toolCallId 工具调用 ID（交错事件时用于精确定位）
   */
  function addToolCallItem(toolName: string, input?: string, toolCallId?: string) {
    pushContentItem('tool_call', 'in_progress', { toolName, input, toolCallId });
  }

  /**
   * 查找指定 toolCallId 的工具调用内容项
   *
   * @param toolCallId 工具调用 ID
   * @returns 匹配的工具调用项；不存在则返回 undefined
   */
  function findToolCallItem(toolCallId: string): ContentItem | undefined {
    for (let i = state.value.contentItems.length - 1; i >= 0; i--) {
      const item = state.value.contentItems[i];
      if (item.type === 'tool_call' && item.toolCallId === toolCallId) {
        return item;
      }
    }
    return undefined;
  }

  /**
   * 查找指定 toolCallId 的工具结果内容项
   *
   * @param toolCallId 工具调用 ID
   * @returns 匹配的工具结果项；不存在则返回 undefined
   */
  function findToolResultItem(toolCallId: string): ContentItem | undefined {
    for (let i = state.value.contentItems.length - 1; i >= 0; i--) {
      const item = state.value.contentItems[i];
      if (item.type === 'tool_result' && item.toolCallId === toolCallId) {
        return item;
      }
    }
    return undefined;
  }

  /**
   * 新增工具结果内容项（进行中）
   *
   * @param toolName 工具名称（通常继承自同 toolCallId 的工具调用项）
   * @param toolCallId 工具调用 ID（结果归属的调用标识）
   * @returns 新建的工具结果项
   */
  function addToolResultItem(toolName?: string, toolCallId?: string): ContentItem {
    return pushContentItem('tool_result', 'in_progress', { toolName, toolCallId });
  }

  /**
   * 追加工具入参增量到指定工具调用（无 toolCallId 时回退到最后一个进行中工具调用）
   *
   * @param delta 入参增量
   * @param toolCallId 工具调用 ID（可选）
   */
  function appendToolInput(delta: string, toolCallId?: string) {
    if (!delta) return;
    const item = toolCallId ? (findToolCallItem(toolCallId) ?? findInProgressItem('tool_call')) : findInProgressItem('tool_call');
    if (item) {
      item.input = (item.input || '') + delta;
    }
  }

  /**
   * 追加工具结果增量到指定工具结果项（首次到达时惰性创建独立结果项）
   * <p>工具结果与工具调用拆分为两个独立内容项：结果增量写入 tool_result 项的 result，
   * 工具名在惰性创建时继承自同一 toolCallId 的工具调用项。</p>
   *
   * @param delta 结果增量
   * @param toolCallId 工具调用 ID（可选）
   */
  function appendToolResult(delta: string, toolCallId?: string) {
    if (!delta) return;
    let item = toolCallId ? findToolResultItem(toolCallId) : findInProgressItem('tool_result');
    if (!item) {
      // 惰性创建独立结果项：工具名继承同 toolCallId 的工具调用项
      const toolCall = toolCallId ? findToolCallItem(toolCallId) : undefined;
      item = addToolResultItem(toolCall?.toolName, toolCallId);
    }
    item.result = (item.result || '') + delta;
  }

  /**
   * 完成最后一个进行中工具调用
   * <p>调用消息仅承载工具名与入参，结果由独立的 tool_result 内容项承载，此处不写入结果。</p>
   *
   * @param success 是否成功；成功置 completed，失败置 failed
   */
  function completeToolCall(success: boolean = true) {
    const item = findInProgressItem('tool_call');
    if (!item) return;
    item.status = success ? 'completed' : 'failed';
    item.endTime = Date.now();
  }

  /**
   * 完成最后一个进行中工具结果项
   *
   * @param result 完整执行结果（可选，覆盖流式结果）
   * @param success 是否成功；成功置 completed，失败置 failed
   */
  function completeToolResult(result?: string, success: boolean = true) {
    const item = findInProgressItem('tool_result');
    if (!item) return;
    if (result) {
      item.result = result;
    }
    item.status = success ? 'completed' : 'failed';
    item.endTime = Date.now();
  }

  /**
   * 追加报告增量：无进行中报告项则新建，有则原地追加
   * <p>同步维护 analysisReport 派生字段。</p>
   *
   * @param delta 报告内容增量
   */
  function appendReportDelta(delta: string) {
    if (!delta) return;
    let item = findInProgressItem('report');
    if (!item) {
      item = pushContentItem('report', 'in_progress', { content: '' });
    }
    item.content = (item.content || '') + delta;
    state.value.analysisReport = item.content;
  }

  /**
   * 将进行中的报告项转换为思考项并收敛为完成态（工具调用前的中途叙述）
   * <p>事件线对齐（与后端 convertAssistantToThinking 语义一致）：agent 在工具调用前
   * 输出的 TEXT_BLOCK 是过程叙述，应作为「思考」展示而非混入最终报告。
   * TOOL_CALL_START 时调用；转换后最终报告由后续 TEXT_BLOCK_DELTA 新建报告项承接，
   * 确保分析报告严格出现在事件线最后。</p>
   */
  function convertReportToThinking() {
    const item = findInProgressItem('report');
    if (!item) return;
    item.type = 'thinking';
    item.status = 'completed';
    item.endTime = Date.now();
    // 叙述不再作为报告，重取最后一个报告项同步派生字段
    const lastReport = [...state.value.contentItems].reverse().find(i => i.type === 'report');
    state.value.analysisReport = lastReport?.content ?? null;
  }

  /**
   * 完成报告：最后一个进行中报告项收敛为 completed
   * <p>存在权威最终文本时覆盖流式内容（如 AGENT_RESULT 兜底全文）。</p>
   *
   * @param finalText 权威最终文本（可选）
   */
  function completeReport(finalText?: string) {
    const item = findInProgressItem('report');
    if (item) {
      if (finalText) {
        item.content = finalText;
      }
      item.status = 'completed';
      item.endTime = Date.now();
    } else if (finalText) {
      // 兜底：无进行中报告项但携带全文，直接创建完成态报告项
      pushContentItem('report', 'completed', { content: finalText, endTime: Date.now() });
    }
    // 同步派生字段：取最后一个报告项的内容
    const lastReport = [...state.value.contentItems].reverse().find(i => i.type === 'report');
    state.value.analysisReport = lastReport?.content ?? null;
  }

  /**
   * 设置 SQL
   */
  function setSQL(sql: string) {
    state.value.currentSQL = sql;
  }

  /**
   * 设置查询数据
   */
  function setQueryData(data: Record<string, any>[]) {
    state.value.queryData = data;
  }

  /**
   * 设置图表
   */
  function setChart(chartType: string, chartOption: string) {
    state.value.chartType = chartType;
    try {
      state.value.chartConfig = JSON.parse(chartOption);
    } catch (err) {
      console.error('Failed to parse chart option:', err);
      state.value.chartConfig = null;
    }
  }

  /**
   * 设置建议追问列表
   */
  function setSuggestions(suggestions: Suggestion[]) {
    state.value.suggestions = suggestions;
  }

  /**
   * 设置搜索结果
   */
  function setSearchResults(results: SearchResultItem[]) {
    state.value.searchResults = results;
  }

  /**
   * 设置空结果
   */
  function setEmptyResult(isEmpty: boolean) {
    state.value.isEmptyResult = isEmpty;
  }

  /**
   * 设置错误
   */
  function setError(message: string) {
    state.value.errorMessage = message;
    state.value.isAnalyzing = false;
  }

  /**
   * 完成分析
   * <p>清理流式状态：所有进行中内容项收敛为 completed（failed 保持展开），
   * 同步报告派生字段。</p>
   */
  function completeAnalysis() {
    state.value.isAnalyzing = false;
    state.value.analysisEndTime = Date.now();
    for (const item of state.value.contentItems) {
      if (item.status === 'in_progress') {
        item.status = 'completed';
        item.endTime = state.value.analysisEndTime;
      }
    }
    const lastReport = [...state.value.contentItems].reverse().find(i => i.type === 'report');
    state.value.analysisReport = lastReport?.content ?? null;
  }

  /**
   * 从当前状态创建分析快照
   */
  function createSnapshot(): AnalysisSnapshot {
    return {
      isAnalyzing: state.value.isAnalyzing,
      contentItems: state.value.contentItems.map(item => ({ ...item })),
      currentSQL: state.value.currentSQL,
      queryData: [...state.value.queryData],
      chartConfig: state.value.chartConfig,
      chartType: state.value.chartType,
      analysisReport: state.value.analysisReport,
      searchResults: state.value.searchResults ? [...state.value.searchResults] : null,
      isEmptyResult: state.value.isEmptyResult,
      errorMessage: state.value.errorMessage,
      analysisStartTime: state.value.analysisStartTime,
      analysisEndTime: state.value.analysisEndTime,
      suggestions: [...state.value.suggestions],
    };
  }

  /**
   * 导出当前状态快照（使用已存在的 createSnapshot 方法）
   */
  function exportSnapshot(): AnalysisSnapshot {
    return createSnapshot();
  }

  /**
   * 导入 AnalysisSnapshot 快照到当前状态
   * <p>从 Map 中恢复指定会话的状态时使用。</p>
   *
   * @param snapshot 要导入的分析快照
   */
  function importSnapshot(snapshot: AnalysisSnapshot) {
    // 序号从快照最大 seq 继续递增，保证后续新增内容项顺序正确
    const nextSeq = snapshot.contentItems.reduce((max, item) => Math.max(max, item.seq + 1), 0);
    state.value = {
      isAnalyzing: snapshot.isAnalyzing ?? false,
      contentItems: snapshot.contentItems.map(item => ({ ...item })),
      contentSeq: nextSeq,
      currentSQL: snapshot.currentSQL,
      queryData: [...snapshot.queryData],
      chartConfig: snapshot.chartConfig,
      chartType: snapshot.chartType,
      analysisReport: snapshot.analysisReport,
      searchResults: snapshot.searchResults ? [...snapshot.searchResults] : null,
      isEmptyResult: snapshot.isEmptyResult ?? false,
      errorMessage: snapshot.errorMessage,
      analysisStartTime: snapshot.analysisStartTime,
      analysisEndTime: snapshot.analysisEndTime,
      suggestions: [...snapshot.suggestions],
    };
  }

  return {
    state,
    currentUserQuestion,
    analysisSessionId,
    reset,
    startAnalysis,
    // 内容流操作方法
    pushContentItem,
    findInProgressItem,
    appendThinkingDelta,
    completeThinking,
    addToolCallItem,
    findToolCallItem,
    findToolResultItem,
    addToolResultItem,
    appendToolInput,
    appendToolResult,
    completeToolCall,
    completeToolResult,
    appendReportDelta,
    convertReportToThinking,
    completeReport,
    // 通用设置方法
    setSQL,
    setQueryData,
    setChart,
    setSearchResults,
    setEmptyResult,
    setError,
    setSuggestions,
    completeAnalysis,
    createSnapshot,
    exportSnapshot,
    importSnapshot,
  };
});
