// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/** 提升到 mock 工厂可访问的作用域（避免 vi.mock 提升导致的初始化顺序问题） */
const mocks = vi.hoisted(() => ({
  streamAnswer: vi.fn(),
  close: vi.fn(),
  messageError: vi.fn(),
  messageWarning: vi.fn(),
}));

vi.mock('../../api/retrieval', () => ({
  streamAnswer: mocks.streamAnswer,
}));

vi.mock('tdesign-vue-next', () => ({
  MessagePlugin: { error: mocks.messageError, warning: mocks.messageWarning },
}));

import QaChatPane from './QaChatPane.vue';
import type { StreamAnswerHandlers } from '../../api/retrieval';

/**
 * 知识问答面板流式交互测试。
 * <p>覆盖：① 增量按序累积并渲染 markdown；② 完成帧全文覆盖（缓存命中无增量）；
 * ③ 流内错误移除占位气泡并提示；④ 停止生成关闭连接并保留内容；⑤ 组件卸载自动关流。</p>
 */
describe('QaChatPane 流式问答', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mocks.streamAnswer.mockReset();
    mocks.close.mockReset();
    mocks.messageError.mockReset();
    mocks.messageWarning.mockReset();
    mocks.streamAnswer.mockReturnValue({ close: mocks.close });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * 挂载组件（TDesign 子组件以 stub 替代，聚焦问答逻辑）。
   * @returns 挂载结果
   */
  function setup(): ReturnType<typeof mount> {
    return mount(QaChatPane, {
      props: { kbId: 1 },
      global: {
        stubs: {
          't-textarea': true,
          't-button': true,
          't-tag': true,
          't-loading': true,
          't-icon': true,
        },
      },
    });
  }

  /**
   * 读取组件 setup 暴露的内部状态（script setup 未显式 expose）。
   * @param wrapper 挂载结果
   * @returns setup 状态对象
   */
  function stateOf(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
    return (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState;
  }

  /**
   * 发送一个问题并取回传给流客户端的回调集合。
   * @param wrapper 挂载结果
   * @returns 回调集合
   */
  function sendQuestion(wrapper: ReturnType<typeof mount>): StreamAnswerHandlers {
    const state = stateOf(wrapper);
    state.inputText = '营收对比';
    (state.send as () => void)();
    return mocks.streamAnswer.mock.calls[0][1] as StreamAnswerHandlers;
  }

  it('should_renderDeltasInOrder_when_streaming', async () => {
    // given
    const wrapper = setup();
    const handlers = sendQuestion(wrapper);

    // when
    handlers.onDelta('根据');
    handlers.onDelta('规则');
    vi.advanceTimersByTime(100);
    await nextTick();

    // then：增量按序拼接并渲染到 markdown 容器
    expect(wrapper.find('.qa-bubble__md').html()).toContain('根据规则');
  });

  it('should_overrideAccumulatedText_when_completedArrivesWithoutDelta', async () => {
    // given：缓存命中场景，没有任何增量
    const wrapper = setup();
    const handlers = sendQuestion(wrapper);

    // when
    handlers.onCompleted('缓存全文');
    await nextTick();

    // then
    expect(wrapper.find('.qa-bubble__md').html()).toContain('缓存全文');
    expect(stateOf(wrapper).sending).toBe(false);
  });

  it('should_removeBubbleAndReport_when_streamErrorArrives', async () => {
    // given
    const wrapper = setup();
    const handlers = sendQuestion(wrapper);

    // when
    handlers.onError('模型不可用');
    await nextTick();

    // then：失败不保留占位气泡
    expect(wrapper.findAll('.qa-row--assistant')).toHaveLength(0);
    expect(mocks.messageError).toHaveBeenCalledWith('问答失败：模型不可用');
  });

  it('should_closeStreamAndKeepContent_when_stopClicked', async () => {
    // given
    const wrapper = setup();
    const handlers = sendQuestion(wrapper);
    handlers.onDelta('已生成内容');
    vi.advanceTimersByTime(100);
    await nextTick();

    // when
    (stateOf(wrapper).stop as () => void)();
    await nextTick();

    // then：连接被关闭，已渲染内容保留
    expect(mocks.close).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.qa-bubble__md').html()).toContain('已生成内容');
  });

  it('should_closeStream_when_componentUnmounted', () => {
    // given
    const wrapper = setup();
    sendQuestion(wrapper);

    // when
    wrapper.unmount();

    // then
    expect(mocks.close).toHaveBeenCalledTimes(1);
  });
});