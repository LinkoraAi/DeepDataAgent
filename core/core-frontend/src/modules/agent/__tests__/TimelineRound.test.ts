import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import TimelineRound from '../components/cards/TimelineRound.vue';
import type { ReActRound } from '../types';

describe('TimelineRound - ReAct 轮次渲染', () => {
  function createMockRound(overrides?: Partial<ReActRound>): ReActRound {
    return {
      id: 'round-1',
      startTime: Date.now() - 1000,
      endTime: Date.now(),
      thinking: {
        id: 'thinking-1',
        type: 'thinking',
        timestamp: Date.now() - 1000,
        content: '思考内容',
        isStreaming: false,
      },
      toolCalls: [
        {
          id: 'tool-1',
          type: 'tool_call',
          toolName: 'execute_sql',
          status: 'success',
          timestamp: Date.now(),
          startTime: Date.now() - 500,
          endTime: Date.now(),
          input: '{"sql": "SELECT 1"}',
          result: '{"data": [1]}',
        },
      ],
      isActive: false,
      isCollapsed: true,
      ...overrides,
    };
  }

  const childStubs = {
    TimelineThinkingItem: { template: '<div class="thinking-stub" />' },
    TimelineToolCallItem: { template: '<div class="tool-stub" />' },
  };

  describe('折叠状态', () => {
    it('应渲染摘要行', () => {
      const wrapper = mount(TimelineRound, {
        props: { round: createMockRound(), isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.find('.timeline-round__summary').exists()).toBe(true);
      expect(wrapper.text()).toContain('思考');
      expect(wrapper.text()).toContain('execute_sql');
      expect(wrapper.text()).toContain('✓');
    });

    it('多个工具应用 + 连接', () => {
      const round = createMockRound({
        toolCalls: [
          { id: 't1', type: 'tool_call', toolName: 'tool_a', status: 'success', timestamp: Date.now(), startTime: Date.now(), endTime: Date.now() },
          { id: 't2', type: 'tool_call', toolName: 'tool_b', status: 'success', timestamp: Date.now(), startTime: Date.now(), endTime: Date.now() },
        ],
      });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.text()).toContain('tool_a');
      expect(wrapper.text()).toContain('tool_b');
      expect(wrapper.text()).toContain('+');
    });

    it('无工具调用时应显示"(无工具调用)"', () => {
      const round = createMockRound({ toolCalls: [] });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.text()).toContain('无工具调用');
    });

    it('点击摘要行应切换为展开状态', async () => {
      const wrapper = mount(TimelineRound, {
        props: { round: createMockRound(), isAnalyzing: false },
        global: { stubs: childStubs },
      });

      await wrapper.find('.timeline-round__summary').trigger('click');
      await nextTick();

      expect(wrapper.find('.timeline-round__expanded').exists()).toBe(true);
    });
  });

  describe('展开状态', () => {
    it('应渲染思考内容与工具详情', () => {
      const round = createMockRound({ isActive: true, isCollapsed: false });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.find('.timeline-round__expanded').exists()).toBe(true);
      expect(wrapper.find('.thinking-stub').exists()).toBe(true);
      expect(wrapper.find('.tool-stub').exists()).toBe(true);
    });

    it('应渲染工具连接线', () => {
      const round = createMockRound({ isActive: true, isCollapsed: false });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.find('.timeline-round__tool-connector').exists()).toBe(true);
      expect(wrapper.find('.timeline-round__tool-connector').text()).toContain('└─');
    });

    it('思考内容为空且非流式时不渲染思考区域', () => {
      const round = createMockRound({
        isActive: true,
        isCollapsed: false,
        thinking: {
          id: 'thinking-1',
          type: 'thinking',
          timestamp: Date.now(),
          content: '',
          isStreaming: false,
        },
      });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      expect(wrapper.find('.thinking-stub').exists()).toBe(false);
    });

    it('点击折叠按钮应切换为折叠状态', async () => {
      const round = createMockRound({ isActive: true, isCollapsed: false });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      await wrapper.find('.timeline-round__toggle').trigger('click');
      await nextTick();

      expect(wrapper.find('.timeline-round__summary').exists()).toBe(true);
    });
  });

  describe('自动折叠/展开行为', () => {
    it('isActive 变为 true 时应自动展开', async () => {
      const round = createMockRound({ isActive: false, isCollapsed: true });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: true },
        global: { stubs: childStubs },
      });

      // 模拟 isActive 变为 true
      await wrapper.setProps({ round: { ...round, isActive: true, isCollapsed: false } });
      await nextTick();

      expect(wrapper.find('.timeline-round__expanded').exists()).toBe(true);
    });

    it('isActive 变为 false 时应自动折叠', async () => {
      const round = createMockRound({ isActive: true, isCollapsed: false });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: true },
        global: { stubs: childStubs },
      });

      // 模拟 isActive 变为 false（完成）
      await wrapper.setProps({ round: { ...round, isActive: false } });
      await nextTick();

      expect(wrapper.find('.timeline-round__summary').exists()).toBe(true);
    });
  });

  describe('边界情况', () => {
    it('空思考轮次（兜底场景）应正常渲染', () => {
      const round = createMockRound({
        isActive: true,
        isCollapsed: false,
        thinking: {
          id: 'thinking-empty',
          type: 'thinking',
          timestamp: Date.now(),
          content: '',
          isStreaming: false,
        },
      });
      const wrapper = mount(TimelineRound, {
        props: { round, isAnalyzing: false },
        global: { stubs: childStubs },
      });

      // 不应显示占位文本，工具调用正常显示
      expect(wrapper.find('.tool-stub').exists()).toBe(true);
      expect(wrapper.text()).not.toContain('暂无思考');
    });
  });
});
