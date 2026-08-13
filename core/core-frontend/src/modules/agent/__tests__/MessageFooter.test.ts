import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import MessageFooter from '../components/MessageFooter.vue';
import type { ChatMessage } from '../types';

vi.mock('@/shared/utils/copy', () => ({
  copyToClipboard: vi.fn(() => Promise.resolve(true)),
}));

const tdesignStubs = {
  't-button': {
    template: '<button @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
    emits: ['click'],
  },
};

function createMockMessage(report?: string): ChatMessage {
  return {
    id: 'test-1',
    role: 'agent',
    content: '',
    timestamp: Date.now(),
    analysisState: {
      contentItems: [],
      currentSQL: null,
      queryData: [],
      chartConfig: null,
      chartType: null,
      analysisReport: report || null,
      searchResults: null,
      isEmptyResult: false,
      errorMessage: null,
      analysisStartTime: null,
      analysisEndTime: null,
      suggestions: [],
    },
  };
}

describe('MessageFooter', () => {
  it('should_emitRetry_when_retryButtonClicked_given_validMessage', async () => {
    // given
    const message = createMockMessage('test report');
    const wrapper = mount(MessageFooter, {
      props: { message },
      global: { stubs: tdesignStubs },
    });

    // when
    const buttons = wrapper.findAll('button');
    const retryButton = buttons.find(b => b.text().includes('重新生成'));
    await retryButton?.trigger('click');

    // then
    expect(wrapper.emitted('retry')).toBeTruthy();
  });

  it('should_toggleFavorite_when_favoriteButtonClicked_given_validMessage', async () => {
    // given
    const message = createMockMessage('test report');
    const wrapper = mount(MessageFooter, {
      props: { message },
      global: { stubs: tdesignStubs },
    });

    // when
    const buttons = wrapper.findAll('button');
    const favoriteButton = buttons.find(b => b.text().includes('收藏'));
    await favoriteButton?.trigger('click');

    // then
    expect(favoriteButton?.text()).toContain('已收藏');
  });

  it('should_emitFeedback_when_likeButtonClicked_given_validMessage', async () => {
    // given
    const message = createMockMessage('test report');
    const wrapper = mount(MessageFooter, {
      props: { message },
      global: { stubs: tdesignStubs },
    });

    // when
    const buttons = wrapper.findAll('button');
    // 点赞按钮是没有文字的按钮（只有图标）
    const likeButton = buttons.find(b => b.text().trim() === '');
    if (likeButton) {
      await likeButton.trigger('click');
    }

    // then
    const feedbackEvents = wrapper.emitted('feedback');
    expect(feedbackEvents).toBeTruthy();
    if (feedbackEvents) {
      expect(feedbackEvents[0][0]).toEqual({ type: 'like' });
    }
  });
});
