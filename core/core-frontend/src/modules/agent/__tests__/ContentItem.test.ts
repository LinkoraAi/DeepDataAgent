import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ContentItem from '../components/cards/ContentItem.vue';
import type { ContentItem as ContentItemType } from '../types';

vi.mock('@/shared/utils/markdown', () => ({
  renderMarkdown: vi.fn((text: string) => `<p>${text}</p>`),
}));

/** 构造内容项 */
function createItem(overrides?: Partial<ContentItemType>): ContentItemType {
  return {
    id: 'item-1',
    seq: 0,
    type: 'thinking',
    status: 'in_progress',
    content: '思考内容',
    startTime: 1700000000000,
    ...overrides,
  };
}

const tdesignStubs = {
  't-loading': { template: '<div class="t-loading-stub" />' },
};

describe('ContentItem - 统一样式与状态驱动展开折叠', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('思考项动态展开折叠', () => {
    it('in_progress 且分析中应自动展开并显示流式光标', () => {
      // given：进行中思考项
      const wrapper = mount(ContentItem, {
        props: { item: createItem({ status: 'in_progress' }), isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });

      // then：内容展开可见，含流式光标
      const body = wrapper.find('.content-item__body');
      expect(body.exists()).toBe(true);
      expect(body.isVisible()).toBe(true);
      expect(wrapper.find('.streaming-cursor').exists()).toBe(true);
      expect(wrapper.find('.content-item__text').text()).toBe('思考内容');
    });

    it('completed 应自动折叠为摘要，手动点击可展开回看', async () => {
      // given：已完成思考项
      const wrapper = mount(ContentItem, {
        props: { item: createItem({ status: 'completed', endTime: 1700000001000 }), isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });

      // then：默认折叠（内容隐藏，摘要可见）
      expect(wrapper.find('.content-item__body').isVisible()).toBe(false);
      expect(wrapper.find('.content-item__summary').exists()).toBe(true);
      expect(wrapper.find('.content-item__summary').text()).toContain('思考内容');

      // when：点击折叠按钮展开
      await wrapper.find('.content-item__toggle').trigger('click');
      await nextTick();

      // then：内容展开（jsdom 计算样式缓存陈旧，改为断言 v-show 内联样式与摘要消失）
      expect(wrapper.find('.content-item__body').attributes('style') ?? '').not.toContain('display: none');
      expect(wrapper.find('.content-item__summary').exists()).toBe(false);
    });

    it('status 由 in_progress 变为 completed 应自动折叠', async () => {
      // given：进行中思考项（分析中）
      const item = createItem({ status: 'in_progress' });
      const wrapper = mount(ContentItem, {
        props: { item, isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);

      // when：思考完成（status 变化）
      await wrapper.setProps({ item: { ...item, status: 'completed', endTime: 1700000001000 } });
      await nextTick();

      // then：自动折叠（v-show 内联样式置为 display: none），流式光标消失
      expect(wrapper.find('.content-item__body').attributes('style')).toContain('display: none');
      expect(wrapper.find('.streaming-cursor').exists()).toBe(false);
    });
  });

  describe('工具调用项动态展开折叠', () => {
    it('in_progress 应自动展开显示入参与实时结果', () => {
      // given：进行中工具调用
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            result: '{"data":[]}',
            status: 'in_progress',
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：展开显示入参与结果
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__label').text()).toBe('输入参数');
      expect(wrapper.find('.content-item__code').text()).toContain('SELECT 1');
      // 工具名出现在头部
      expect(wrapper.find('.content-item__tool-name').exists()).toBe(true);
    });

    it('completed 应自动折叠为摘要（工具名+状态+耗时）', () => {
      // given：已完成工具调用
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            result: '{"data":[]}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000005000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：折叠为摘要（工具名 + ✓ + 耗时）
      expect(wrapper.find('.content-item__body').isVisible()).toBe(false);
      const summary = wrapper.find('.content-item__summary');
      expect(summary.text()).toContain('execute_sql');
      expect(summary.text()).toContain('✓');
      expect(summary.text()).toContain('5.0s');
    });

    it('failed 应保持展开显示错误详情', () => {
      // given：失败工具调用
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            result: '数据库连接失败',
            status: 'failed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：保持展开，显示错误结果与失败状态图标
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__body').text()).toContain('数据库连接失败');
      expect(wrapper.find('.content-item__tool-status--failed').exists()).toBe(true);
    });
  });

  describe('工具结果项渲染', () => {
    it('in_progress 应自动展开显示返回结果与流式光标', () => {
      // given：进行中工具结果项
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_result',
            toolName: 'execute_sql',
            result: '{"data":[]}',
            status: 'in_progress',
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：展开显示返回结果与流式光标，前置标签为「工具结果」
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__badge').text()).toContain('工具结果');
      expect(wrapper.find('.content-item__label').text()).toBe('返回结果');
      expect(wrapper.find('.content-item__code').text()).toContain('data');
      expect(wrapper.find('.streaming-cursor').exists()).toBe(true);
    });

    it('completed 应自动折叠为摘要（工具名+状态）', () => {
      // given：已完成工具结果项
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_result',
            toolName: 'execute_sql',
            result: '{"data":[]}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000005000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：折叠为摘要（工具名 + ✓）
      expect(wrapper.find('.content-item__body').isVisible()).toBe(false);
      const summary = wrapper.find('.content-item__summary');
      expect(summary.text()).toContain('execute_sql');
      expect(summary.text()).toContain('✓');
    });

    it('failed 应保持展开显示执行失败详情', () => {
      // given：失败工具结果项
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_result',
            toolName: 'execute_sql',
            result: '数据库连接失败',
            status: 'failed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：保持展开，显示执行失败提示与错误结果
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__failure').exists()).toBe(true);
      expect(wrapper.find('.content-item__body').text()).toContain('执行失败');
      expect(wrapper.find('.content-item__body').text()).toContain('数据库连接失败');
    });
  });

  describe('历史回放默认折叠', () => {
    it('非分析中（历史回放）即使 in_progress 也应默认折叠', () => {
      // given：历史回放场景，进行中思考项（合并展示数据）
      const wrapper = mount(ContentItem, {
        props: { item: createItem({ status: 'in_progress' }), isAnalyzing: false },
        global: { stubs: tdesignStubs },
      });

      // then：默认折叠，且不显示流式光标
      expect(wrapper.find('.content-item__body').isVisible()).toBe(false);
      expect(wrapper.find('.streaming-cursor').exists()).toBe(false);
    });
  });

  describe('报告项始终展开', () => {
    it('报告项不渲染折叠按钮且始终展开显示内容', () => {
      // given：报告内容项
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({ type: 'report', content: '分析报告', status: 'completed' }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：无折叠按钮，内容始终展开
      expect(wrapper.find('.content-item__toggle').exists()).toBe(false);
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__markdown').exists()).toBe(true);
    });
  });

  describe('统一样式（不差异化卡片）', () => {
    it('思考与工具调用共用同一内容容器样式类', () => {
      // given：思考项与工具项
      const thinking = mount(ContentItem, {
        props: { item: createItem({ type: 'thinking', status: 'in_progress' }), isAnalyzing: true },
        global: { stubs: tdesignStubs },
      });
      const tool = mount(ContentItem, {
        props: {
          item: createItem({ type: 'tool_call', toolName: 't', status: 'in_progress' }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：使用相同的 content-item 容器与 body 样式，仅通过前置标签区分类型
      expect(thinking.find('.content-item').classes()).toContain('content-item--thinking');
      expect(tool.find('.content-item').classes()).toContain('content-item--tool_call');
      expect(thinking.find('.content-item__badge').text()).toContain('思考');
      expect(tool.find('.content-item__badge').text()).toContain('工具调用');
    });
  });

  describe('ContentItem - 合并模式（调用 + 结果单卡片，D3）', () => {
    it('入参 + 结果合并为单卡片：入参在上、结果在下', async () => {
      // given：合并单元（调用 completed + 结果 completed）
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          resultItem: createItem({
            id: 'tool-result-1',
            type: 'tool_result',
            toolName: 'execute_sql',
            result: '{"data":[]}',
            status: 'completed',
            startTime: 1700000001000,
            endTime: 1700000005000,
          }),
          isAnalyzing: false,
        },
        global: { stubs: tdesignStubs },
      });

      // when：历史回放默认折叠，点击展开
      await wrapper.find('.content-item__toggle').trigger('click');
      await nextTick();

      // then：单卡片内含两个区块，入参在上、结果在下
      const labels = wrapper.findAll('.content-item__label').map(n => n.text());
      expect(labels).toEqual(['输入参数', '执行结果']);
      expect(wrapper.findAll('.content-item__code')[0].text()).toContain('SELECT 1');
      expect(wrapper.findAll('.content-item__code')[1].text()).toContain('data');
    });

    it('调用完成 + 结果流式 → 进行中状态，自动展开且结果区显示流式光标', () => {
      // given：合并单元，调用 completed、结果 in_progress（分析中）
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          resultItem: createItem({
            id: 'tool-result-1',
            type: 'tool_result',
            result: '{"data":',
            status: 'in_progress',
            startTime: 1700000001000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：综合状态 in_progress → 自动展开，结果区流式光标
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.streaming-cursor').exists()).toBe(true);
    });

    it('中断态：调用完成、无结果 → 「有入参、无结果」完成态', async () => {
      // given：仅调用项（无 resultItem），调用已完成
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          isAnalyzing: false,
        },
        global: { stubs: tdesignStubs },
      });

      // when：展开查看
      await wrapper.find('.content-item__toggle').trigger('click');
      await nextTick();

      // then：显示入参与「有入参、无结果」提示
      expect(wrapper.find('.content-item__code').text()).toContain('SELECT 1');
      expect(wrapper.find('.content-item__body').text()).toContain('有入参、无结果');
    });

    it('结果项 failed → 合并卡片综合为失败态并展开显示错误', () => {
      // given：调用 completed、结果 failed（分析中 → 自动展开）
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          resultItem: createItem({
            id: 'tool-result-1',
            type: 'tool_result',
            result: '数据库连接失败',
            status: 'failed',
            startTime: 1700000001000,
            endTime: 1700000002000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：失败态展开，显示失败提示与错误结果
      expect(wrapper.find('.content-item__body').isVisible()).toBe(true);
      expect(wrapper.find('.content-item__failure').exists()).toBe(true);
      expect(wrapper.find('.content-item__body').text()).toContain('数据库连接失败');
    });

    it('调用完成 + 结果完成 → 合并单元折叠为摘要（工具名 + ✓ + 时长以结果结束为界）', () => {
      // given：合并单元均完成
      const wrapper = mount(ContentItem, {
        props: {
          item: createItem({
            type: 'tool_call',
            toolName: 'execute_sql',
            input: '{"sql":"SELECT 1"}',
            status: 'completed',
            startTime: 1700000000000,
            endTime: 1700000001000,
          }),
          resultItem: createItem({
            id: 'tool-result-1',
            type: 'tool_result',
            result: '{"data":[]}',
            status: 'completed',
            startTime: 1700000001000,
            endTime: 1700000005000,
          }),
          isAnalyzing: true,
        },
        global: { stubs: tdesignStubs },
      });

      // then：折叠为摘要（工具名 + ✓ + 5.0s），结果结束时间作为整体时长
      expect(wrapper.find('.content-item__body').isVisible()).toBe(false);
      const summary = wrapper.find('.content-item__summary');
      expect(summary.text()).toContain('execute_sql');
      expect(summary.text()).toContain('✓');
      expect(summary.text()).toContain('5.0s');
    });
  });
});
