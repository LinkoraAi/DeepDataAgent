import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { ToolCallItem, AnalysisState, AnalysisSnapshot, SearchResultItem } from '../types';

export const useAnalysisStore = defineStore('analysis', () => {
  const state = ref<AnalysisState>({
    isAnalyzing: false,
    thinkingSteps: [],
    toolCalls: [],
    currentSQL: null,
    queryData: [],
    chartConfig: null,
    chartType: null,
    analysisReport: null,
    searchResults: null,
    isEmptyResult: false,
    errorMessage: null,
    analysisStartTime: null,
  });

  /**
   * Current user question (used to create agent message on completion)
   */
  const currentUserQuestion = ref<string>('');

  /**
   * Reset state
   */
  function reset() {
    state.value = {
      isAnalyzing: false,
      thinkingSteps: [],
      toolCalls: [],
      currentSQL: null,
      queryData: [],
      chartConfig: null,
      chartType: null,
      analysisReport: null,
      searchResults: null,
      isEmptyResult: false,
      errorMessage: null,
      analysisStartTime: null,
    };
  }

  /**
   * Start analysis
   */
  function startAnalysis() {
    reset();
    state.value.isAnalyzing = true;
    state.value.analysisStartTime = Date.now();
  }

  /**
   * Add thinking step
   */
  function addThinkingStep(step: string) {
    state.value.thinkingSteps.push(step);
  }

  /**
   * Add tool call
   */
  function addToolCall(toolName: string) {
    state.value.toolCalls.push({
      name: toolName,
      status: 'running',
      startTime: Date.now(),
    });
  }

  /**
   * Update tool call result
   */
  function updateToolCallResult(toolName: string, result: string, success: boolean = true) {
    const tool = state.value.toolCalls.find(t => t.name === toolName);
    if (tool) {
      tool.status = success ? 'success' : 'error';
      tool.result = result;
      tool.endTime = Date.now();
    }
  }

  /**
   * Set SQL
   */
  function setSQL(sql: string) {
    state.value.currentSQL = sql;
  }

  /**
   * Set query data
   */
  function setQueryData(data: Record<string, any>[]) {
    state.value.queryData = data;
  }

  /**
   * Set chart
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
   * Set analysis report
   */
  function setAnalysisReport(report: string) {
    state.value.analysisReport = report;
  }

  /**
   * Set search results from web search tool
   */
  function setSearchResults(results: SearchResultItem[]) {
    state.value.searchResults = results;
  }

  /**
   * Set empty result
   */
  function setEmptyResult(isEmpty: boolean) {
    state.value.isEmptyResult = isEmpty;
  }

  /**
   * Set error
   */
  function setError(message: string) {
    state.value.errorMessage = message;
    state.value.isAnalyzing = false;
  }

  /**
   * Complete analysis
   */
  function completeAnalysis() {
    state.value.isAnalyzing = false;
  }

  /**
   * Create analysis snapshot from current state
   */
  function createSnapshot(): AnalysisSnapshot {
    return {
      thinkingSteps: [...state.value.thinkingSteps],
      toolCalls: [...state.value.toolCalls],
      currentSQL: state.value.currentSQL,
      queryData: [...state.value.queryData],
      chartConfig: state.value.chartConfig,
      chartType: state.value.chartType,
      analysisReport: state.value.analysisReport,
      searchResults: state.value.searchResults ? [...state.value.searchResults] : null,
      isEmptyResult: state.value.isEmptyResult,
      errorMessage: state.value.errorMessage,
      analysisStartTime: state.value.analysisStartTime,
      analysisEndTime: Date.now(),
    };
  }

  return {
    state,
    currentUserQuestion,
    reset,
    startAnalysis,
    addThinkingStep,
    addToolCall,
    updateToolCallResult,
    setSQL,
    setQueryData,
    setChart,
    setAnalysisReport,
    setSearchResults,
    setEmptyResult,
    setError,
    completeAnalysis,
    createSnapshot,
  };
});
