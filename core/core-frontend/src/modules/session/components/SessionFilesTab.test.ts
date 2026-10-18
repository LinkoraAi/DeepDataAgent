// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import TDesign, { Table } from 'tdesign-vue-next';
import SessionFilesTab from './SessionFilesTab.vue';
import { listResources } from '../api/sessions';

/**
 * 会话文件 Tab 回归测试。
 * <p>守护两个已发生的缺陷：①会话 ID 未就绪时不得发出畸形请求（/sessions//resources → 404）；
 * ②主表 loading 必须随 .value 正确熄灭，不得因传入裸 Ref 对象而永久转圈。</p>
 */

/** 只桩网络端点，其余实现（useAsync / 组件库）保持真实。 */
vi.mock('../api/sessions', () => ({
  listResources: vi.fn(),
  addResources: vi.fn(),
  removeResource: vi.fn(),
}));
vi.mock('@/modules/assetfile/api', () => ({ listFiles: vi.fn() }));

const listResourcesMock = vi.mocked(listResources);

// jsdom 未实现 ResizeObserver，TDesign 表格挂载时可能探测该 API
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
};

/** 已挂载包装器：用例结束统一卸载，避免 attach="body" 的弹窗 DOM 残留。 */
const mountedWrappers: ReturnType<typeof mount>[] = [];

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/** 挂载文件 Tab。 */
function mountTab(sessionId: string): ReturnType<typeof mount> {
  const wrapper = mount(SessionFilesTab, {
    props: { sessionId, readOnly: false },
    global: { plugins: [TDesign] },
  });
  mountedWrappers.push(wrapper);
  return wrapper;
}

describe('SessionFilesTab', () => {
  it('should_requestWithSessionId_when_mounted_given_validSessionId', async () => {
    // given
    listResourcesMock.mockResolvedValue({ data: [], first_id: null, last_id: null, has_more: false });

    // when
    mountTab('sess_abc');
    await flushPromises();

    // then
    expect(listResourcesMock).toHaveBeenCalledWith('sess_abc', expect.objectContaining({ limit: 100 }));
  });

  it('should_skipRequest_when_mounted_given_emptySessionId', async () => {
    // given
    const wrapper = mountTab('');

    // when
    await flushPromises();

    // then
    expect(listResourcesMock).not.toHaveBeenCalled();
    expect(wrapper.findComponent(Table).props('loading')).toBe(false);
  });

  it('should_stopLoading_when_requestSettles_given_emptyResourcePage', async () => {
    // given
    listResourcesMock.mockResolvedValue({ data: [], first_id: null, last_id: null, has_more: false });
    const wrapper = mountTab('sess_abc');

    // when
    await flushPromises();

    // then（回归哨兵：裸 Ref 经 || 短路恒为真值会让该 prop 永久为真）
    expect(wrapper.findComponent(Table).props('loading')).toBe(false);
  });

  it('should_renderResourceRows_when_loaded_given_singleResource', async () => {
    // given
    listResourcesMock.mockResolvedValue({
      data: [{
        id: 'sesr_1',
        type: 'file',
        file_id: 'file_1',
        mount_path: 'mounts/file_1',
        created_at: '2026-10-18T10:00:00Z',
      }],
      first_id: 'sesr_1',
      last_id: 'sesr_1',
      has_more: false,
    });

    // when
    const wrapper = mountTab('sess_abc');
    await flushPromises();

    // then
    expect(wrapper.text()).toContain('mounts/file_1');
  });
});
