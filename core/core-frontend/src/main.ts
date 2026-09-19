import { createApp } from 'vue';
import { createPinia } from 'pinia';
import TDesign, { MessagePlugin } from 'tdesign-vue-next';
import TDesignChat from '@tdesign-vue-next/chat';
import App from './App.vue';
import router from './app/router';
import './shared/styles/index.css';
import 'tdesign-vue-next/es/style/index.css';

const app = createApp(App);

/** 兜底异常 → 可读消息文本。 */
function resolveErrorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}

// 全局异常兜底：组件渲染 / 生命周期异常与未处理的 Promise 拒绝统一 toast，防静默失败复发
app.config.errorHandler = (err) => {
  console.error('[global-error]', err);
  MessagePlugin.error(`页面运行异常: ${resolveErrorMessage(err)}`);
};
window.addEventListener('unhandledrejection', (event) => {
  console.error('[unhandled-rejection]', event.reason);
  MessagePlugin.error(`操作失败: ${resolveErrorMessage(event.reason)}`);
});

app.use(createPinia());
app.use(router);
app.use(TDesign);
app.use(TDesignChat);
app.mount('#app');
