import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import TimelineToolCallItem from '../components/cards/TimelineToolCallItem.vue';
import type { ToolCallTimelineItem } from '../types';

describe('TimelineToolCallItem', () => {
  const createMockItem = (overrides: Partial<ToolCallTimelineItem> = {}): ToolCallTimelineItem => ({
    id: 'tool-1',
    type: 'tool_call',
    toolName: 'execute_sql',
    status: 'success',
    timestamp: Date.now(),
    startTime: Date.now(),
    endTime: Date.now() + 1000,
    input: '{"sql": "SELECT * FROM users"}',
    result: '查询成功，返回 10 条记录',
    ...overrides,
  });

  const globalStubs = {
    't-loading': { template: '<div />' },
  };

  // ─── 渲染测试 ───────────────────────────────────────────

  it('should_renderRootContainer_when_mounted', () => {
    // given
    const item = createMockItem();

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then
    expect(wrapper.find('.timeline-tool-call-item').exists()).toBe(true);
  });

  it('should_notRenderHeaderElements_when_mounted', () => {
    // given
    const item = createMockItem();

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then - header 信息（工具名、图标、状态、耗时）已移至父级 TimelineRound 摘要行
    expect(wrapper.find('.timeline-tool-call-item__header').exists()).toBe(false);
    expect(wrapper.find('.timeline-tool-call-item__name').exists()).toBe(false);
    expect(wrapper.find('.timeline-tool-call-item__icon').exists()).toBe(false);
    expect(wrapper.find('.timeline-tool-call-item__status').exists()).toBe(false);
    expect(wrapper.find('.timeline-tool-call-item__duration').exists()).toBe(false);
  });

  // ─── 详情显示 ───────────────────────────────────────────

  it('should_displayInputSection_when_inputExists', () => {
    // given
    const item = createMockItem({ input: '{"sql": "SELECT 1"}' });

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then
    expect(wrapper.find('.timeline-tool-call-item__details').exists()).toBe(true);
    const labels = wrapper.findAll('.timeline-tool-call-item__label');
    const hasInputLabel = labels.some(label => label.text().includes('输入参数'));
    expect(hasInputLabel).toBe(true);
    expect(wrapper.find('.timeline-tool-call-item__code').exists()).toBe(true);
  });

  it('should_displayResultSection_when_resultExists', () => {
    // given
    const item = createMockItem({ result: '执行成功' });

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then
    expect(wrapper.find('.timeline-tool-call-item__details').exists()).toBe(true);
    const labels = wrapper.findAll('.timeline-tool-call-item__label');
    const hasResultLabel = labels.some(label => label.text().includes('执行结果'));
    expect(hasResultLabel).toBe(true);
  });

  it('should_renderBothSections_when_inputAndResultExist', () => {
    // given
    const item = createMockItem({ input: '{"test": 1}', result: '结果' });

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then
    expect(wrapper.find('.timeline-tool-call-item__details').exists()).toBe(true);
    expect(wrapper.findAll('.timeline-tool-call-item__section')).toHaveLength(2);
  });

  it('should_notDisplayDetails_when_inputAndResultAreEmpty', () => {
    // given
    const item = createMockItem({ input: undefined, result: undefined });

    // when
    const wrapper = mount(TimelineToolCallItem, {
      props: { item },
      global: { stubs: globalStubs },
    });

    // then
    expect(wrapper.find('.timeline-tool-call-item__details').exists()).toBe(false);
  });
});
