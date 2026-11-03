// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import TDesign, { RadioGroup, Select, Slider } from 'tdesign-vue-next';
import { getKnowledgeBase } from '../../api/knowledgeBase';
import { listEnabledProfiles } from '@/modules/model/api';
import RetrievalTester from './RetrievalTester.vue';

/**
 * 检索参数调试台组件测试（聚焦通道召回数量滑杆）。
 * <p>口径：NAIVE 仅渲染向量 / BM25 两条通道滑杆，MIX 追加图谱滑杆（实体/关系路共用单值）；
 * 滑杆 change 回写草稿值并同步标签文案。知识库配置回显与模型列表均以 Mock 隔离。</p>
 */

// jsdom 未实现 ResizeObserver，TDesign 组件挂载时可能探测该 API，此处做最小兜底
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
};

vi.mock('../../api/knowledgeBase', () => ({
  getKnowledgeBase: vi.fn(),
  updateRetrievalConfig: vi.fn(),
}));
vi.mock('../../api/retrieval', () => ({
  retrieveChunks: vi.fn(),
}));
vi.mock('@/modules/model/api', () => ({
  MODEL_TYPE_RERANK: 'RERANK',
  listEnabledProfiles: vi.fn(),
}));

/** 已挂载的包装器：每个用例结束后统一卸载，避免 DOM 跨用例残留。 */
const mountedWrappers: ReturnType<typeof mount>[] = [];

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/**
 * 按回显配置挂载调试台。
 * @param retrievalStrategy 知识库 retrieval_strategy JSON 字符串（null 表示未配置）
 * @returns 组件包装器
 */
async function mountTester(retrievalStrategy: string | null): Promise<ReturnType<typeof mount>> {
  vi.mocked(getKnowledgeBase).mockResolvedValue({ retrievalStrategy } as never);
  vi.mocked(listEnabledProfiles).mockResolvedValue([] as never);
  const wrapper = mount(RetrievalTester, {
    props: { kbId: 1 },
    global: { plugins: [TDesign] },
  });
  await flushPromises();
  mountedWrappers.push(wrapper);
  return wrapper;
}

describe('RetrievalTester 通道召回数量', () => {
  it('should_渲染两条通道滑杆默认20_when_mount_given_NAIVE空配置', async () => {
    // when
    const wrapper = await mountTester(null);

    // then：NAIVE 仅向量 / BM25 两条通道滑杆（第三条为相似度阈值滑杆），图谱滑杆不渲染
    expect(wrapper.text()).toContain('VECTOR 通道：20 条');
    expect(wrapper.text()).toContain('BM25 通道：20 条');
    expect(wrapper.text()).not.toContain('GRAPH 通道');
    expect(wrapper.findAllComponents(Slider)).toHaveLength(3);
  });

  it('should_渲染三条通道滑杆并回显配置值_when_mount_given_MIX配置', async () => {
    // given
    const strategy = JSON.stringify({
      strategyType: 'MIX',
      channelQueryCountConfig: { vectorQueryCount: 30, bm25QueryCount: 15, graphQueryCount: 25 },
    });

    // when
    const wrapper = await mountTester(strategy);

    // then：MIX 追加图谱滑杆，三值按配置回显
    expect(wrapper.text()).toContain('VECTOR 通道：30 条');
    expect(wrapper.text()).toContain('BM25 通道：15 条');
    expect(wrapper.text()).toContain('GRAPH 通道：25 条');
    expect(wrapper.findAllComponents(Slider)).toHaveLength(4);
  });

  it('should_切换MIX后出现图谱滑杆_when_changeStrategy_when_NAIVE初始态', async () => {
    // given
    const wrapper = await mountTester(null);
    expect(wrapper.text()).not.toContain('GRAPH 通道');

    // when：策略单选切到 MIX
    wrapper.findAllComponents(RadioGroup)[0].vm.$emit('update:modelValue', 'MIX');
    await nextTick();

    // then：图谱滑杆出现，草稿值取默认 20
    expect(wrapper.text()).toContain('GRAPH 通道：20 条');
    expect(wrapper.findAllComponents(Slider)).toHaveLength(4);
  });

  it('should_滑杆回写标签数量_when_dragVectorSlider_given_NAIVE初始态', async () => {
    // given：滑杆顺序为 向量、BM25、相似度
    const wrapper = await mountTester(null);

    // when
    wrapper.findAllComponents(Slider)[0].vm.$emit('change', 50);
    await nextTick();

    // then
    expect(wrapper.text()).toContain('VECTOR 通道：50 条');
  });

  it('should_异常回传归一默认值_when_dragBm25Slider_given_非法滑杆值', async () => {
    // given
    const wrapper = await mountTester(null);

    // when：数组回传取首元素，非数值回传回落默认 20
    wrapper.findAllComponents(Slider)[1].vm.$emit('change', [80]);
    await nextTick();
    expect(wrapper.text()).toContain('BM25 通道：80 条');

    wrapper.findAllComponents(Slider)[1].vm.$emit('change', 'oops');
    await nextTick();
    expect(wrapper.text()).toContain('BM25 通道：20 条');
  });
});

describe('RetrievalTester 加权融合三路权重', () => {
  it('should_渲染带名称的三路权重滑块_when_switchToWeightedSum_given_默认权重', async () => {
    // given：默认 RRF 融合下不渲染权重滑块
    const wrapper = await mountTester(null);
    expect(wrapper.text()).not.toContain('VECTOR 通道权重');

    // when：融合方式切到加权求和（页面上仅此一个下拉，索引 0）
    wrapper.findAllComponents(Select)[0].vm.$emit('update:modelValue', 'WEIGHTED_SUM');
    await nextTick();

    // then：三条通道滑块按枚举名展示并回显默认权重，合计 1.0
    expect(wrapper.text()).toContain('VECTOR 通道权重：0.7');
    expect(wrapper.text()).toContain('BM25 通道权重：0.2');
    expect(wrapper.text()).toContain('GRAPH 通道权重：0.1');
    expect(wrapper.text()).toContain('三路合计 1.0');
    expect(wrapper.findAllComponents(Slider)).toHaveLength(6);
  });

  it('should_其余两路让到下限_when_dragVectorWeight_given_拖到上限0.8', async () => {
    // given：滑块顺序为 向量数量、BM25 数量、相似度、VECTOR 权重、BM25 权重、GRAPH 权重
    const wrapper = await mountTester(null);
    wrapper.findAllComponents(Select)[0].vm.$emit('update:modelValue', 'WEIGHTED_SUM');
    await nextTick();

    // when
    wrapper.findAllComponents(Slider)[3].vm.$emit('change', 0.8);
    await nextTick();

    // then：三路联动，合计仍为 1.0 且无 0 权通道
    expect(wrapper.text()).toContain('VECTOR 通道权重：0.8');
    expect(wrapper.text()).toContain('BM25 通道权重：0.1');
    expect(wrapper.text()).toContain('GRAPH 通道权重：0.1');
    expect(wrapper.text()).toContain('三路合计 1.0');
  });

  it('should_按0.1步进联动_when_dragBm25Weight_given_拖到0.4', async () => {
    // given
    const wrapper = await mountTester(null);
    wrapper.findAllComponents(Select)[0].vm.$emit('update:modelValue', 'WEIGHTED_SUM');
    await nextTick();

    // when：BM25 0.2 → 0.4，剩余 0.6 由 VECTOR(7):GRAPH(1) 分摊
    wrapper.findAllComponents(Slider)[4].vm.$emit('change', 0.4);
    await nextTick();

    // then
    expect(wrapper.text()).toContain('BM25 通道权重：0.4');
    expect(wrapper.text()).toContain('VECTOR 通道权重：0.5');
    expect(wrapper.text()).toContain('GRAPH 通道权重：0.1');
    expect(wrapper.text()).toContain('三路合计 1.0');
  });
});
