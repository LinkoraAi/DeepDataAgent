// @vitest-environment jsdom
import { beforeAll, afterEach, describe, expect, it, vi } from 'vitest';
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils';
import TDesign from 'tdesign-vue-next';
import DocReaderDialog from './DocReaderDialog.vue';
import { fetchBlob, downloadFile } from '@/shared/api/blob';
import { documentContentPath, previewDocumentText } from '../../api/documents';
import type { DocumentWire } from '../../api/documents';

/**
 * 使用 @vue-office 渲染组件的桩替身：仅暴露 name/src，断言「走了哪个组件分支」，
 * 避免在 jsdom 中真正解析 docx/excel/pptx 二进制。
 */
vi.mock('@vue-office/docx', async () => {
  const { h } = await import('vue');
  return {
    default: { name: 'VueOfficeDocx', props: { src: { type: String, default: '' } }, setup: () => () => h('div', { class: 'stub-docx' }) },
  };
});
vi.mock('@vue-office/excel', async () => {
  const { h } = await import('vue');
  return {
    default: { name: 'VueOfficeExcel', props: { src: { type: String, default: '' } }, setup: () => () => h('div', { class: 'stub-excel' }) },
  };
});
vi.mock('@vue-office/pptx', async () => {
  const { h } = await import('vue');
  return {
    default: { name: 'VueOfficePptx', props: { src: { type: String, default: '' } }, setup: () => () => h('div', { class: 'stub-pptx' }) },
  };
});

/** 只桩掉二进制读取与下载，其余接口保持真实实现。 */
vi.mock('@/shared/api/blob', () => ({ fetchBlob: vi.fn(), downloadFile: vi.fn() }));

/** 桩掉后端文本提取端点：本组件已彻底不再使用，作为「不应再被调用」的回归哨兵。 */
vi.mock('../../api/documents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/documents')>();
  return { ...actual, previewDocumentText: vi.fn() };
});

const fetchBlobMock = vi.mocked(fetchBlob);
const downloadFileMock = vi.mocked(downloadFile);
const previewTextMock = vi.mocked(previewDocumentText);

/** object URL 打桩（jsdom 未实现）。 */
const createObjectUrlMock = vi.fn();
const revokeObjectUrlMock = vi.fn();

beforeAll(() => {
  // jsdom 未实现 ResizeObserver，TDesign 组件挂载时可能探测该 API，此处做最小兜底
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
  let seq = 0;
  createObjectUrlMock.mockImplementation(() => `blob:mock-${++seq}`);
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: createObjectUrlMock });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: revokeObjectUrlMock });
});

/** 已挂载的包装器：每个用例结束后统一卸载，避免 attach="body" 的弹窗 DOM 跨用例残留。 */
const mountedWrappers: ReturnType<typeof mount>[] = [];

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/** 构造线格式文档（仅覆盖断言所需字段，其余给稳定默认值）。 */
function makeDoc(fileType: string, overrides: Partial<DocumentWire> = {}): DocumentWire {
  return {
    id: 1,
    kbId: 1,
    fileName: `file.${fileType.toLowerCase()}`,
    fileType,
    status: 'PROCESSED',
    errorMessage: null,
    fileSize: 1024,
    chunkCount: 1,
    importType: 'UPLOAD',
    s3File: null,
    fileContentHash: null,
    chunkStrategy: null,
    sourceFileProfile: null,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 伪 Blob：满足组件对二进制与文本读取的最小需求。 */
function fakeBlob(text = ''): Blob {
  return { text: () => Promise.resolve(text) } as unknown as Blob;
}

/**
 * 弹窗以 attach="body" 挂载到 body（TDesign 内部经 Teleport 渲染），内容不在挂载组件子树内，
 * 因此断言统一以 document.body 作为查询根。
 */
function page(): DOMWrapper<Element> {
  return new DOMWrapper(document.body);
}

/** 挂载对话框并触发打开（watch 仅在模型值由 false→true 时加载）。 */
async function openWith(doc: DocumentWire | null): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(DocReaderDialog, {
    props: { modelValue: false, document: null },
    global: { plugins: [TDesign] },
  });
  await wrapper.setProps({ document: doc });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  mountedWrappers.push(wrapper);
  return wrapper;
}

/** 查找指定文案的按钮（在 body 内检索，兼容弹窗挂载到 body）。 */
function findButton(text: string) {
  const button = page().findAll('button').find((item) => item.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

describe('DocReaderDialog', () => {
  describe('Office 文档走前端渲染组件', () => {
    it('should 渲染 docx 组件且不再调用文本提取_when 打开 DOCX 文档_given 正常文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('DOCX'));

      // then
      expect(page().find('.stub-docx').exists()).toBe(true);
      expect(page().find('pre.doc-reader__text').exists()).toBe(false);
      expect(fetchBlobMock).toHaveBeenCalledWith(documentContentPath(1));
      expect(previewTextMock).not.toHaveBeenCalled();
    });

    it('should 渲染 excel 组件且不再调用文本提取_when 打开 XLSX 文档_given 正常文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('XLSX'));

      // then
      expect(page().find('.stub-excel').exists()).toBe(true);
      expect(fetchBlobMock).toHaveBeenCalledWith(documentContentPath(1));
      expect(previewTextMock).not.toHaveBeenCalled();
    });

    it('should 渲染 pptx 组件_when 打开 PPTX 文档_given 正常文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('PPTX'));

      // then
      expect(page().find('.stub-pptx').exists()).toBe(true);
      expect(fetchBlobMock).toHaveBeenCalledWith(documentContentPath(1));
    });
  });

  describe('原生渲染分流', () => {
    it('should 使用 iframe 与「在新窗口打开」_when 打开 PDF 文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('PDF'));

      // then
      expect(page().find('iframe.doc-reader__frame').exists()).toBe(true);
      expect(page().text()).toContain('在新窗口打开');
    });

    it('should 使用 img 渲染_when 打开图片文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('PNG'));

      // then
      expect(page().find('.doc-reader__image img').exists()).toBe(true);
    });

    it('should 以等宽 pre 展示原文_when 打开文本类文档_given 正常文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob('hello world'));

      // when
      const wrapper = await openWith(makeDoc('MD'));

      // then
      expect(page().find('pre.doc-reader__text').text()).toContain('hello world');
      expect(previewTextMock).not.toHaveBeenCalled();
    });
  });

  describe('不支持预览的回退', () => {
    it('should 提示下载且不读取内容_when 打开老二进制 DOC 文档', async () => {
      // when
      const wrapper = await openWith(makeDoc('DOC'));

      // then
      expect(page().text()).toContain('不支持在线预览');
      expect(findButton('下载').exists()).toBe(true);
      expect(fetchBlobMock).not.toHaveBeenCalled();
      expect(previewTextMock).not.toHaveBeenCalled();
    });

    it('should 提示下载且不走 excel 渲染_when 打开老二进制 XLS 文档', async () => {
      // given（@vue-office/excel 内置 exceljs 仅支持 xlsx，老 BIFF 格式必须引导下载而非报错）

      // when
      const wrapper = await openWith(makeDoc('XLS'));

      // then
      expect(page().text()).toContain('该格式不支持在线预览');
      expect(page().find('.stub-excel').exists()).toBe(false);
      expect(fetchBlobMock).not.toHaveBeenCalled();
      expect(previewTextMock).not.toHaveBeenCalled();
      expect(findButton('下载').exists()).toBe(true);
    });

    it('should 提示超限并保留下载_when 打开超过 50MB 的文档_given 大文件', async () => {
      // when
      const wrapper = await openWith(makeDoc('DOCX', { fileSize: 60 * 1024 * 1024 }));

      // then
      expect(page().text()).toContain('文件较大');
      expect(page().find('.stub-docx').exists()).toBe(false);
      expect(fetchBlobMock).not.toHaveBeenCalled();
      expect(findButton('下载').exists()).toBe(true);
    });
  });

  describe('错误与重试', () => {
    it('should 展示错误并支持重试_when 加载原文失败_given 首次读取异常', async () => {
      // given
      fetchBlobMock.mockRejectedValueOnce(new Error('网络异常')).mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('DOCX'));

      // then
      expect(page().text()).toContain('文档加载失败');
      expect(page().text()).toContain('网络异常');

      // when 重试
      await findButton('重试').trigger('click');
      await flushPromises();

      // then
      expect(page().find('.stub-docx').exists()).toBe(true);
      expect(fetchBlobMock).toHaveBeenCalledTimes(2);
    });
  });

  describe('契约与资源释放', () => {
    it('should 保留「在线阅读」标题与「关闭/下载」页脚_when 打开任意文档', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());

      // when
      const wrapper = await openWith(makeDoc('TXT'));

      // then
      expect(page().text()).toContain('在线阅读：file.txt');
      expect(findButton('关闭').exists()).toBe(true);
      expect(findButton('下载').exists()).toBe(true);
    });

    it('should 释放 object URL_when 关闭对话框_given 已加载二进制预览', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());
      const wrapper = await openWith(makeDoc('PDF'));
      expect(createObjectUrlMock).toHaveBeenCalled();

      // when
      await wrapper.setProps({ modelValue: false });

      // then
      expect(revokeObjectUrlMock).toHaveBeenCalledWith(expect.stringContaining('blob:mock-'));
    });
  });

  describe('下载交互', () => {
    it('should 触发下载_when 点击下载按钮', async () => {
      // given
      fetchBlobMock.mockResolvedValue(fakeBlob());
      const wrapper = await openWith(makeDoc('TXT'));

      // when
      await findButton('下载').trigger('click');
      await flushPromises();

      // then
      expect(downloadFileMock).toHaveBeenCalledTimes(1);
    });
  });
});