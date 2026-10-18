import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

/**
 * Vitest 配置：以纯逻辑用例（http 解包、SSE 归约、DTO 装配、SHA-256、分页适配、枚举映射）为主，
 * 同时支持共享组件的轻量交互用例；默认 node 环境，需要 DOM 的用例在文件内用
 * `// @vitest-environment jsdom` 单独声明。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
