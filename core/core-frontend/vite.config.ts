import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        /**
         * 手动分包：TDesign 全家桶与文档预览组件（vue-office 系）是两大稳定重依赖，
         * 各自独立成 chunk 以利用浏览器缓存并消除 rollup 单 chunk 超限告警；
         * 其余三方库归入 vendor。业务页面仍走路由懒加载动态分包。
         */
        manualChunks(id: string) {
          if (!id.includes('node_modules')) {
            return undefined;
          }
          if (id.includes('tdesign')) {
            return 'vendor-tdesign';
          }
          if (id.includes('@vue-office')) {
            return 'vendor-office';
          }
          return 'vendor';
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
});
