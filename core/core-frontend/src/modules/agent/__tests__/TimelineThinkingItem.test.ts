import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import TimelineThinkingItem from '../components/cards/TimelineThinkingItem.vue';
import type { ThinkingTimelineItem } from '../types';

describe('TimelineThinkingItem', () => {
  // Mock requestAnimationFrame to prevent infinite recursion
  const originalRAF = globalThis.requestAnimationFrame;
  const originalCAF = globalThis.cancelAnimationFrame;
  let rafCallbacks: FrameRequestCallback[] = [];

  beforeEach(() => {
    rafCallbacks = [];
    // Store callbacks but don't execute immediately to avoid infinite recursion
    globalThis.requestAnimationFrame = vi.fn((cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    globalThis.cancelAnimationFrame = vi.fn(() => {
      rafCallbacks = [];
    });
  });

  afterEach(() => {
    globalThis.requestAnimationFrame = originalRAF;
    globalThis.cancelAnimationFrame = originalCAF;
    rafCallbacks = [];
  });

  /** Helper to flush one RAF callback */
  function flushRAF() {
    const cbs = [...rafCallbacks];
    rafCallbacks = [];
    cbs.forEach(cb => cb(0));
  }

  const createMockItem = (overrides: Partial<ThinkingTimelineItem> = {}): ThinkingTimelineItem => ({
    id: 'thinking-1',
    type: 'thinking',
    content: '这是思考内容',
    timestamp: Date.now(),
    isStreaming: false,
    ...overrides,
  });

  it('should_renderThinkingContent_when_mounted', () => {
    // given
    const item = createMockItem({ content: '测试思考内容' });

    // when
    const wrapper = mount(TimelineThinkingItem, { props: { item } });

    // then
    expect(wrapper.text()).toContain('测试思考内容');
  });

  it('should_displayStreamingCursor_when_isStreaming', () => {
    // given
    const item = createMockItem({ isStreaming: true });

    // when
    const wrapper = mount(TimelineThinkingItem, { props: { item } });

    // then
    expect(wrapper.find('.streaming-cursor').exists()).toBe(true);
  });

  it('should_hideStreamingCursor_when_notStreaming', () => {
    // given
    const item = createMockItem({ isStreaming: false });

    // when
    const wrapper = mount(TimelineThinkingItem, { props: { item } });

    // then
    expect(wrapper.find('.streaming-cursor').exists()).toBe(false);
  });

  it('should_renderContentStructure_when_mounted', () => {
    // given
    const item = createMockItem();

    // when
    const wrapper = mount(TimelineThinkingItem, { props: { item } });

    // then - 新结构仅包含 content 与 text 元素（header 已移除，由父级 TimelineRound 统一展示）
    expect(wrapper.find('.timeline-thinking-item').exists()).toBe(true);
    expect(wrapper.find('.timeline-thinking-item__content').exists()).toBe(true);
    expect(wrapper.find('.timeline-thinking-item__text').exists()).toBe(true);
    // 不应存在旧版 header 相关元素
    expect(wrapper.find('.timeline-thinking-item__header').exists()).toBe(false);
    expect(wrapper.find('.timeline-thinking-item__icon').exists()).toBe(false);
    expect(wrapper.find('.timeline-thinking-item__status').exists()).toBe(false);
    expect(wrapper.find('.timeline-thinking-item__title').exists()).toBe(false);
  });

  it('should_renderEmptyContent_when_contentIsEmpty', () => {
    // given - 空思考轮次（content 为空字符串）
    const item = createMockItem({ content: '' });

    // when
    const wrapper = mount(TimelineThinkingItem, { props: { item } });

    // then - 直接输出空内容，不显示占位文本
    expect(wrapper.find('.timeline-thinking-item__text').exists()).toBe(true);
    expect(wrapper.find('.timeline-thinking-item__text').text()).toBe('');
  });

  it('should_updateContent_when_contentChanges', async () => {
    // given - mount with initial content
    const initialItem = createMockItem({ content: '初始内容', isStreaming: false });
    const wrapper = mount(TimelineThinkingItem, { props: { item: initialItem } });
    expect(wrapper.text()).toContain('初始内容');

    // when - unmount and remount with new content (simulates parent re-render with new item)
    wrapper.unmount();
    const updatedItem = createMockItem({ id: 'thinking-2', content: '更新后的内容', isStreaming: false });
    const newWrapper = mount(TimelineThinkingItem, { props: { item: updatedItem } });
    await nextTick();

    // then - new mount should display new content
    expect(newWrapper.text()).toContain('更新后的内容');
    newWrapper.unmount();
  });
});
