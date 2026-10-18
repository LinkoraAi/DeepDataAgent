import type { TimelineItem } from './streamingReducer';
import type { SessionStatusWire } from '../types';

/**
 * 会话工作区纯逻辑层（无 Vue / Pinia / DOM 依赖，全部可单测）。
 * <p>把"事件时间线 → 界面可消费派生数据"的判定收敛到本文件：token 聚合、工具审批状态判定、
 * 会话状态事件映射、调试视图类型收集等。store 与组件只负责编排与渲染，判定口径在此单一真相源。</p>
 */

/** token 计量聚合结果（In = 输入 token 累计，Out = 输出 token 累计）。 */
export interface TokenUsage {
  /** 输入 token 累计 */
  in: number;
  /** 输出 token 累计 */
  out: number;
}

/** 工具调用事件的类型全集（与会话后端 ChatEventType.TOOL_USE_TYPES 同口径 + 自定义工具）。 */
export const TOOL_USE_TYPES: readonly string[] = [
  'agent.tool_use',
  'agent.mcp_tool_use',
  'agent.custom_tool_use',
];

/** 工具结果事件的类型全集（内置 / MCP / 自定义，与 TOOL_USE_TYPES 按 tool_use_id 配对）。 */
export const TOOL_RESULT_TYPES: readonly string[] = [
  'agent.tool_result',
  'agent.mcp_tool_result',
  'user.tool_result',
  'user.custom_tool_result',
];

/** HITL 需人工审批的权限策略词汇（后端 permission_policy=always_ask）。 */
export const ALWAYS_ASK_POLICY = 'always_ask';

/** 已允许裁决词汇（user.tool_confirmation.result）。 */
export const CONFIRM_ALLOW = 'allow';

/** 已拒绝裁决词汇（user.tool_confirmation.result）。 */
export const CONFIRM_DENY = 'deny';

/** 会话状态事件类型 → 对外四态映射（含 rescheduled 别名归一到 rescheduling）。 */
const STATUS_EVENT_MAP: Record<string, SessionStatusWire> = {
  'session.status_idle': 'idle',
  'session.status_running': 'running',
  'session.status_rescheduled': 'rescheduling',
  'session.status_rescheduling': 'rescheduling',
  'session.status_terminated': 'terminated',
};

/** 会话错误事件类型。 */
export const SESSION_ERROR_TYPE = 'session.error';

/** 模型调用结束（token 计量下沉）事件类型。 */
export const SPAN_MODEL_REQUEST_END_TYPE = 'span.model_request_end';

/** 用户消息事件类型。 */
export const USER_MESSAGE_TYPE = 'user.message';

/** 智能体消息事件类型。 */
export const AGENT_MESSAGE_TYPE = 'agent.message';

/** 思考事件类型。 */
export const AGENT_THINKING_TYPE = 'agent.thinking';

/** 产物交付事件类型。 */
export const ARTIFACT_DELIVERED_TYPE = 'agent.artifact_delivered';

/** 工具调用确认回显事件类型。 */
export const TOOL_CONFIRMATION_TYPE = 'user.tool_confirmation';

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 取数值字段（非数字 / 非法一律回落 0，避免脏计量数据污染聚合）。 */
function numberOf(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * 会话状态事件类型 → 四态（非状态事件返回 null）。
 * @param type 事件类型（如 session.status_running）
 * @returns 对应对外状态；无匹配为 null
 */
export function statusFromEvent(type: string | null | undefined): SessionStatusWire | null {
  if (!type) {
    return null;
  }
  return STATUS_EVENT_MAP[type] ?? null;
}

/**
 * 聚合 token 计量：遍历时间线中 buffered 的 span.model_request_end 事件，
 * 读取后端真实键名 {@code model_usage.input_tokens} / {@code model_usage.output_tokens} 求和。
 * <p>流式占位（streaming=true）不含计量字段，一律跳过；同一 evt_ id 由 reducer 幂等去重，
 * 故回放与实时重合不会重复累加。</p>
 * @param items 时间线
 * @returns In/Out 累计
 */
export function aggregateTokenUsage(items: readonly TimelineItem[]): TokenUsage {
  let input = 0;
  let output = 0;
  for (const item of items) {
    if (item.streaming || item.type !== SPAN_MODEL_REQUEST_END_TYPE) {
      continue;
    }
    const usage = item.data.model_usage;
    if (isPlainObject(usage)) {
      input += numberOf(usage, 'input_tokens');
      output += numberOf(usage, 'output_tokens');
    }
  }
  return { in: input, out: output };
}

/**
 * 从智能体冻结快照解析"工具名 → 权限策略"映射（宽松读取 {@code tools[].configs[]}）。
 * <p>快照键集随版本演进，故全程宽松防御：非数组 / 缺字段一律跳过；custom 工具无 configs 不参与。</p>
 * @param agent 会话内嵌的智能体快照（Record 宽松类型）
 * @returns 工具名 → permission_policy 映射
 */
export function resolveToolPolicies(agent: Record<string, unknown> | null | undefined): Record<string, string> {
  const policies: Record<string, string> = {};
  const tools = agent?.tools;
  if (!Array.isArray(tools)) {
    return policies;
  }
  for (const entry of tools) {
    if (!isPlainObject(entry)) {
      continue;
    }
    const configs = entry.configs;
    if (!Array.isArray(configs)) {
      continue;
    }
    for (const config of configs) {
      if (!isPlainObject(config)) {
        continue;
      }
      const name = typeof config.name === 'string' ? config.name : '';
      const policy = typeof config.permission_policy === 'string' ? config.permission_policy : '';
      if (name && policy) {
        policies[name] = policy;
      }
    }
  }
  return policies;
}

/**
 * 建立"tool_use_id → 结果事件"索引，供工具卡片回填结果（含 is_error / output）。
 * @param items 时间线
 * @returns tool_use_id → TimelineItem 映射（后者覆盖前者，理论唯一）
 */
export function indexToolResults(items: readonly TimelineItem[]): Map<string, TimelineItem> {
  const map = new Map<string, TimelineItem>();
  for (const item of items) {
    if (item.streaming || !TOOL_RESULT_TYPES.includes(item.type)) {
      continue;
    }
    const toolUseId = typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
    if (toolUseId) {
      map.set(toolUseId, item);
    }
  }
  return map;
}

/**
 * 建立"tool_use_id → 确认回显事件"索引，供审批卡片回显裁决结果。
 * @param items 时间线
 * @returns tool_use_id → TimelineItem 映射
 */
export function indexToolConfirmations(items: readonly TimelineItem[]): Map<string, TimelineItem> {
  const map = new Map<string, TimelineItem>();
  for (const item of items) {
    if (item.streaming || item.type !== TOOL_CONFIRMATION_TYPE) {
      continue;
    }
    const toolUseId = typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
    if (toolUseId) {
      map.set(toolUseId, item);
    }
  }
  return map;
}

/**
 * 从工具调用条目取工具名（name 缺失回落空串，供策略查找与展示）。
 * @param item 工具调用时间线条目
 * @returns 工具名
 */
export function toolNameOf(item: TimelineItem): string {
  return typeof item.data.name === 'string' ? item.data.name : '';
}

/**
 * 从工具调用条目取 tool_use_id（缺失回落空串）。
 * @param item 工具调用时间线条目
 * @returns tool_use_id
 */
export function toolUseIdOf(item: TimelineItem): string {
  return typeof item.data.tool_use_id === 'string' ? item.data.tool_use_id : '';
}

/**
 * 判定工具卡片是否应展示"允许 / 拒绝"操作（HITL 核心判据，纯函数）。
 * <p>须同时满足：该工具在会话快照策略为 always_ask、会话当前 running、
 * 尚无对应 tool_result 回填、尚无确认回显（用户已裁决则不再展示按钮）。</p>
 * @param options 判据入参
 * @param options.policy 该工具权限策略（来自 resolveToolPolicies，可能为空）
 * @param options.status 会话当前状态
 * @param options.hasResult 是否已收到配对工具结果
 * @param options.hasConfirmation 是否已收到确认回显
 * @returns 是否待审批
 */
export function isAwaitingApproval(options: {
  policy: string | undefined;
  status: SessionStatusWire | string;
  hasResult: boolean;
  hasConfirmation: boolean;
}): boolean {
  return (
    options.policy === ALWAYS_ASK_POLICY &&
    options.status === 'running' &&
    !options.hasResult &&
    !options.hasConfirmation
  );
}

/**
 * 提取会话错误文案（{@code session.error} 的 {@code error.message}，缺失回落通用文案）。
 * @param item 错误事件条目
 * @returns 可展示的错误信息
 */
export function errorTextOf(item: TimelineItem): string {
  const error = item.data.error;
  if (isPlainObject(error)) {
    const message = typeof error.message === 'string' ? error.message.trim() : '';
    if (message) {
      return message;
    }
  }
  return '智能体执行失败';
}

/**
 * 收集时间线中出现过的事件类型（去重 + 字典序，供调试视图过滤下拉）。
 * @param items 时间线
 * @returns 类型列表（不含空类型）
 */
export function collectEventTypes(items: readonly TimelineItem[]): string[] {
  const set = new Set<string>();
  for (const item of items) {
    if (item.type) {
      set.add(item.type);
    }
  }
  return Array.from(set).sort();
}

/**
 * 判断会话是否只读（已归档或已终止：禁发消息、禁挂载操作）。
 * @param session 会话对象（含 archived_at / status）
 * @returns 是否只读
 */
export function isSessionReadOnly(session: { archived_at?: string | null; status?: string } | null | undefined): boolean {
  if (!session) {
    return false;
  }
  return Boolean(session.archived_at) || session.status === 'terminated';
}

/**
 * 聚合 content[].text 全文（智能体 / 用户消息 buffered 帧的文本块拼接，非文本块忽略）。
 * @param content 内容块数组
 * @returns 拼接后的纯文本
 */
export function aggregateContentText(content: unknown): string {
  if (!Array.isArray(content)) {
    return '';
  }
  const parts: string[] = [];
  for (const block of content) {
    if (isPlainObject(block) && block.type === 'text' && typeof block.text === 'string') {
      parts.push(block.text);
    }
  }
  return parts.join('');
}

/**
 * 把流式占位的 {@code {text}} 或 buffered 完整帧统一解析为可展示的纯文本。
 * <p>对话视图按"是否有 content 字段"分流：buffered agent.message 用 content[]，流式增量用 data.text。</p>
 * @param item 时间线条目
 * @returns 展示文本
 */
export function messageTextOf(item: TimelineItem): string {
  if (item.streaming) {
    return typeof item.data.text === 'string' ? item.data.text : '';
  }
  return aggregateContentText(item.data.content);
}
