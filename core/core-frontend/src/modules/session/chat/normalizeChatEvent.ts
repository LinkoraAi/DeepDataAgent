import type { RawSseFrame } from '@/shared/api/sse';

/**
 * SSE 帧 → 归一化事件（防御性校验：坏帧返回 null 丢弃，绝不抛错打断连接）。
 * 后端帧三形态：
 * - buffered：data 为扁平事件 JSON（含 id/type）；
 * - event_start：{type:'event_start', event:{id,type}}；
 * - event_delta：{type:'event_delta', event_id, delta}。
 */
export type NormalizedEvent =
  | { kind: 'buffered'; id: string; event: Record<string, unknown> }
  | { kind: 'start'; id: string; type: string }
  | { kind: 'delta'; id: string; text: string }
  | null;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 归一化一帧；返回 null 表示应丢弃 */
export function normalizeChatEvent(frame: RawSseFrame): NormalizedEvent {
  let data: unknown;
  try {
    data = JSON.parse(frame.data);
  } catch {
    return null;
  }
  if (!isPlainObject(data)) {
    return null;
  }

  if (data.type === 'event_start') {
    const meta = isPlainObject(data.event) ? data.event : null;
    const id = typeof meta?.id === 'string' ? meta.id : frame.id;
    const type = typeof meta?.type === 'string' ? meta.type : '';
    if (!id || !type) {
      return null;
    }
    return { kind: 'start', id, type };
  }

  if (data.type === 'event_delta') {
    const id = typeof data.event_id === 'string' ? data.event_id : frame.id;
    if (!id || typeof data.delta !== 'string') {
      return null;
    }
    return { kind: 'delta', id, text: data.delta };
  }

  // buffered 完整事件
  const id = typeof data.id === 'string' && data.id ? data.id : frame.id;
  const type = typeof data.type === 'string' ? data.type : frame.event;
  if (!id || !type) {
    return null;
  }
  return { kind: 'buffered', id, event: data };
}
