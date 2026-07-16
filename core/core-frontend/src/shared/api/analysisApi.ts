import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { SSEEvent } from '@/modules/agent/types';

/**
 * Data analysis request
 */
export interface DataAnalysisRequest {
  sessionId: string;
  modelConfigId: number;
  connectionId: string;
  userQuestion: string;
  enableWebSearch: boolean;
}

/**
 * Execute data analysis with SSE streaming using @microsoft/fetch-event-source
 */
export async function analyzeStream(
  request: DataAnalysisRequest,
  onEvent: (event: SSEEvent) => void,
  onError?: (error: Error) => void,
  onComplete?: () => void
): Promise<AbortController> {
  const controller = new AbortController();

  const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
  const url = `${baseUrl}/agent/data-analysis/analyze`;

  try {
    await fetchEventSource(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
      },
      body: JSON.stringify(request),
      signal: controller.signal,
      async onopen(response) {
        if (!response.ok) {
          const errorMsg = `Connection failed: ${response.status} ${response.statusText}`;
          throw new Error(errorMsg);
        }
      },
      onmessage(ev) {
        // ev.data 是 SSE data 字段的原始字符串
        if (!ev.data) return;
        try {
          const event: SSEEvent = JSON.parse(ev.data);
          if (event.type) {
            onEvent(event);
          }
        } catch (err) {
          console.error('[SSE] Failed to parse event data:', err, 'Raw:', ev.data);
        }
      },
      onerror(err) {
        console.error('[SSE] Stream error:', err);
        onError?.(err instanceof Error ? err : new Error(String(err)));
        // throw error 阻止自动重连
        throw err;
      },
      onclose() {
        onComplete?.();
      },
      openWhenHidden: true,
    });
  } catch (err: any) {
    if (err.name === 'AbortError') {
      console.debug('[SSE] Request aborted by user');
    } else {
      // fetchEventSource 内部的 onerror 已经处理了，这里只处理连接级别的错误
      console.error('[SSE] Connection error:', err);
      onError?.(err instanceof Error ? err : new Error(String(err)));
      onComplete?.();
    }
  }

  return controller;
}
