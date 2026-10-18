/**
 * 会话域线格式类型（后端 runtime 域，snake_case 保真，勿做命名转换）。
 */

/** 内容块（入站/出站共用） */
export type ContentBlock =
  | { type: 'text'; text: string }
  | {
      type: 'image';
      source:
        | { type: 'base64'; media_type: string; data: string }
        | { type: 'url'; url: string }
        | { type: 'file'; file_id: string };
      width?: number;
      height?: number;
    };

/** 会话状态（对外四态） */
export type SessionStatusWire = 'idle' | 'running' | 'rescheduling' | 'terminated';

/** 会话资源项（挂载文件） */
export interface SessionResourceWire {
  id: string;
  type: string;
  file_id?: string;
  mount_path?: string;
  created_at?: string;
  updated_at?: string;
}

/** 会话对象（SessionResponse；agent 为智能体冻结快照，键集随版本演进按宽松类型处理） */
export interface SessionWire {
  id: string;
  type: 'session';
  agent: Record<string, unknown> & { id?: string; name?: string; version?: number };
  environment_id: string;
  status: SessionStatusWire;
  title: string | null;
  metadata: Record<string, unknown>;
  resources: SessionResourceWire[];
  deployment_id: string | null;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 创建会话请求（废止字段 vaults/memory_store_ids/environment/delta_flush_interval_ms 绝不提交） */
export interface CreateSessionPayload {
  agent: string | { id: string; type: 'agent'; version?: number };
  environment_id: string;
  title?: string;
  resources?: Array<{ type: 'file'; file_id: string; mount_path?: string }>;
  metadata?: Record<string, unknown>;
  environment_variables?: Record<string, string>;
}

/** 线程对象（单智能体恒一条主线程） */
export interface ThreadWire {
  id: string;
  type: 'session_thread';
  session_id: string;
  parent_thread_id: string | null;
  agent: Record<string, unknown>;
  status: string;
  archived_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 入站事件（扁平结构，禁止 payload 嵌套） */
export type OutboundEvent =
  | { type: 'user.message'; content: ContentBlock[] }
  | { type: 'user.interrupt'; session_thread_id?: string }
  | { type: 'user.tool_confirmation'; tool_use_id: string; result: 'allow' | 'deny'; deny_message?: string }
  | { type: 'user.tool_result'; tool_use_id: string; content: ContentBlock[]; is_error: boolean };

/** 出站事件的宽松线格式（后端 @JsonAnyGetter 顶层展开，字段随类型不同） */
export interface ChatEventWire {
  id: string;
  type: string;
  processed_at?: string;
  content?: ContentBlock[];
  name?: string;
  input?: unknown;
  tool_use_id?: string;
  is_error?: boolean;
  stop_reason?: { type: string; event_ids?: string[] };
  error?: unknown;
  /** 其余类型自有字段 */
  [key: string]: unknown;
}

/** 会话列表查询参数 */
export interface SessionListParams {
  agent_id?: string;
  statuses?: string[];
  include_archived?: boolean;
  created_at_gte?: string;
  created_at_lte?: string;
  limit?: number;
  after_id?: string;
}
