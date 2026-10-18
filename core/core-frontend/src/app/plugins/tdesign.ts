import type { App } from 'vue';
import TDesign from 'tdesign-vue-next';
import 'tdesign-vue-next/es/style/index.css';

/**
 * TDesign Vue Next 全量注册（管理端组件用量大，全量引入换取确定性；
 * 生产构建由 Vite tree-shaking 与分包策略兜底）。
 */
export function setupTDesign(app: App): void {
  app.use(TDesign);
}
