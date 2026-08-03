import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ReportSection from '../components/cards/ReportSection.vue';

// Mock requestAnimationFrame 为同步执行
beforeEach(() => {
  vi.stubGlobal('requestAnimationFrame', (cb: Function) => { cb(); return 0; });
  vi.stubGlobal('cancelAnimationFrame', () => {});
  // Mock scrollIntoView：jsdom 默认未实现，需先定义再 spy
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    writable: true,
    value: vi.fn(),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

vi.mock('@/shared/utils/markdown', () => ({
  renderMarkdown: vi.fn((text: string) => `<p>${text}</p>`),
}));

describe('ReportSection', () => {
  it('should_displayReportContent_when_mounted_given_validReport', () => {
    // given
    const report = '## 销售分析\n本月销售额达 1,250 万。';

    // when
    const wrapper = mount(ReportSection, {
      props: { report },
    });

    // then
    expect(wrapper.text()).toContain('销售分析');
  });

  it('should_displayStreamingCursor_when_mounted_given_isAnalyzing', () => {
    // given
    const report = '正在生成报告...';

    // when
    const wrapper = mount(ReportSection, {
      props: { report, isAnalyzing: true },
    });

    // then
    expect(wrapper.find('.streaming-cursor').exists()).toBe(true);
  });

  it('should_notDisplayStreamingCursor_when_mounted_given_notAnalyzing', () => {
    // given
    const report = '分析完成';

    // when
    const wrapper = mount(ReportSection, {
      props: { report, isAnalyzing: false },
    });

    // then
    expect(wrapper.find('.streaming-cursor').exists()).toBe(false);
  });

  it('should_displayEmptyMessage_when_mounted_given_emptyReport', () => {
    // given
    const report = '';

    // when
    const wrapper = mount(ReportSection, {
      props: { report },
    });

    // then
    expect(wrapper.text()).toContain('暂无分析报告');
  });

  it('should_notScrollToReport_when_mounted_given_notAnalyzing', async () => {
    // given 历史回放：非分析状态挂载，报告内容存在
    const report = '历史报告内容';
    const scrollIntoView = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');

    // when
    mount(ReportSection, {
      props: { report, isAnalyzing: false },
    });
    await nextTick();

    // then 历史回放不触发任何滚动动画
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('should_scrollToReport_when_mounted_given_isAnalyzing', async () => {
    // given 分析中：报告内容更新后需要平滑跟随滚动
    const report = '分析中的报告';
    const scrollIntoView = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');

    // when
    mount(ReportSection, {
      props: { report, isAnalyzing: true },
    });
    await nextTick();

    // then 仅分析中触发 scrollIntoView 平滑滚动
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'end' });
  });
});
