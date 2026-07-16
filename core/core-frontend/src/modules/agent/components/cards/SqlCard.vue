<template>
  <BaseCard title="SQL 查询" icon="📊" :default-expanded="true">
    <div class="sql-card__content">
      <div class="sql-card__actions">
        <t-button theme="default" variant="text" size="small" @click="copySQL">
          <template #icon><copy-icon /></template>
          复制
        </t-button>
      </div>
      <pre class="sql-card__code"><code v-html="highlightedSQL"></code></pre>
    </div>
  </BaseCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { CopyIcon } from 'tdesign-icons-vue-next';
import { MessagePlugin } from 'tdesign-vue-next';
import hljs from 'highlight.js/lib/core';
import sql from 'highlight.js/lib/languages/sql';
import DOMPurify from 'dompurify';
import { copyToClipboard } from '@/shared/utils/copy';
import BaseCard from './BaseCard.vue';

hljs.registerLanguage('sql', sql);

interface Props {
  sql: string;
}

const props = defineProps<Props>();

const highlightedSQL = computed(() => {
  if (!props.sql) return '';
  try {
    const raw = hljs.highlight(props.sql, { language: 'sql' }).value;
    return DOMPurify.sanitize(raw);
  } catch {
    return props.sql;
  }
});

async function copySQL() {
  if (props.sql) {
    await copyToClipboard(props.sql);
    MessagePlugin.success('SQL 已复制到剪贴板');
  }
}
</script>

<style scoped lang="less">
.sql-card {
  &__content {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
  }

  &__code {
    margin: 0;
    padding: 16px;
    background: var(--td-bg-color-secondarycontainer);
    border-radius: 6px;
    overflow-x: auto;
    font-family: 'Courier New', monospace;
    font-size: 13px;
    line-height: 1.6;

    code {
      font-family: inherit;
    }
  }
}
</style>
