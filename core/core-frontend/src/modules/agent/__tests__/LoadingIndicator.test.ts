import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LoadingIndicator from '../components/LoadingIndicator.vue';

/**
 * LoadingIndicator 组件单元测试
 * <p>验证加载指示器组件的基础渲染功能，包括文本显示和 CSS 类名。</p>
 */
describe('LoadingIndicator', () => {
  /**
   * 测试场景：组件挂载后应显示"分析中..."文本
   */
  it('should_displayAnalysisText_when_mounted_given_defaultState', () => {
    // given
    const stubs = {
      't-loading': { template: '<div />' },
    };

    // when
    const wrapper = mount(LoadingIndicator, {
      global: { stubs },
    });

    // then
    expect(wrapper.text()).toContain('分析中...');
  });

  /**
   * 测试场景：组件根元素应包含 loading-indicator 类名
   */
  it('should_haveLoadingIndicatorClass_when_mounted_given_defaultState', () => {
    // given
    const stubs = {
      't-loading': { template: '<div />' },
    };

    // when
    const wrapper = mount(LoadingIndicator, {
      global: { stubs },
    });

    // then
    expect(wrapper.find('.loading-indicator').exists()).toBe(true);
  });

  /**
   * 测试场景：标签元素应包含 loading-indicator__label 类名
   */
  it('should_haveLabelClass_when_mounted_given_defaultState', () => {
    // given
    const stubs = {
      't-loading': { template: '<div />' },
    };

    // when
    const wrapper = mount(LoadingIndicator, {
      global: { stubs },
    });

    // then
    expect(wrapper.find('.loading-indicator__label').exists()).toBe(true);
  });
});
