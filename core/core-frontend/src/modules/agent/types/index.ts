/**
 * Session response
 */
export interface Session {
  id: string;
  title: string;
  datasourceId: number;
  modelConfigId: number;
  status: string;
  messageCount: number;
  lastMessageAt: string;
  createdAt: string;
  closedAt?: string;
}

/**
 * Search result item for web search tool
 */
export interface SearchResultItem {
  title: string;
  url: string;
  snippet: string;
}

/**
 * Session list item
 */
export interface SessionListItem {
  id: string;
  title: string;
  datasourceId: number;
  modelConfigId: number;
  status: string;
  messageCount: number;
  lastMessageAt: string;
  createdAt: string;
}

/**
 * Message response
 */
export interface Message {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  toolCalls?: string;
  toolResult?: string;
  metadata?: string;
  createdAt: string;
}

/**
 * SSE event types
 */
export type SSEEventType = 
  | 'thinking'
  | 'tool_call'
  | 'tool_result'
  | 'sql'
  | 'data'
  | 'chart'
  | 'analysis'
  | 'search_results'
  | 'done'
  | 'error';

/**
 * SSE event
 */
export interface SSEEvent {
  type: SSEEventType;
  message: string;
  payload?: string | null;
}

/**
 * Tool call item
 */
export interface ToolCallItem {
  name: string;
  status: 'running' | 'success' | 'error';
  startTime: number;
  endTime?: number;
  result?: string;
}

/**
 * Data analysis response
 */
export interface DataAnalysisResponse {
  sql: string;
  data: Record<string, any>[];
  chartType: string;
  chartOption: string;
  analysis: string;
  isEmptyResult: boolean;
}

/**
 * Analysis state
 */
export interface AnalysisState {
  isAnalyzing: boolean;
  thinkingSteps: string[];
  toolCalls: ToolCallItem[];
  currentSQL: string | null;
  queryData: Record<string, any>[];
  chartConfig: any | null;
  chartType: string | null;
  analysisReport: string | null;
  searchResults: SearchResultItem[] | null;
  isEmptyResult: boolean;
  errorMessage: string | null;
  analysisStartTime: number | null;
}

/**
 * Analysis snapshot for agent message
 */
export interface AnalysisSnapshot {
  thinkingSteps: string[];
  toolCalls: ToolCallItem[];
  currentSQL: string | null;
  queryData: Record<string, any>[];
  chartConfig: any | null;
  chartType: string | null;
  analysisReport: string | null;
  searchResults: SearchResultItem[] | null;
  isEmptyResult: boolean;
  errorMessage: string | null;
  analysisStartTime: number | null;
  analysisEndTime: number | null;
}

/**
 * Chat message for conversation flow
 */
export interface ChatMessage {
  id: string;
  role: 'user' | 'agent';
  content: string;
  timestamp: number;
  analysisState?: AnalysisSnapshot;
}

/**
 * Datasource connection response
 */
export interface DatasourceConnection {
  id: number;
  name: string;
  type: string;
  subType?: string;
  status: string;
  host?: string;
  port?: number;
  database?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}
