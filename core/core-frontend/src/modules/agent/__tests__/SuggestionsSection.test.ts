import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import SuggestionsSection from '../components/cards/SuggestionsSection.vue';
import type { Suggestion } from '../types';

vi.mock('@/shared/stores/ui', () => ({
  useUiStore: () => ({
    setSuggestion: vi.fn(),
  }),
}));

describe('SuggestionsSection', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  const mockSuggestions: Suggestion[] = [
    { text: '按地区细分', type: 'drill' },
    { text: '查看同比', type: 'compare' },
    { text: '预测下月趋势', type: 'predict' },
  ];

  it('should_displayAllSuggestions_when_mounted_given_suggestionList', () => {
    // given
    const props = { suggestions: mockSuggestions };

    // when
    const wrapper = mount(SuggestionsSection, { props });

    // then
    const chips = wrapper.findAll('.suggestion-chip');
    expect(chips).toHaveLength(3);
    expect(chips[0].text()).toBe('按地区细分');
  });

  it('should_notRender_when_mounted_given_emptySuggestions', () => {
    // given
    const props = { suggestions: [] };

    // when
    const wrapper = mount(SuggestionsSection, { props });

    // then
    expect(wrapper.find('.suggestions-section').exists()).toBe(false);
  });

  it('should_emitSelect_when_suggestionClicked_given_validSuggestion', async () => {
    // given
    const props = { suggestions: mockSuggestions };
    const wrapper = mount(SuggestionsSection, { props });

    // when
    const chips = wrapper.findAll('.suggestion-chip');
    await chips[0].trigger('click');

    // then
    const selectEvents = wrapper.emitted('select');
    expect(selectEvents).toBeTruthy();
    if (selectEvents) {
      expect(selectEvents[0][0]).toEqual(mockSuggestions[0]);
    }
  });

  it('should_displayHeaderLabel_when_mounted_given_suggestionList', () => {
    // given
    const props = { suggestions: mockSuggestions };

    // when
    const wrapper = mount(SuggestionsSection, { props });

    // then
    expect(wrapper.text()).toContain('你可能还想了解');
  });
});
