import axios from 'axios';
import type { AgentEvent } from '@/modules/agent/types';

/**
 * Data analysis request
 * <p>当 resumeOnly 为 true 时（刷新后仅续流运行中会话），只需 sessionId 与 clientId，
 * 其余字段可省略，后端不校验、不启动新分析。</p>
 */
export interface DataAnalysisRequest {
  sessionId: string;
  modelConfigId?: number;
  connectionId?: string;
  userQuestion?: string;
  enableWebSearch?: boolean;
  resumeOnly?: boolean;
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

/**
 * 停止数据分析（后端取消 agent 并落库为 CANCELLED）
 * <p>用于前端手动点击「停止」时真正取消后端正在运行的分析会话。</p>
 *
 * @param sessionId 要停止的会话 ID
 */
export async function stopAnalysis(sessionId: string): Promise<void> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
  const url = `${baseUrl}/agent/data-analysis/stop`;

  await axios.post<void>(url, { sessionId }, {
    headers: {
      'Content-Type': 'application/json',
    },
  });
}


