<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import PageHeader from '@/shared/components/PageHeader.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { KB_LIFECYCLE } from '@/shared/enums/dict';
import { errorMessage } from '@/shared/composables/useAsync';
import { usePolling } from '@/shared/composables/usePolling';
import { ApiError } from '@/shared/types/api';
import { getModelProfile } from '@/modules/model/api';
import { getKnowledgeBase } from '../api/knowledgeBase';
import type { KnowledgeBaseWire } from '../api/knowledgeBase';
import { extractKbChunkStrategy } from '../mappers.documents';
import KbFormDrawer from '../components/KbFormDrawer.vue';
import DocTable from '../components/doc/DocTable.vue';
import ChunkPanel from '../components/chunk/ChunkPanel.vue';
import RetrievalTester from '../components/retrieval/RetrievalTester.vue';
import QaChatPane from '../components/chat/QaChatPane.vue';

/**
 * 知识库详情页骨架：头部（返回 / 名称 / 生命周期 / 嵌入模型回显 / 失败留痕 / 更新时间 / 编辑入口）
 * 与四个 Tab（原始文档 / 切片详情 / 知识检索 / 知识问答）。
 * <p>四个 Tab 均接真实子页（文档 / 切片 / 检索调试 / 知识问答）；
 * 详情加载与 DELETING 轮询、编辑抽屉保持完整可用。</p>
 */
const props = defineProps<{ kbId: string }>();
const router = useRouter();

/** 详情数据 */
const kb = ref<KnowledgeBaseWire | null>(null);
/** 加载中 */
const loading = ref(false);
/** 错误文案（非 404 的加载失败） */
const errorText = ref('');
/** 资源不存在 / 已被删除（404） */
const notFound = ref(false);
/** 嵌入模型显示名回显 */
const embeddingModelName = ref('');
/** 当前激活 Tab */
const activeTab = ref('documents');
/** 编辑抽屉可见性 */
const drawerVisible = ref(false);

const isActive = computed(() => kb.value?.lifecycleStatus === 'ACTIVE');
const isDeleting = computed(() => kb.value?.lifecycleStatus === 'DELETING');

/**
 * 库级分块策略 JSON 原文（自 ragEngineConfig 提取，design D7）。
 * <p>下传给文档表格用于「继承库级」展示与策略编辑弹窗默认值；
 * 详情加载与编辑保存刷新 kb 后由 computed 自动同步，无额外请求。</p>
 */
const kbChunkStrategy = computed(() => extractKbChunkStrategy(kb.value?.ragEngineConfig ?? null));

/** 从 embeddingConfig JSON 中防御式解析嵌入模型 profileId */
function parseEmbeddingProfileId(raw: string | null): string {
  if (!raw) {
    return '';
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed !== null && typeof parsed === 'object') {
      const id = (parsed as Record<string, unknown>).modelProfileId;
      return typeof id === 'string' ? id : '';
    }
  } catch {
    // 脏数据回退空串
  }
  return '';
}

/** 回显嵌入模型显示名（引用 DISABLED/已删除模型时回落为 profileId 原值） */
async function resolveEmbeddingName(profileId: string): Promise<void> {
  if (profileId === '') {
    embeddingModelName.value = '';
    return;
  }
  try {
    const profile = await getModelProfile(profileId);
    embeddingModelName.value = profile.displayName;
  } catch {
    embeddingModelName.value = profileId;
  }
}

/** 加载详情（返回是否为删除中，供轮询续跑判定） */
async function loadDetail(): Promise<boolean> {
  try {
    const data = await getKnowledgeBase(Number(props.kbId));
    kb.value = data;
    notFound.value = false;
    errorText.value = '';
    await resolveEmbeddingName(parseEmbeddingProfileId(data.embeddingConfig));
    return data.lifecycleStatus === 'DELETING';
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // 删除已收口（行物理缺失）或库不存在
      notFound.value = true;
      kb.value = null;
      return false;
    }
    errorText.value = errorMessage(e);
    return false;
  }
}

/** 删除中轮询：库进入 DELETING 后每 4s 刷新，直到消失（404）或转为 DELETE_FAILED 停止 */
const polling = usePolling(() => loadDetail(), 4000);

/** 打开编辑抽屉 */
function openEdit(): void {
  drawerVisible.value = true;
}

/** 编辑保存后刷新详情 */
function onSaved(saved: KnowledgeBaseWire): void {
  kb.value = saved;
  void resolveEmbeddingName(parseEmbeddingProfileId(saved.embeddingConfig));
  if (saved.lifecycleStatus === 'DELETING') {
    polling.start();
  }
}

/** 返回列表 */
function backToList(): void {
  router.push({ name: 'kb-list' });
}

/** 格式化时间为 YYYY-MM-DD HH:mm */
function formatTime(iso: string | null): string {
  if (!iso) {
    return '-';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

onMounted(async () => {
  loading.value = true;
  try {
    const deleting = await loadDetail();
    if (deleting) {
      polling.start();
    }
  } finally {
    loading.value = false;
  }
  if (notFound.value) {
    MessagePlugin.warning('知识库不存在或已删除');
  }
});
</script>

<template>
  <div class="dda-page">
    <EmptyGuide v-if="notFound" description="该知识库不存在或已被删除">
      <template #action>
        <t-button theme="primary" @click="backToList">返回知识库列表</t-button>
      </template>
    </EmptyGuide>

    <template v-else>
      <PageHeader :title="kb?.name ?? '知识库详情'" :subtitle="kb?.description || ''" icon="folder">
        <template #actions>
          <t-button variant="outline" @click="backToList">
            <t-icon name="chevron-left" />
            返回列表
          </t-button>
          <t-button theme="primary" :disabled="!isActive" @click="openEdit">编辑</t-button>
        </template>
      </PageHeader>

      <div v-if="errorText" class="kb-detail__error">{{ errorText }}</div>

      <div class="kb-detail__meta">
        <StatusTag :code="kb?.lifecycleStatus" :dict="KB_LIFECYCLE" />
        <span class="kb-detail__meta-item">
          嵌入模型：<b>{{ embeddingModelName || '-' }}</b>
        </span>
        <span class="kb-detail__meta-item">语言：{{ kb?.language || '-' }}</span>
        <span class="kb-detail__meta-item">更新时间：{{ formatTime(kb?.updatedAt ?? null) }}</span>
      </div>

      <t-alert
        v-if="kb?.lifecycleStatus === 'DELETE_FAILED' && kb?.errorMessage"
        theme="error"
        :message="`知识库删除失败：${kb.errorMessage}`"
        description="请重新发起删除以继续清退。"
      />
      <t-alert
        v-else-if="isDeleting"
        theme="warning"
        message="知识库正在删除中，清退任务异步推进，页面将自动刷新直至消失……"
      />

      <t-tabs v-model="activeTab" class="kb-detail__tabs">
        <t-tab-panel value="documents" label="原始文档">
          <DocTable :kb-id="Number(props.kbId)" :kb-chunk-strategy="kbChunkStrategy" />
        </t-tab-panel>
        <t-tab-panel value="chunks" label="切片详情">
          <ChunkPanel :kb-id="Number(props.kbId)" />
        </t-tab-panel>
        <t-tab-panel value="retrieval" label="知识检索">
          <RetrievalTester :kb-id="Number(props.kbId)" />
        </t-tab-panel>
        <t-tab-panel value="qa" label="知识问答">
          <QaChatPane :kb-id="Number(props.kbId)" />
        </t-tab-panel>
      </t-tabs>
    </template>

    <KbFormDrawer v-model="drawerVisible" :kb="kb" @saved="onSaved" />
  </div>
</template>

<style scoped>
.kb-detail__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 18px;
  margin-bottom: 16px;
  padding: 14px 20px;
  font-size: 13px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-large);
  box-shadow: var(--dda-card-shadow);
}

.kb-detail__meta-item {
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.kb-detail__meta-item b {
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

.kb-detail__error {
  margin-bottom: 12px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--td-error-color, #d54941);
  background: var(--td-error-color-1, #fdecee);
  border-radius: 4px;
}

.kb-detail__tabs {
  margin-top: 8px;
}
</style>
