import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ErrorDisplay from '../components/ErrorDisplay.vue';
import type { AppError } from '../utils/errorHandler';

const tdesignStubs = {
  't-icon': { template: '<i class="t-icon-stub" />' },
  't-tag': { template: '<span class="t-tag-stub"><slot /></span>' },
  't-button': {
    template: '<button @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
    emits: ['click'],
  },
};

describe('ErrorDisplay', () => {
  it('should_displayErrorMessage_when_mounted_given_errorWithMessage', () => {
    // given
    const error: AppError = {
      type: 'connection',
      message: '数据库连接失败',
      suggestion: '请检查数据库配置',
      retryable: true,
    };

    // when
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('数据库连接失败');
  });

  it('should_displaySuggestion_when_mounted_given_errorWithSuggestion', () => {
    // given
    const error: AppError = {
      type: 'timeout',
      message: '查询超时',
      suggestion: '请缩小查询范围后重试',
      retryable: true,
    };

    // when
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('请缩小查询范围后重试');
  });

  it('should_showRetryButton_when_mounted_given_retryableError', () => {
    // given
    const error: AppError = {
      type: 'connection',
      message: '连接失败',
      suggestion: '请重试',
      retryable: true,
    };

    // when
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('重试');
  });

  it('should_notShowRetryButton_when_mounted_given_nonRetryableError', () => {
    // given
    const error: AppError = {
      type: 'validation',
      message: '参数错误',
      suggestion: '请检查输入',
      retryable: false,
    };

    // when
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).not.toContain('重试');
  });

  it('should_emitRetry_when_retryButtonClicked_given_retryableError', async () => {
    // given
    const error: AppError = {
      type: 'connection',
      message: '连接失败',
      suggestion: '请重试',
      retryable: true,
    };
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // when
    const button = wrapper.find('button');
    await button.trigger('click');

    // then
    expect(wrapper.emitted('retry')).toBeTruthy();
  });

  it('should_displayErrorTypeLabel_when_mounted_given_dataError', () => {
    // given
    const error: AppError = {
      type: 'data_error',
      message: '查询结果为空',
      suggestion: '请调整查询条件',
      retryable: true,
    };

    // when
    const wrapper = mount(ErrorDisplay, {
      props: { error },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('数据错误');
  });
});
