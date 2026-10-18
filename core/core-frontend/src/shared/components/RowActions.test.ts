// @vitest-environment jsdom
import { beforeAll, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import TDesign from 'tdesign-vue-next';
import RowActions from './RowActions.vue';
import type { RowActionItem } from './RowActions.vue';

/**
 * RowActions 组件测试：校验"主操作内联、次级操作按需收进更多下拉"的交互契约。
 */
beforeAll(() => {
  // jsdom 未实现 ResizeObserver，TDesign 组件挂载时可能探测该 API，此处做最小兜底
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
});

const ACTIONS: RowActionItem[] = [
  { key: 'edit', label: '编辑', theme: 'primary', tip: '修改名称' },
  { key: 'delete', label: '删除', theme: 'danger', tip: '删除记录' },
];

/** 挂载被测组件（注册 TDesign 以便解析 t-button / t-tooltip / t-dropdown / t-icon）。 */
function mountRowActions(actions: RowActionItem[], maxInline?: number) {
  return mount(RowActions, {
    props: { actions, maxInline },
    global: { plugins: [TDesign] },
  });
}

describe('RowActions', () => {
  it('should 内联主操作并渲染更多触发器_when 操作数超过内联上限', () => {
    // given / when
    const wrapper = mountRowActions(ACTIONS);

    // then：仅主操作内联展示，其余动作默认不可见，由"更多"触发器承载
    const buttons = wrapper.findAll('button');
    expect(buttons[0].text()).toContain('编辑');
    expect(wrapper.find('[aria-label="更多操作"]').exists()).toBe(true);
  });

  it('should 触发 select 事件_when 点击内联主操作', async () => {
    // given
    const wrapper = mountRowActions(ACTIONS);

    // when
    await wrapper.findAll('button')[0].trigger('click');

    // then
    expect(wrapper.emitted('select')?.[0]).toEqual(['edit']);
  });

  it('should 不渲染更多触发器_when 操作数不超过内联上限', () => {
    // given / when
    const wrapper = mountRowActions([ACTIONS[0]], 1);

    // then
    expect(wrapper.find('[aria-label="更多操作"]').exists()).toBe(false);
  });
});