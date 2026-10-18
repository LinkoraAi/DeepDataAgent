// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils';
import TDesign from 'tdesign-vue-next';
import DocChunkStrategyDialog from './DocChunkStrategyDialog.vue';
import ChunkModeFields from '../chunk/ChunkModeFields.vue';

/**
 * 文档级分块策略编辑弹窗组件测试。
 * <p>弹窗以 attach="body" 经 Teleport 渲染，DOM 断言统一以 document.body 为查询根
 * （与 DocReaderDialog.test.ts 同模式）；子编辑器状态经 findComponent 读取。</p>
 */

/** 已挂载的包装器：每个用例结束后统一卸载，避免弹窗 DOM 跨用例残留。 */
const mountedWrappers: ReturnType<typeof mount>[] = [];

// jsdom 未实现 ResizeObserver，TDesign 组件挂载时可能探测该 API，此处做最小兜底
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
};

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/** 弹窗查询根（Teleport 到 body）。 */
function page(): DOMWrapper<Element> {
  return new DOMWrapper(document.body);
}

/** 挂载并打开弹窗。 */
async function openDialog(options: {
  initialStrategy?: string | null;
  fileType: string;
  mode?: 'upload' | 'edit';
}): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(DocChunkStrategyDialog, {
    props: {
      modelValue: false,
      initialStrategy: options.initialStrategy ?? null,
      fileType: options.fileType,
      mode: options.mode ?? 'edit',
    },
    global: { plugins: [TDesign] },
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  mountedWrappers.push(wrapper);
  return wrapper;
}

/** 读取内部编辑器子组件实例（props 回显与子事件触发均经此入口）。 */
function fieldsOf(wrapper: ReturnType<typeof mount>) {
  return wrapper.findComponent(ChunkModeFields);
}

/** 查找指定文案的按钮。 */
function findButton(text: string) {
  const button = page().findAll('button').find((item) => item.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

describe('DocChunkStrategyDialog', () => {
  it('should_echoDocMode_when_open_given_qaStrategyJson', async () => {
    // given / when
    const wrapper = await openDialog({
      initialStrategy: JSON.stringify({ chunkMode: 'QA' }),
      fileType: 'DOCX',
    });

    // then（回显命中策略模式）
    expect(fieldsOf(wrapper).props('chunkMode')).toBe('QA');
  });

  it('should_filterModesByMatrix_when_open_given_csvFileType', async () => {
    // given / when
    const wrapper = await openDialog({ fileType: 'CSV' });

    // then（CSV 矩阵允许 GENERAL/QA/TABLE，不含 BOOK/LAWS/PRESENTATION/ONE）
    expect(fieldsOf(wrapper).props('allowedModes')).toEqual(['GENERAL', 'QA', 'TABLE']);
  });

  it('should_fallbackGeneral_when_open_given_modeOutsideMatrix', async () => {
    // given（回显模式与文件类型不兼容：PDF 携 TABLE 策略）
    const wrapper = await openDialog({
      initialStrategy: JSON.stringify({ chunkMode: 'TABLE' }),
      fileType: 'PDF',
    });

    // then（回落 GENERAL，MUST NOT 保留矩阵外初值）
    expect(fieldsOf(wrapper).props('chunkMode')).toBe('GENERAL');
  });

  it('should_fallbackGeneral_when_open_given_unsetInitialStrategy', async () => {
    // given / when
    const wrapper = await openDialog({ fileType: 'PDF' });

    // then
    expect(fieldsOf(wrapper).props('chunkMode')).toBe('GENERAL');
  });

  it('should_emitCleanGeneralJson_when_confirm_given_defaultDraft', async () => {
    // given
    const wrapper = await openDialog({ fileType: 'MD' });

    // when
    await findButton('保存并重新解析').trigger('click');
    await flushPromises();

    // then（GENERAL 输出三规范键）
    const emitted = wrapper.emitted('confirm');
    expect(emitted).toHaveLength(1);
    expect(JSON.parse(emitted?.[0][0] as string)).toEqual({
      chunkMode: 'GENERAL',
      modeConfig: { params: { chunk_token_num: 512, overlapped_percent: 0, delimiter: '\n' } },
    });
  });

  it('should_emitModeOnlyJson_when_confirm_given_nonGeneralMode', async () => {
    // given
    const wrapper = await openDialog({ fileType: 'PDF' });
    fieldsOf(wrapper).vm.$emit('update:chunkMode', 'PRESENTATION');
    await flushPromises();

    // when
    await findButton('保存并重新解析').trigger('click');
    await flushPromises();

    // then（非 GENERAL 不携带 modeConfig）
    const emitted = wrapper.emitted('confirm');
    expect(JSON.parse(emitted?.[0][0] as string)).toEqual({ chunkMode: 'PRESENTATION' });
  });

  it('should_emitNull_when_restoreInherit_given_editMode', async () => {
    // given
    const wrapper = await openDialog({ fileType: 'PDF' });

    // when
    await findButton('恢复为继承库级策略').trigger('click');
    await flushPromises();

    // then（null 语义 = 宿主发起不带覆盖值的重解析）
    const emitted = wrapper.emitted('confirm');
    expect(emitted).toHaveLength(1);
    expect(emitted?.[0][0]).toBeNull();
  });

  it('should_hideRestoreAndUseUploadCopy_when_open_given_uploadMode', async () => {
    // given / when
    await openDialog({ fileType: 'PDF', mode: 'upload' });

    // then（上传形态：确认按钮为「应用策略」，无恢复继承入口，文案声明无需重解析）
    expect(findButton('应用策略').exists()).toBe(true);
    expect(page().findAll('button').some((item) => item.text() === '恢复为继承库级策略')).toBe(false);
    expect(page().text()).toContain('无需重新解析');
  });

  it('should_showReparseWarning_when_open_given_editMode', async () => {
    // given / when
    await openDialog({ fileType: 'PDF' });

    // then（编辑形态提示保存并重新解析的清切片语义）
    expect(page().text()).toContain('清空该文档全部切片（含手工切片）');
  });
});
