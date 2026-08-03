import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import DataTableSection from '../components/cards/DataTableSection.vue';

vi.mock('../DataPreview.vue', () => ({
  default: {
    name: 'DataPreview',
    template: '<div class="data-preview-stub" />',
  },
}));

describe('DataTableSection', () => {
  it('should_displayRowCount_when_mounted_given_dataWithRows', () => {
    // given
    const data = [
      { name: 'Alice', age: 30 },
      { name: 'Bob', age: 25 },
    ];

    // when
    const wrapper = mount(DataTableSection, {
      props: { data },
    });

    // then
    expect(wrapper.text()).toContain('2行');
  });

  it('should_displayTitle_when_mounted_given_emptyData', () => {
    // given
    const data: Record<string, any>[] = [];

    // when
    const wrapper = mount(DataTableSection, {
      props: { data },
    });

    // then
    expect(wrapper.text()).toContain('数据明细');
    expect(wrapper.text()).toContain('0行');
  });
});
