import { vi } from 'vitest';

// Mock TDesign 组件为简单原生元素
vi.mock('tdesign-vue-next', () => ({
  MessagePlugin: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

// Mock vue-echarts
vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    template: '<div class="v-chart-stub" />',
  },
}));

// Mock highlight.js
vi.mock('highlight.js/lib/core', () => ({
  default: {
    highlight: vi.fn((code: string) => ({ value: code })),
    registerLanguage: vi.fn(),
  },
}));

vi.mock('highlight.js/lib/languages/sql', () => ({
  default: {},
}));

// Mock dompurify
vi.mock('dompurify', () => ({
  default: {
    sanitize: vi.fn((html: string) => html),
  },
}));

// Mock @/shared/utils/markdown
vi.mock('@/shared/utils/markdown', () => ({
  renderMarkdown: vi.fn((text: string) => text),
}));

// Mock @/shared/utils/copy
vi.mock('@/shared/utils/copy', () => ({
  copyToClipboard: vi.fn(() => Promise.resolve(true)),
}));
