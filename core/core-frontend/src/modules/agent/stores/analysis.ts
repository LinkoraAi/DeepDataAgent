import { defineStore } from 'pinia';
import { ref } from 'vue';
import type {
  AnalysisState,
  AnalysisSnapshot,
  SearchResultItem,
  Suggestion,
  ReActRound,
  ThinkingTimelineItem,
  ToolCallTimelineItem,
  ReportTimelineItem,
} from '../types';
import { generateTimelineId } from '../composables/useTimelineItem';

export const useAnalysisStore = defineStore('analysis', () => {
  const state = ref<AnalysisState>({
    isAnalyzing: false,
    rounds: [],
    currentRoundId: null,
    isTimelineExpanded: true,
    currentSQL: null,
    queryData: [],
    chartConfig: null,
    chartType: null,
    analysisReport: null,
    report: null,
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
      rounds: [],
      currentRoundId: null,
      isTimelineExpanded: true,
      currentSQL: null,
      queryData: [],
      chartConfig: null,
      chartType: null,
      analysisReport: null,
      report: null,
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
   * 创建新轮次并设为当前轮次
   * <p>用于 thinking 事件到达时开启新一轮 ReAct 循环。
   * 若当前轮次仍有 active 工具（status=running），先强制结束当前轮次。</p>
   *
   * @returns 新创建的轮次对象
   */
  function startNewRound(): ReActRound {
    // 若当前轮次仍有 active 工具，强制结束（符合 ReAct 语义：新一轮思考开启则旧轮次结束）
    if (state.value.currentRoundId) {
      const current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
      if (current && current.isActive) {
        forceCompleteCurrentRound();
      }
    }

    const now = Date.now();
    const thinkingItem: ThinkingTimelineItem = {
      id: `thinking-${generateTimelineId()}`,
      timestamp: now,
      type: 'thinking',
      content: '',
      isStreaming: true,
    };
    const newRound: ReActRound = {
      id: `round-${generateTimelineId()}`,
      startTime: now,
      thinking: thinkingItem,
      toolCalls: [],
      isActive: true,
      isCollapsed: false,
    };
    state.value.rounds.push(newRound);
    state.value.currentRoundId = newRound.id;
    return newRound;
  }

  /**
   * 追加思考增量到当前轮次
   * <p>若无当前轮次，自动创建新轮次（兜底场景）。</p>
   *
   * @param delta 思考内容增量
   */
  function appendThinkingToCurrentRound(delta: string) {
    if (!delta) return;
    let current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
    if (!current || !current.thinking.isStreaming) {
      // 无当前轮次或当前轮次思考已结束，创建新轮次
      current = startNewRound();
    }
    current.thinking.content += delta;
  }

  /**
   * 标记当前轮次的思考为完成状态
   * <p>thinking.isStreaming 置为 false，保留 currentRoundId 等待工具调用归入。</p>
   * 同步更新轮次的 isActive 状态。
   */
  function finalizeCurrentRoundThinking() {
    const current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
    if (current) {
      current.thinking.isStreaming = false;
      updateRoundActiveState(current);
    }
  }

  /**
   * 在当前轮次新增工具调用
   * <p>若无当前轮次，创建空 thinking 的兜底轮次。
   * 对重复同名 running 工具做去重（更新 input 而非新增）。</p>
   *
   * @param toolName 工具名称
   * @param input 工具输入参数（JSON 字符串）
   */
  function addToolCallToCurrentRound(toolName: string, input?: string) {
    let current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
    if (!current) {
      // 无当前轮次，创建空 thinking 兜底轮次
      current = startNewRound();
      current.thinking.isStreaming = false; // 兜底轮次思考为空且非流式
    }

    // 去重：若最后一个同名工具仍在 running 且 input 为空，更新 input
    const lastRunning = [...current.toolCalls].reverse().find(
      t => t.toolName === toolName && t.status === 'running' && !t.input
    );
    if (lastRunning) {
      lastRunning.input = input;
      return;
    }

    const now = Date.now();
    const newTool: ToolCallTimelineItem = {
      id: `tool-${generateTimelineId()}`,
      timestamp: now,
      type: 'tool_call',
      toolName,
      status: 'running',
      input,
      startTime: now,
    };
    current.toolCalls.push(newTool);
    updateRoundActiveState(current);
  }

  /**
   * 更新当前轮次最后一个同名 running 工具的结果
   *
   * @param toolName 工具名称
   * @param result 执行结果
   * @param success 是否成功
   */
  function updateToolCallInCurrentRound(toolName: string, result: string, success: boolean = true) {
    const current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
    if (!current) return;

    // 从后往前查找最后一个 running 状态的同名工具
    const tool = [...current.toolCalls].reverse().find(
      t => t.toolName === toolName && t.status === 'running'
    );
    if (tool) {
      tool.status = success ? 'success' : 'error';
      tool.result = result;
      tool.endTime = Date.now();
    }
    updateRoundActiveState(current);
  }

  /**
   * 强制结束当前轮次
   * <p>标记 endTime，但不修改内部工具状态（保留 running 状态作为历史记录）。
   * 清空 currentRoundId，用于下一轮 thinking 开启新轮次。</p>
   */
  function forceCompleteCurrentRound() {
    const current = state.value.rounds.find(r => r.id === state.value.currentRoundId);
    if (current) {
      if (!current.endTime) {
        current.endTime = Date.now();
      }
      current.isActive = false;
      // 关键修复：轮次完成时同步折叠。否则切换会话回来后重新挂载 TimelineRound，
      // 初值依赖 isCollapsed 快照（仍为 false），导致已完成轮次错误展开。
      current.isCollapsed = true;
    }
    state.value.currentRoundId = null;
  }

  /**
   * 更新轮次的 isActive 状态
   * <p>isActive = thinking.isStreaming || 任意 toolCall.status=running</p>
   */
  function updateRoundActiveState(round: ReActRound) {
    round.isActive = round.thinking.isStreaming ||
      round.toolCalls.some(t => t.status === 'running');
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
   * 设置分析报告
   * @param report 分析报告内容
   * @param isComplete 是否为完整报告（true: 覆盖，false: 追加）
   */
  function setAnalysisReport(report: string, isComplete: boolean = true) {
    if (isComplete) {
      state.value.analysisReport = report;
    } else {
      state.value.analysisReport = (state.value.analysisReport || '') + report;
    }
  }

  /**
   * 检测是否为叙述性前缀。
   * <p>用于过滤 LLM 流式输出中的非报告内容。</p>
   */
  function isNarrativePrefix(text: string): boolean {
    const prefixes = [
      '我将帮您', '让我', '现在', '好的', '首先', '接下来', '我已经',
      '我将', '我来', '请稍等', '正在分析', '我将为您', '我将进行'
    ];
    const trimmed = text.trim();
    return prefixes.some(prefix => trimmed.startsWith(prefix));
  }

  /**
   * 更新时间线报告项
  
   * <p>用于 analysis SSE 事件的流式处理。增量时追加内容并标记为流式中，
   * 完成时标记为非流式。</p>
   *
   * @param delta 报告内容增量
   * @param isComplete 是否为报告完成事件
   */
  function upsertReportItem(delta: string, isComplete: boolean = false) {
    // 过滤明显的叙述性前缀（仅在报告为空且非完成事件时）
    if (!isComplete && !state.value.report && isNarrativePrefix(delta)) {
      return;
    }
    if (!state.value.report && !isComplete) {
      // 创建新的流式报告（首次增量）
      const now = Date.now();
      state.value.report = {
        id: 'report-1',
        timestamp: now,
        type: 'report',
        content: delta,
        isStreaming: true,
      };
    } else if (state.value.report) {
      if (isComplete) {
        // 完成事件：用完整内容替换已流式输出的内容，避免重复
        // （报告可能已通过 TEXT_BLOCK_DELTA 增量流式输出，
        //  但 AGENT_RESULT 或 generate_analysis 工具结果会发送完整报告，
        //  此处必须替换而不是追加，否则会出现重复内容）
        state.value.report = {
          ...state.value.report,
          content: delta,
          isStreaming: false,
        };
      } else if (state.value.report.isStreaming) {
        // 增量事件：仅在报告仍在流式中时才追加
        // 防止报告已完成后，迟到的增量事件再次追加导致内容重复
        state.value.report = {
          ...state.value.report,
          content: state.value.report.content + delta,
          isStreaming: true,
        };
      }
      // 如果报告已完成（isStreaming=false），忽略迟到的增量事件
    } else if (isComplete) {
      // 首次即为完成事件（无流式过程）
      const now = Date.now();
      state.value.report = {
        id: 'report-1',
        timestamp: now,
        type: 'report',
        content: delta,
        isStreaming: false,
      };
    }

    // 同步 legacy 字段，保持兼容
    if (state.value.report) {
      state.value.analysisReport = state.value.report.content;
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
   * <p>清理流式状态：currentRoundId 清空，所有未结束轮次回填 endTime，
   * 所有轮次标记 isActive=false 并 isCollapsed=true（完成后默认折叠）。</p>
   */
  function completeAnalysis() {
    state.value.isAnalyzing = false;
    state.value.analysisEndTime = Date.now();
    state.value.currentRoundId = null;
    // 回填所有未结束轮次的 endTime，并标记为非激活、默认折叠
    for (const round of state.value.rounds) {
      if (!round.endTime) {
        round.endTime = state.value.analysisEndTime;
      }
      round.isActive = false;
      round.isCollapsed = true;
    }
    // 结束报告流式状态
    if (state.value.report) {
      state.value.report.isStreaming = false;
    }
    // 保持时间线可见（展示各轮次折叠摘要），用户可手动折叠
    state.value.isTimelineExpanded = true;
  }

  /**
   * 从当前状态创建分析快照
   */
  function createSnapshot(): AnalysisSnapshot {
    return {
      isAnalyzing: state.value.isAnalyzing,
      rounds: state.value.rounds.map(round => ({
        ...round,
        thinking: { ...round.thinking },
        toolCalls: round.toolCalls.map(t => ({ ...t })),
      })),
      isTimelineExpanded: state.value.isTimelineExpanded,
      currentSQL: state.value.currentSQL,
      queryData: [...state.value.queryData],
      chartConfig: state.value.chartConfig,
      chartType: state.value.chartType,
      analysisReport: state.value.analysisReport,
      report: state.value.report ? { ...state.value.report } : null,
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
    state.value = {
      isAnalyzing: snapshot.isAnalyzing ?? false,
      rounds: snapshot.rounds.map(round => ({
        ...round,
        thinking: { ...round.thinking },
        toolCalls: round.toolCalls.map(t => ({ ...t })),
      })),
      currentRoundId: null,
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
    };
  }

  return {
    state,
    currentUserQuestion,
    analysisSessionId,
    reset,
    startAnalysis,
    // 轮次操作方法
    startNewRound,
    appendThinkingToCurrentRound,
    finalizeCurrentRoundThinking,
    addToolCallToCurrentRound,
    updateToolCallInCurrentRound,
    forceCompleteCurrentRound,
    // 通用设置方法
    setSQL,
    setQueryData,
    setChart,
    setAnalysisReport,
    upsertReportItem,
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
