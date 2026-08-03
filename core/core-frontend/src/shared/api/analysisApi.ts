import axios from 'axios';
import type { AgentEvent } from '@/modules/agent/types';

/**
 * Data analysis request
 */
export interface DataAnalysisRequest {
  sessionId: string;
  modelConfigId: number;
  connectionId: string;
  userQuestion: string;
  enableWebSearch: boolean;
  clientId: string;
}

/**
 * Data analysis response
 */
export interface DataAnalysisResponse {
  sessionId: string;
  message: string;
}

/**
 * 执行数据分析（异步启动）
 * <p>发送分析请求到后端，后端会异步执行分析并通过 SSE 连接推送事件。
 * 事件通过 useSSEConnection 的回调接收。</p>
 *
 * @param request 分析请求参数（包含 clientId）
 * @returns Promise<DataAnalysisResponse> 分析响应
 */
export async function analyzeStream(
  request: DataAnalysisRequest
): Promise<DataAnalysisResponse> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
  const url = `${baseUrl}/agent/data-analysis/analyze`;

  const response = await axios.post<DataAnalysisResponse>(url, request, {
    headers: {
      'Content-Type': 'application/json',
    },
  });

  return response.data;
}


