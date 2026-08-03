import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import DataPreview from '../components/DataPreview.vue';

vi.mock('tdesign-icons-vue-next', () => ({
  DownloadIcon: { template: '<span />' },
}));

const { mockMessagePlugin } = vi.hoisted(() => ({
  mockMessagePlugin: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

vi.mock('tdesign-vue-next', () => ({
  MessagePlugin: mockMessagePlugin,
}));

const tdesignStubs = {
  't-input': { template: '<input />' },
  't-button': {
    template: '<button @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
    emits: ['click'],
  },
  't-table': { template: '<div class="t-table-stub" />' },
};

describe('DataPreview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should_callCreateObjectURL_when_exportingCSV_given_dataWithRows', async () => {
    // given
    const mockCreateObjectURL = vi.fn(() => 'blob:mock-url');
    const mockRevokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL: mockCreateObjectURL, revokeObjectURL: mockRevokeObjectURL });
    const clickSpy = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(clickSpy);

    const data = [
      { name: 'Alice', age: 30 },
      { name: 'Bob', age: 25 },
    ];

    const wrapper = mount(DataPreview, {
      props: { data },
      global: { stubs: tdesignStubs },
    });

    // when
    const exportBtn = wrapper.findAll('button').find((b) => b.text().includes('导出 CSV'));
    await exportBtn!.trigger('click');

    // then — URL.createObjectURL 被调用，说明 Blob 已创建（CSV 导出流程执行）
    expect(mockCreateObjectURL).toHaveBeenCalledTimes(1);
    // MessagePlugin.success 被调用，说明导出流程完成
    expect(mockMessagePlugin.success).toHaveBeenCalledWith('导出成功');

    // 清理
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('should_showWarning_when_exportingCSV_given_emptyData', async () => {
    // given
    const data: Record<string, any>[] = [];

    const wrapper = mount(DataPreview, {
      props: { data },
      global: { stubs: tdesignStubs },
    });

    // when
    const exportBtn = wrapper.findAll('button').find((b) => b.text().includes('导出 CSV'));
    await exportBtn!.trigger('click');

    // then — 空数据时显示警告，不调用 createObjectURL
    expect(mockMessagePlugin.warning).toHaveBeenCalledWith('没有数据可导出');
  });

  it('should_displayRowCount_when_mounted_given_dataWithRows', () => {
    // given
    const data = [
      { name: 'Alice', age: 30 },
      { name: 'Bob', age: 25 },
    ];

    // when
    const wrapper = mount(DataPreview, {
      props: { data },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('2 行数据');
  });
});
