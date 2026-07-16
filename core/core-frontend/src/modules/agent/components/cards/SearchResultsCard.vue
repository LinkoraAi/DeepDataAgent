<template>
  <BaseCard title="搜索结果" icon="🔍" class="search-results-card">
    <div class="search-results-list">
      <div
        v-for="(result, index) in results"
        :key="index"
        class="search-result-item"
      >
        <div class="result-header">
          <span class="result-number">{{ index + 1 }}</span>
          <a
            :href="result.url"
            target="_blank"
            rel="noopener noreferrer"
            class="result-title"
            :title="result.title"
          >
            {{ result.title }}
          </a>
        </div>
        <p class="result-snippet" v-if="result.snippet">
          {{ result.snippet }}
        </p>
        <div class="result-url">
          <LinkIcon class="url-icon" />
          <a :href="result.url" target="_blank" rel="noopener noreferrer">
            {{ result.url }}
          </a>
        </div>
      </div>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import { LinkIcon } from 'tdesign-icons-vue-next';
import BaseCard from './BaseCard.vue';
import type { SearchResultItem } from '../../types';

defineProps<{
  results: SearchResultItem[];
}>();
</script>

<style scoped>
.search-results-card {
  margin-bottom: 16px;
}

.search-results-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-result-item {
  padding: 12px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 8px;
  transition: all 0.2s ease;
}

.search-result-item:hover {
  border-color: var(--td-brand-color);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.result-header {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
}

.result-number {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--td-brand-color-light);
  color: var(--td-brand-color);
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.result-title {
  flex: 1;
  color: var(--td-brand-color);
  font-weight: 500;
  font-size: 14px;
  line-height: 1.5;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.result-title:hover {
  text-decoration: underline;
}

.result-snippet {
  margin: 0 0 8px 28px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.result-url {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 28px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.url-icon {
  width: 12px;
  height: 12px;
}

.result-url a {
  color: var(--td-text-color-placeholder);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 400px;
}

.result-url a:hover {
  color: var(--td-brand-color);
}
</style>
