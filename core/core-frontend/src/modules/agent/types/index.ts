/**
 * Session response
 */
export interface Session {
  id: string;
  title: string;
  datasourceId: number;
  modelConfigId: number;
  status: string;
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
 * 建议追问项
 */
export interface Suggestion {
  /** 建议问题文本 */
  text: string;
  /** 建议类型：下钻/对比/预测/探索 */
  type?: 'drill' | 'compare' | 'predict' | 'explore';
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
  /** 后端是否正在分析中（刷新恢复订阅时据此判断） */
  running: boolean;
  lastMessageAt: string;
  createdAt: string;
}

/**
 * Message response
 */
export interface Message {
  id: number;
  sessionId: string;
  /** 对话轮次 ID（用于按轮次分组，同一轮次的多条消息共享同一 dialogueId） */
  dialogueId?: number;
  role: 'user' | 'assistant' | 'system' | 'thinking' | 'tool';
  content: string;
  toolCalls?: string;
  toolResult?: string;
  createdAt: string;
}

/**
 * Tool call item
 */
export interface ToolCallItem {
  name: string;
  status: 'running' | 'success' | 'error';
  startTime: number;
  endTime?: number;
  /** 工具调用的输入参数(JSON字符串) */
  input?: string;
  result?: string;
}

/**
 * Timeline item base interface
 * 时间线项基础接口
 */
export interface BaseTimelineItem {
  /** 唯一标识 */
  id: string;
  /** 时间戳 */
  timestamp: number;
  /** 项类型 */
  type: 'thinking' | 'tool_call' | 'report';
}

/**
 * Thinking timeline item
 * 思考时间线项
 */
export interface ThinkingTimelineItem extends BaseTimelineItem {
  type: 'thinking';
  /** 思考内容 */
  content: string;
  /** 是否正在流式输出 */
  isStreaming: boolean;
}

/**
 * Tool call timeline item
 * 工具调用时间线项
 */
export interface ToolCallTimelineItem extends BaseTimelineItem {
  type: 'tool_call';
  /** 工具名称 */
  toolName: string;
  /** 执行状态 */
  status: 'running' | 'success' | 'error';
  /** 工具输入参数 */
  input?: string;
  /** 工具执行结果 */
  result?: string;
  /** 开始时间戳 */
  startTime: number;
  /** 结束时间戳 */
  endTime?: number;
}

/**
 * Report timeline item
 * 报告时间线项
 */
export interface ReportTimelineItem extends BaseTimelineItem {
  type: 'report';
  /** 报告内容 */
  content: string;
  /** 是否正在流式输出 */
  isStreaming: boolean;
}

/**
 * Timeline item union type
 * 时间线项联合类型
 */
export type TimelineItem = ThinkingTimelineItem | ToolCallTimelineItem | ReportTimelineItem;

/**
 * ReAct 轮次：一次"思考 + 后续工具调用"的完整决策循环
 */
export interface ReActRound {
  /** 唯一标识 */
  id: string;
  /** 轮次开始时间戳 */
  startTime: number;
  /** 轮次结束时间戳（最后一个工具完成时，或 completeAnalysis 时回填） */
  endTime?: number;
  /** 本轮思考项（content 可能为空，兜底轮次场景） */
  thinking: ThinkingTimelineItem;
  /** 本轮工具调用（按时间顺序，可能为空） */
  toolCalls: ToolCallTimelineItem[];
  /** 是否正在流式输出（thinking.isStreaming 或任意 toolCall.status=running） */
  isActive: boolean;
  /** 完成后是否折叠（默认 true，用户可手动展开回看） */
  isCollapsed?: boolean;
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
  /** ReAct 轮次数组（替代扁平 timeline） */
  rounds: ReActRound[];
  /** 当前正在流式输出的轮次 ID */
  currentRoundId: string | null;
  /** 时间线是否展开 */
  isTimelineExpanded: boolean;
  currentSQL: string | null;
  queryData: Record<string, any>[];
  chartConfig: any | null;
  chartType: string | null;
  analysisReport: string | null;
  /** 时间线内的报告项（流式状态） */
  report: ReportTimelineItem | null;
  searchResults: SearchResultItem[] | null;
  isEmptyResult: boolean;
  errorMessage: string | null;
  analysisStartTime: number | null;
  /** 建议追问列表 */
  suggestions: Suggestion[];
  /** 分析结束时间戳（completeAnalysis 时记录） */
  analysisEndTime: number | null;
}

/**
 * Analysis snapshot for agent message
 */
export interface AnalysisSnapshot {
  /** 是否正在分析中 */
  isAnalyzing?: boolean;
  /** ReAct 轮次数组 */
  rounds: ReActRound[];
  /** 时间线是否展开 */
  isTimelineExpanded?: boolean;
  currentSQL: string | null;
  queryData: Record<string, any>[];
  chartConfig: any | null;
  chartType: string | null;
  analysisReport: string | null;
  /** 时间线内的报告项（可选，用于历史兼容） */
  report?: ReportTimelineItem | null;
  searchResults: SearchResultItem[] | null;
  isEmptyResult: boolean;
  errorMessage: string | null;
  analysisStartTime: number | null;
  analysisEndTime: number | null;
  /** 建议追问列表 */
  suggestions: Suggestion[];
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
  username?: string;
  /** PostgreSQL 等数据源的 schema，默认 public */
  schema?: string;
  /** 密码掩码文本，编辑时回显用（如 "****...****"），null 表示未配置密码 */
  maskedPassword?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * AgentEvent - AgentScope 2.0 事件类型名称
 * 后端 SSE 通过 event: <AgentEventType> 推送事件
 */
export type AgentEventType =
  | 'AGENT_START'
  | 'THINKING_BLOCK_START'
  | 'THINKING_BLOCK_DELTA'
  | 'THINKING_BLOCK_END'
  | 'MODEL_CALL_START'
  | 'MODEL_CALL_END'
  | 'TOOL_CALL_START'
  | 'TOOL_CALL_DELTA'
  | 'TOOL_CALL_END'
  | 'TOOL_RESULT_START'
  | 'TOOL_RESULT_TEXT_DELTA'
  | 'TOOL_RESULT_END'
  | 'TEXT_BLOCK_START'
  | 'TEXT_BLOCK_DELTA'
  | 'TEXT_BLOCK_END'
  | 'AGENT_RESULT'
  | 'AGENT_END'
  | 'EXCEED_MAX_ITERS'
  | 'ERROR';

/**
 * AgentEvent - AgentScope 2.0 事件 JSON 格式
 * 包含 type 字段，其余字段根据具体事件类型可变
 */
export interface AgentEvent {
  /** 事件类型 */
  type: AgentEventType;
  /** 回复 ID（AgentScope 内部） */
  replyId?: string;
  /** 块 ID（ThinkingBlock/TextBlock 增量时使用） */
  blockId?: string;
  /** 增量文本（ThinkingBlockDelta/TextBlockDelta/ToolCallDelta/ToolResultTextDelta） */
  delta?: string;
  /** 工具调用 ID */
  toolCallId?: string;
  /** 工具名称 */
  toolCallName?: string;
  /** 工具执行状态（ToolResultEnd） */
  state?: string;
  /** Agent 最终结果（AgentResult） */
  result?: { textContent: string; [key: string]: any };
  /** 其他可能字段 */
  [key: string]: any;
}
