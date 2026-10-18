<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useAuthenticatedMedia } from '@/shared/composables/useAuthenticatedMedia';

/**
 * 命中切片附图缩略图。
 * <p>后端 modalFile 是在线预览端点的<b>相对 URL</b>（需 Bearer 头），&lt;img&gt; 直挂会 401，
 * 故统一走 useAuthenticatedMedia 取二进制转一次性 object URL。该组合式函数按组件作用域持有并
 * 在销毁时自动 revoke，因此结果列表中每条切片各挂一个本子组件实例（一实例一图）。</p>
 */
const props = defineProps<{
  /** 预览端点相对 URL（后端 ChunkReferenceResponse.modalFile） */
  path: string;
}>();

const { url, loading, error, load } = useAuthenticatedMedia();

/** 按路径加载鉴权图片 */
function loadMedia(path: string): void {
  if (path !== '') {
    void load(path);
  }
}

onMounted(() => loadMedia(props.path));
watch(() => props.path, loadMedia);
</script>

<template>
  <div class="chunk-thumb">
    <t-loading v-if="loading" size="small" text="图片加载中……" />
    <img v-else-if="url" :src="url" class="chunk-thumb__img" alt="切片附图" />
    <span v-else class="chunk-thumb__empty">{{ error || '图片暂不可预览' }}</span>
  </div>
</template>

<style scoped>
.chunk-thumb {
  display: flex;
  align-items: center;
  min-height: 40px;
  margin-top: 8px;
}

.chunk-thumb__img {
  max-width: 240px;
  max-height: 160px;
  object-fit: contain;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 4px;
  background: var(--td-bg-color-container, #fff);
}

.chunk-thumb__empty {
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}
</style>
