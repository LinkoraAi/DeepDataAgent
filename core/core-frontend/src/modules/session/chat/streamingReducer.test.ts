// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { normalizeChatEvent } from './normalizeChatEvent';
import { applyEvent, createReducerState, selectLastEventId, selectTimeline } from './streamingReducer';

describe('normalizeChatEvent', () => {
  it('should 解析 buffered 完整事件', () => {
    const evt = normalizeChatEvent({ event: 'agent.message', id: 'evt_1', data: '{"id":"evt_1","type":"agent.message","content":[]}' });
    expect(evt).toEqual({ kind: 'buffered', id: 'evt_1', event: { id: 'evt_1', type: 'agent.message', content: [] } });
  });

  it('should 解析 start 与 delta 帧', () => {
    expect(normalizeChatEvent({ event: 'event_start', id: 'evt_2', data: '{"type":"event_start","event":{"id":"evt_2","type":"agent.message"}}' }))
      .toEqual({ kind: 'start', id: 'evt_2', type: 'agent.message' });
    expect(normalizeChatEvent({ event: 'event_delta', id: 'evt_2', data: '{"type":"event_delta","event_id":"evt_2","delta":"你好"}' }))
      .toEqual({ kind: 'delta', id: 'evt_2', text: '你好' });
  });

  it('should 丢弃坏帧_when 非法 JSON 或缺少必需字段', () => {
    expect(normalizeChatEvent({ event: 'x', id: '', data: 'not-json' })).toBeNull();
    expect(normalizeChatEvent({ event: 'x', id: '', data: '{"foo":1}' })).toBeNull();
    expect(normalizeChatEvent({ event: 'event_delta', id: '', data: '{"type":"event_delta"}' })).toBeNull();
  });
});

describe('streamingReducer', () => {
  it('should 按 id 幂等去重_when 回放与实时重合', () => {
    const state = createReducerState();
    const evt = { kind: 'buffered' as const, id: 'evt_a', event: { id: 'evt_a', type: 'user.message' } };
    applyEvent(state, evt);
    applyEvent(state, evt);
    expect(selectTimeline(state)).toHaveLength(1);
  });

  it('should 聚合 delta 文本并保留首次顺序', () => {
    const state = createReducerState();
    applyEvent(state, { kind: 'start', id: 'evt_s', type: 'agent.message' });
    applyEvent(state, { kind: 'delta', id: 'evt_s', text: '你' });
    applyEvent(state, { kind: 'delta', id: 'evt_s', text: '好' });
    const timeline = selectTimeline(state);
    expect(timeline[0].streaming).toBe(true);
    expect(timeline[0].data.text).toBe('你好');
  });

  it('should 完整帧替换流式占位_when buffered 后到', () => {
    const state = createReducerState();
    applyEvent(state, { kind: 'start', id: 'evt_s', type: 'agent.message' });
    applyEvent(state, { kind: 'delta', id: 'evt_s', text: '部分' });
    applyEvent(state, { kind: 'buffered', id: 'evt_s', event: { id: 'evt_s', type: 'agent.message', content: [{ type: 'text', text: '完整内容' }] } });
    const timeline = selectTimeline(state);
    expect(timeline).toHaveLength(1);
    expect(timeline[0].streaming).toBe(false);
    expect(timeline[0].data.content).toBeDefined();
  });

  it('should 处理 buffered 先行顺序_when 完整帧先于残留 delta', () => {
    const state = createReducerState();
    applyEvent(state, { kind: 'buffered', id: 'evt_s', event: { id: 'evt_s', type: 'agent.message' } });
    applyEvent(state, { kind: 'delta', id: 'evt_s', text: '迟到增量' });
    const timeline = selectTimeline(state);
    expect(timeline).toHaveLength(1);
    expect(timeline[0].streaming).toBe(false);
  });

  it('should 追踪最后完整事件 id 作重连游标', () => {
    const state = createReducerState();
    applyEvent(state, { kind: 'buffered', id: 'evt_1', event: { id: 'evt_1', type: 'session.status_running' } });
    applyEvent(state, { kind: 'start', id: 'evt_2', type: 'agent.message' });
    expect(selectLastEventId(state)).toBe('evt_1');
    applyEvent(state, { kind: 'buffered', id: 'evt_2', event: { id: 'evt_2', type: 'agent.message' } });
    expect(selectLastEventId(state)).toBe('evt_2');
  });
});
