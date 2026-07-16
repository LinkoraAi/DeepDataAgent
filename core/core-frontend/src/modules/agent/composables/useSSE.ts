import { ref } from 'vue';
import { analyzeStream, type DataAnalysisRequest } from '@/shared/api/analysisApi';
import type { SSEEvent, SearchResultItem } from '../types';
import { useAnalysisStore } from '../stores/analysis';

/**
 * Parse and clean up backend error message
 */
function parseBackendError(rawMessage: string): string {
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

  if (rawMessage.includes('|')) {
    const parts = rawMessage.split('|');
    for (const part of parts) {
      const trimmed = part.trim();
      if (trimmed.includes('Access denied') || trimmed.includes('error') || trimmed.includes('failed')) {
        return trimmed;
      }
    }
    return parts[parts.length - 1].trim();
  }

  return rawMessage;
}

/**
 * Parse web_search tool result JSON into structured SearchResultItem array
 * <p>Backend returns JSON format like:
 * {"results": [{"title": "...", "url": "...", "snippet": "...", "content": "..."}]}</p>
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
    console.error('[SSE Handler] Failed to parse web search results:', err);
  }

  return results;
}

/**
 * SSE composable for data analysis streaming
 */
export function useSSE() {
  const analysisStore = useAnalysisStore();
  const abortController = ref<AbortController | null>(null);

  // 回调：分析完成或出错时通知外部写入消息
  let onCompleteCallback: (() => void) | null = null;
  let onErrorCallback: ((message: string) => void) | null = null;

  /**
   * 设置完成/出错回调
   */
  function setCallbacks(onComplete: () => void, onError: (message: string) => void) {
    onCompleteCallback = onComplete;
    onErrorCallback = onError;
  }

  /**
   * Start analysis with SSE
   */
  async function startAnalysis(request: DataAnalysisRequest) {
    analysisStore.startAnalysis();

    abortController.value = await analyzeStream(
      request,
      (event: SSEEvent) => {
        handleSSEEvent(event);
      },
      (error: Error) => {
        analysisStore.setError(error.message);
        onErrorCallback?.(error.message);
      },
      () => {
        analysisStore.completeAnalysis();
        onCompleteCallback?.();
      }
    );
  }

  /**
   * Handle SSE event
   */
  function handleSSEEvent(event: SSEEvent) {
    if (!event || !event.type) {
      console.warn('[SSE Handler] Received invalid event:', event);
      return;
    }

    switch (event.type) {
      case 'thinking':
        if (!event.message) return;
        analysisStore.addThinkingStep(event.message);
        break;

      case 'tool_call':
        if (!event.message) return;
        analysisStore.addToolCall(event.message);
        break;

      case 'tool_result':
        if (!event.message) return;
        analysisStore.updateToolCallResult(event.message, event.payload || '', true);
        
        // 识别 web_search 工具结果，解析为结构化数据
        if (event.message === 'web_search' && event.payload) {
          const searchResults = parseWebSearchResults(event.payload);
          if (searchResults.length > 0) {
            analysisStore.setSearchResults(searchResults);
          }
        }
        break;

      case 'sql':
        if (!event.message) return;
        analysisStore.setSQL(event.message);
        break;

      case 'data':
        try {
          if (!event.message) return;
          const data = JSON.parse(event.message);
          analysisStore.setQueryData(data);
        } catch (err) {
          console.error('[SSE Handler] Failed to parse data:', err);
        }
        break;

      case 'chart':
        try {
          const chartData = event.payload ? JSON.parse(event.payload) : null;
          if (!chartData) return;
          analysisStore.setChart(chartData.chartType || 'TABLE', chartData.chartOption || '{}');
        } catch (err) {
          console.error('[SSE Handler] Failed to parse chart:', err);
        }
        break;

      case 'analysis':
        if (!event.message) return;
        analysisStore.setAnalysisReport(event.message);
        break;

      case 'done':
        try {
          const result = event.payload ? JSON.parse(event.payload) : null;
          if (result?.isEmptyResult) {
            analysisStore.setEmptyResult(true);
          }
        } catch (err) {
          console.error('[SSE Handler] Failed to parse done event:', err);
        }
        analysisStore.completeAnalysis();
        onCompleteCallback?.();
        break;

      case 'error':
        if (!event.message) return;
        const cleanedErrorMessage = parseBackendError(event.message);
        analysisStore.setError(cleanedErrorMessage);
        onErrorCallback?.(cleanedErrorMessage);
        break;

      default:
        console.warn('[SSE Handler] Unknown event type:', event.type);
    }
  }

  /**
   * Stop analysis
   */
  function stopAnalysis() {
    if (abortController.value) {
      abortController.value.abort();
      abortController.value = null;
    }
    analysisStore.completeAnalysis();
  }

  return {
    startAnalysis,
    stopAnalysis,
    setCallbacks,
  };
}
