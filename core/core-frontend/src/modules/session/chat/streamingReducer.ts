import type { NormalizedEvent } from './normalizeChatEvent';

/**
 * 会话事件时间线归约器（纯函数，可单测）。
 * 规则：
 * - 按事件 id 幂等去重（历史回放与实时推送可能重合）；
 * - event_start 登记流式占位、event_delta 聚合文本；
 * - buffered 完整帧到达后替换同名流式占位（最终一致）；
 * - 时间线按"首次出现顺序"输出，重排不改变已定序。
 */

/** 时间线条目 */
export interface TimelineItem {
  id: string;
  type: string;
  /** 仍在增量输出中（尚未收到 buffered 完整帧） */
  streaming: boolean;
  /** buffered 完整事件载荷；流式占位时为 { text } */
  data: Record<string, unknown>;
}

/** 归约器内部状态 */
export interface ReducerState {
  order: string[];
  items: Map<string, TimelineItem>;
}

export function createReducerState(): ReducerState {
  return { order: [], items: new Map() };
}

function ensureOrder(state: ReducerState, id: string): void {
  if (!state.items.has(id)) {
    state.order.push(id);
  }
}

/** 应用一条归一化事件（就地修改状态；null 事件忽略） */
export function applyEvent(state: ReducerState, evt: NormalizedEvent): void {
  if (!evt) {
    return;
  }
  if (evt.kind === 'start') {
    ensureOrder(state, evt.id);
    if (!state.items.has(evt.id)) {
      state.items.set(evt.id, { id: evt.id, type: evt.type, streaming: true, data: { text: '' } });
    }
    return;
  }
  if (evt.kind === 'delta') {
    const existing = state.items.get(evt.id);
    if (existing && existing.streaming) {
      const text = typeof existing.data.text === 'string' ? existing.data.text : '';
      existing.data = { ...existing.data, text: text + evt.text };
    } else if (!existing) {
      // 未见 start 的孤儿 delta：以未知类型占位，等待 buffered 覆盖
      ensureOrder(state, evt.id);
      state.items.set(evt.id, { id: evt.id, type: '', streaming: true, data: { text: evt.text } });
    }
    return;
  }
  // buffered：完整事件覆盖（含替换流式占位）
  const previous = state.items.get(evt.id);
  ensureOrder(state, evt.id);
  state.items.set(evt.id, {
    id: evt.id,
    type: typeof evt.event.type === 'string' ? evt.event.type : previous?.type ?? '',
    streaming: false,
    data: evt.event,
  });
}

/** 输出时间线（按首次出现顺序） */
export function selectTimeline(state: ReducerState): TimelineItem[] {
  return state.order.map((id) => state.items.get(id)).filter((item): item is TimelineItem => Boolean(item));
}

/** 取最新事件 id（SSE 重连 Last-Event-ID 用；仅认 buffered 帧的 evt_ id） */
export function selectLastEventId(state: ReducerState): string | null {
  for (let i = state.order.length - 1; i >= 0; i -= 1) {
    const item = state.items.get(state.order[i]);
    if (item && !item.streaming) {
      return item.id;
    }
  }
  return null;
}
