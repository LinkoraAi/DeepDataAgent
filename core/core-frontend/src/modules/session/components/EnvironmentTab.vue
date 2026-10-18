<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { useAsync } from '@/shared/composables/useAsync';
import { listEnvironments } from '@/modules/environment/api';
import type { EnvironmentWire } from '@/modules/environment/api';

/**
 * 环境只读 Tab：按会话 environment_id 在环境列表中匹配并只读展示配置。
 * <p>后端无单环境按 ID 拉取端点，故 listEnvironments 后前端查找（MVP 一次取前 100 条）。
 * 缺失匹配（环境已删除 / 超出前 100 条）时仅显示 environment_id。MUST NOT 展示环境变量 / 密钥。</p>
 */

const props = defineProps<{
  /** 会话绑定的运行环境 ID */
  environmentId: string;
}>();

/** 环境列表（一次性拉取）。 */
const environments = ref<EnvironmentWire[]>([]);

const listTask = useAsync<EnvironmentWire[]>();

/** 加载环境列表并匹配（失败不阻断，回落仅显示 ID）。 */
async function load(): Promise<void> {
  const data = await listTask.run(async () => {
    const page = await listEnvironments({ limit: 100 });
    return page?.data ?? [];
  });
  environments.value = data ?? [];
}

onMounted(() => {
  void load();
});

/** 匹配到的环境（无则 undefined）。 */
const matched = computed<EnvironmentWire | undefined>(() =>
  environments.value.find((item) => item.id === props.environmentId),
);

/** 是否已完成首次加载；必须取 .value，否则 !Ref 恒为 false 导致内容区永不渲染。 */
const loaded = computed(() => !listTask.loading.value);

/** 类型中文标签。 */
function typeLabel(type: string | undefined): string {
  if (type === 'cloud') {
    return '云端托管';
  }
  if (type === 'self_hosted') {
    return '自托管';
  }
  return type || '未知';
}

/** 预装依赖分类计数文本（六类里仅 apt/pip/npm 可能非空）。 */
const packageSummary = computed<string>(() => {
  const packages = matched.value?.config?.packages;
  if (!packages) {
    return '无';
  }
  const parts: string[] = [];
  const groups: Array<[string, string[] | undefined]> = [
    ['apt', packages.apt],
    ['pip', packages.pip],
    ['npm', packages.npm],
  ];
  for (const [name, list] of groups) {
    if (Array.isArray(list) && list.length) {
      parts.push(`${name}: ${list.length} 项`);
    }
  }
  return parts.length ? parts.join('，') : '无';
});

/** setup_script 文本。 */
const setupScript = computed<string>(() => matched.value?.config?.setup_script ?? '');
</script>

<template>
  <div class="env-tab">
    <div v-if="!matched && loaded" class="env-tab__partial">
      <t-alert theme="warning" :message="`未在环境列表中匹配到该会话绑定的环境（可能已删除或超出展示范围）`" />
      <p class="env-tab__id">环境 ID：<code class="dda-id">{{ environmentId || '—' }}</code></p>
    </div>

    <t-skeleton v-else-if="!loaded" :row-col="[{ width: '100%' }, { width: '60%' }]" animated />

    <template v-else>
      <!-- 纵向描述列表：小号加粗标签 + 值，段间细线分隔 -->
      <div class="env-tab__list">
        <div class="env-tab__field">
          <span class="env-tab__label">名称</span>
          <span class="env-tab__value">{{ matched?.name || '—' }}</span>
        </div>
        <div class="env-tab__field">
          <span class="env-tab__label">类型</span>
          <span class="env-tab__value">{{ typeLabel(matched?.config?.type) }}</span>
        </div>
        <div class="env-tab__field">
          <span class="env-tab__label">描述</span>
          <span class="env-tab__value">{{ matched?.description || '—' }}</span>
        </div>
        <div class="env-tab__field">
          <span class="env-tab__label">预装依赖</span>
          <span class="env-tab__value">{{ packageSummary }}</span>
        </div>
        <div class="env-tab__field">
          <span class="env-tab__label">环境 ID</span>
          <span class="env-tab__value"><code class="dda-id">{{ matched?.id || environmentId }}</code></span>
        </div>
        <div class="env-tab__field">
          <span class="env-tab__label">准备脚本（setup_script）</span>
          <div class="env-tab__value">
            <pre v-if="setupScript" class="env-tab__pre">{{ setupScript }}</pre>
            <p v-else class="env-tab__empty">（未配置）</p>
          </div>
        </div>
      </div>

      <EmptyGuide v-if="matched?.archived_at" description="该环境已被归档（不影响已运行会话）" />
    </template>
  </div>
</template>

<style scoped>
.env-tab {
  height: 100%;
  padding: 16px;
  overflow-y: auto;
}

.env-tab__partial {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.env-tab__list {
  margin-bottom: 16px;
}

.env-tab__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--td-border-level-2-color);
}

.env-tab__field:first-child {
  padding-top: 0;
}

.env-tab__field:last-child {
  border-bottom: none;
}

.env-tab__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--td-text-color-secondary);
}

.env-tab__value {
  font-size: 13px;
  word-break: break-word;
  color: var(--td-text-color-primary);
}

.env-tab__id,
.env-tab__empty {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.env-tab__pre {
  max-height: 320px;
  margin: 0;
  padding: 8px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 6px;
}
</style>
