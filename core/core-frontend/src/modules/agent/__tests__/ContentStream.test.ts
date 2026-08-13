import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ContentStream from '../components/cards/ContentStream.vue';
import ContentItem from '../components/cards/ContentItem.vue';
import type { ContentItem as ContentItemType } from '../types';

vi.mock('@/shared/utils/markdown', () => ({
  renderMarkdown: vi.fn((text: string) => `<p>${text}</p>`),
}));

/** 构造内容项 */
function createItem(
  overrides: Partial<ContentItemType> & { id: string; seq: number; type: ContentItemType['type'] }
): ContentItemType {
  return {
    status: 'completed',
    startTime: 1700000000000,
    endTime: 1700000005000,
    ...overrides,
  };
}

const tdesignStubs = {
  't-loading': { template: '<div class="t-loading-stub" />' },
};

/** 挂载 ContentStream 并取渲染出的 ContentItem 组件列表 */
function mountStream(items: ContentItemType[]) {
  const wrapper = mount(ContentStream, {
    props: { items, isAnalyzing: false },
    global: { stubs: tdesignStubs },
  });
  return wrapper.findAllComponents(ContentItem);
}

describe('ContentStream - 展示层按 toolCallId 合并派生（D2）', () => {
  it('并行多次调用 + 乱序结果 → 按 toolCallId 合并，位置以调用项为锚', () => {
    // given：两次并行调用，结果按乱序（非调用顺序）到达
    const items: ContentItemType[] = [
      createItem({ id: 'tool-1', seq: 0, type: 'tool_call', toolName: 'execute_sql', toolCallId: 'call-1', input: '{"sql":"SELECT 1"}' }),
      createItem({ id: 'tool-2', seq: 1, type: 'tool_call', toolName: 'web_search', toolCallId: 'call-2', input: '{"q":"天气"}' }),
      // 乱序结果：call-2 的结果先到，call-1 的结果后到
      createItem({ id: 'tool-result-4', seq: 2, type: 'tool_result', toolName: 'web_search', toolCallId: 'call-2', result: '{"results":[]}' }),
      createItem({ id: 'tool-result-3', seq: 3, type: 'tool_result', toolName: 'execute_sql', toolCallId: 'call-1', result: '{"data":[]}' }),
    ];

    // when
    const children = mountStream(items);

    // then：合并为两个调用单元，位置以调用项为锚，结果各归其主
    expect(children).toHaveLength(2);
    expect(children[0].props('item').id).toBe('tool-1');
    expect(children[0].props('resultItem')?.id).toBe('tool-result-3');
    expect(children[1].props('item').id).toBe('tool-2');
    expect(children[1].props('resultItem')?.id).toBe('tool-result-4');
  });

  it('孤儿结果（有 toolCallId 但无匹配调用）独立渲染，不报错', () => {
    // given：一次正常调用 + 一个无法匹配的孤儿结果
    const items: ContentItemType[] = [
      createItem({ id: 'tool-1', seq: 0, type: 'tool_call', toolName: 'execute_sql', toolCallId: 'call-1', input: '{"sql":"SELECT 1"}' }),
      createItem({ id: 'tool-result-9', seq: 1, type: 'tool_result', toolName: 'execute_sql', toolCallId: 'call-99', result: '{"data":[]}' }),
    ];

    // when
    const children = mountStream(items);

    // then：两个独立单元，孤儿结果无 resultItem
    expect(children).toHaveLength(2);
    expect(children[0].props('resultItem')).toBeUndefined();
    expect(children[1].props('item').type).toBe('tool_result');
    expect(children[1].props('item').id).toBe('tool-result-9');
    expect(children[1].props('resultItem')).toBeUndefined();
  });

  it('无 toolCallId 的调用与结果独立渲染（老数据回退，不做猜测配对）', () => {
    // given：老数据——调用与结果均缺 toolCallId
    const items: ContentItemType[] = [
      createItem({ id: 'tool-1', seq: 0, type: 'tool_call', toolName: 'execute_sql', input: '{"sql":"SELECT 1"}' }),
      createItem({ id: 'tool-result-2', seq: 1, type: 'tool_result', toolName: 'execute_sql', result: '{"data":[]}' }),
    ];

    // when
    const children = mountStream(items);

    // then：两张独立卡片按消息顺序展示
    expect(children).toHaveLength(2);
    expect(children[0].props('item').id).toBe('tool-1');
    expect(children[1].props('item').id).toBe('tool-result-2');
    expect(children[0].props('resultItem')).toBeUndefined();
    expect(children[1].props('resultItem')).toBeUndefined();
  });

  it('思考 / 报告项不受影响，按 seq 独立渲染', () => {
    // given：思考 + 调用 + 报告（无工具配对逻辑介入）
    const items: ContentItemType[] = [
      createItem({ id: 'thinking-1', seq: 0, type: 'thinking', content: '思考中' }),
      createItem({ id: 'tool-2', seq: 1, type: 'tool_call', toolName: 'execute_sql', toolCallId: 'call-1', input: '{"sql":"SELECT 1"}' }),
      createItem({ id: 'report-3', seq: 2, type: 'report', content: '分析完成' }),
    ];

    // when
    const children = mountStream(items);

    // then：三个独立单元按序渲染
    expect(children.map(c => c.props('item').id)).toEqual(['thinking-1', 'tool-2', 'report-3']);
  });
});
