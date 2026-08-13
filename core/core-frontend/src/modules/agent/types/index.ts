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
  /** 消息角色：谁说的（user / assistant / system / tool） */
  role: 'user' | 'assistant' | 'system' | 'tool';
  /** 消息类型：哪类内容（MESSAGE / THINKING / TOOL_CALL / TOOL_RESULT / ERROR），透出后端 messageType */
  type?: 'MESSAGE' | 'THINKING' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ERROR';
  content: string;
  toolCalls?: string;
  toolResult?: string;
  /** 工具调用 ID（仅 TOOL_CALL / TOOL_RESULT 消息携带，同一调用的调用与结果两条消息取值一致，用于前端配对展示） */
  toolCallId?: string;
  /** 消息状态：COMPLETED / IN_PROGRESS / FAILED（老数据缺失时回退现有判定） */
  status?: string;
  createdAt: string;
}

/**
 * 统一内容流项状态
 */
export type ContentItemStatus = 'in_progress' | 'completed' | 'failed';

/**
 * 统一内容流项
 * <p>对话内所有消息内容（思考、工具调用、报告）的统一模型，严格按 seq 时序渲染，
 * 以 status 驱动自动展开/折叠，不再通过差异化卡片区分类型。</p>
 */
export interface ContentItem {
  /** 唯一标识 */
  id: string;
  /** 顺序号（自增，决定内容流渲染顺序） */
  seq: number;
  /** 项类型：思考 / 工具调用 / 工具结果 / 报告 */
  type: 'thinking' | 'tool_call' | 'tool_result' | 'report';
  /** 状态：进行中 / 完成 / 失败 */
  status: ContentItemStatus;
  /** 文本内容（thinking/report 使用；tool_call 为输入参数） */
  content?: string;
  /** 工具名称（tool_call / tool_result 使用） */
  toolName?: string;
  /** 工具调用 ID（tool_call / tool_result 使用，用于交错事件时精确定位） */
  toolCallId?: string;
  /** 工具输入参数（tool_call，JSON 字符串） */
  input?: string;
  /** 工具执行结果（tool_call / tool_result） */
  result?: string;
  /** 开始时间戳 */
  startTime: number;
  /** 结束时间戳 */
  endTime?: number;
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
  /** 统一内容流（严格按接收时序） */
  contentItems: ContentItem[];
  /** 下一内容项序号（自增，保证渲染顺序） */
  contentSeq: number;
  currentSQL: string | null;
  queryData: Record<string, any>[];
  chartConfig: any | null;
  chartType: string | null;
  /** 分析报告全文（内容流中 report 项的派生汇总，兼容保留） */
  analysisReport: string | null;
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
  /** 统一内容流（按 seq 时序） */
  contentItems: ContentItem[];
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
