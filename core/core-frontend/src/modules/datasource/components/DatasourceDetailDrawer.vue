<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import {
  deleteDatasource,
  disableDatasource,
  enableDatasource,
  getDatasource,
  syncDatasource,
  testSavedDatasource,
} from '@/modules/datasource/api/datasource';
import type { DatasourceResponse, DatasourceTypeResponse } from '@/modules/datasource/api/datasource';
import { listApiSchemas } from '@/modules/datasource/api/apiSchema';
import type { ApiSchemaResponse } from '@/modules/datasource/api/apiSchema';
import { errorMessage } from '@/shared/composables/useAsync';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { DATASOURCE_STATUS, dictLabel } from '@/shared/enums/dict';
import { LAYER } from '@/shared/constants/layers';
import StatusTag from '@/shared/components/StatusTag.vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import RowActions from '@/shared/components/RowActions.vue';
import type { RowActionItem } from '@/shared/components/RowActions.vue';
import { subTypeOptions } from '@/modules/datasource/mappers';
import ApiSchemaDrawer from './ApiSchemaDrawer.vue';
import TablesDialog from './TablesDialog.vue';
import PreviewDialog from './PreviewDialog.vue';

/**
 * 数据源详情右侧抽屉。
 * <p>承载数据源详情的全部只读信息与行级动作：测试连接 / 编辑 / 同步元数据 / 浏览表 /
 * 启用禁用 / 删除，以及 API 数据源的数据表清单（新增 / 编辑 / 预览）。
 * 编辑表单与新建流程仍由列表页持有，本组件通过 {@code edit} 事件外抛；
 * 启用 / 禁用 / 同步成功后内部拉取最新详情并外抛 {@code changed} 同步列表；
 * 删除成功外抛 {@code deleted}（列表页关闭抽屉并刷新）。密码任何位置不回显。</p>
 */
const props = defineProps<{
  /** 当前展示的数据源（null 时抽屉不渲染内容） */
  datasource: DatasourceResponse | null;
  /** 数据源类型字典（子类型中文名解析） */
  types: DatasourceTypeResponse[];
}>();

const emit = defineEmits<{
  /** 点击编辑：列表页据此打开 JDBC / API 编辑抽屉 */
  (e: 'edit', datasource: DatasourceResponse): void;
  /** 启用 / 禁用 / 同步成功：列表页据此刷新列表，payload 为最新详情 */
  (e: 'changed', datasource: DatasourceResponse): void;
  /** 删除成功：列表页据此关闭抽屉并刷新列表 */
  (e: 'deleted', id: number): void;
}>();

/** 抽屉显隐（v-model:visible） */
const visible = defineModel<boolean>('visible', { default: false });

/** 抽屉内最新详情副本（动作后就地刷新，不直接改 prop） */
const ds = ref<DatasourceResponse | null>(null);
/** API 数据表清单 */
const apiSchemas = ref<ApiSchemaResponse[]>([]);
/** 正在执行的动作名（按钮 loading，防重复点击） */
const pendingAction = ref('');
/** 嵌套抽屉：API 数据表编辑 */
const apiSchemaDrawer = reactive<{ visible: boolean; schemaId: number | null }>({ visible: false, schemaId: null });
/** 嵌套弹窗：JDBC 浏览表（元数据浏览） */
const tablesDialogVisible = ref(false);
/** 嵌套弹窗：表数据预览 */
const previewDialog = reactive<{ visible: boolean; tableName: string }>({ visible: false, tableName: '' });

const isEnabled = computed(() => ds.value?.status === 'ENABLED');
const isDisabled = computed(() => ds.value?.status === 'DISABLED');
const subTypeLabel = computed(() => {
  const st = ds.value?.subType;
  if (!st) {
    return '-';
  }
  return subTypeOptions(props.types).find((t) => t.subType === st)?.name ?? st;
});

/** ISO 时间格式化（非法值原样回显） */
function fmtTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-';
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false });
}

/** 加载 API 数据源的数据表清单（失败不清空已有数据，仅提示） */
async function loadApiSchemas(connectionId: number): Promise<void> {
  try {
    apiSchemas.value = await listApiSchemas(connectionId);
  } catch (e) {
    apiSchemas.value = [];
    MessagePlugin.error(errorMessage(e));
  }
}

/** 抽屉数据源切换（首次打开 / 编辑保存后回灌）时同步本地副本与数据表清单 */
watch(
  () => props.datasource,
  async (next) => {
    ds.value = next;
    apiSchemas.value = [];
    if (next?.type === 'API') {
      await loadApiSchemas(next.id);
    }
  },
  { immediate: true },
);

/** 动作结束后拉取最新详情并通知列表页刷新 */
async function refreshAfterAction(id: number): Promise<void> {
  const fresh = await getDatasource(id);
  ds.value = fresh;
  emit('changed', fresh);
  if (fresh.type === 'API') {
    await loadApiSchemas(id);
  }
}

async function onTest(): Promise<void> {
  if (!ds.value || pendingAction.value) {
    return;
  }
  pendingAction.value = 'test';
  try {
    const res = await testSavedDatasource(ds.value.id);
    if (res.success) {
      MessagePlugin.success(res.message || '连接成功');
    } else {
      MessagePlugin.error(res.message || '连接失败');
    }
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingAction.value = '';
  }
}

async function onEnable(): Promise<void> {
  if (!ds.value || pendingAction.value) {
    return;
  }
  pendingAction.value = 'enable';
  try {
    await enableDatasource(ds.value.id);
    MessagePlugin.success('已启用');
    await refreshAfterAction(ds.value.id);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingAction.value = '';
  }
}

async function onDisable(): Promise<void> {
  if (!ds.value || pendingAction.value) {
    return;
  }
  pendingAction.value = 'disable';
  try {
    await disableDatasource(ds.value.id);
    MessagePlugin.success('已禁用');
    await refreshAfterAction(ds.value.id);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingAction.value = '';
  }
}

async function onSync(): Promise<void> {
  if (!ds.value || pendingAction.value) {
    return;
  }
  pendingAction.value = 'sync';
  try {
    await syncDatasource(ds.value.id);
    MessagePlugin.success('同步完成');
    await refreshAfterAction(ds.value.id);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    pendingAction.value = '';
  }
}

async function onDelete(): Promise<void> {
  if (!ds.value) {
    return;
  }
  const target = ds.value;
  const confirmed = await confirmDangerous({
    header: `删除数据源：${target.name}`,
    body: '删除将级联清理其元数据与 API 表配置，操作不可撤销。',
    theme: 'danger',
    confirmText: '确认删除',
  });
  if (!confirmed) {
    return;
  }
  try {
    await deleteDatasource(target.id);
    MessagePlugin.success('已删除');
    visible.value = false;
    emit('deleted', target.id);
  } catch (e) {
    // 非禁用态删除 409 message 直显
    MessagePlugin.error(errorMessage(e));
  }
}

function onEdit(): void {
  if (ds.value) {
    emit('edit', ds.value);
  }
}

function openTables(): void {
  tablesDialogVisible.value = true;
}

function openAddApiSchema(): void {
  apiSchemaDrawer.schemaId = null;
  apiSchemaDrawer.visible = true;
}

function openEditApiSchema(schemaId: number): void {
  apiSchemaDrawer.schemaId = schemaId;
  apiSchemaDrawer.visible = true;
}

function openPreviewTable(tableName: string): void {
  previewDialog.tableName = tableName;
  previewDialog.visible = true;
}

/** API 数据表操作项：编辑内联，预览收进「更多」（仅启用态可预览）。 */
function apiSchemaActions(): RowActionItem[] {
  return [
    { key: 'edit', label: '编辑', theme: 'primary', tip: '编辑该 API 数据表定义' },
    { key: 'preview', label: '预览', tip: '仅启用态可预览接口返回数据', disabled: isDisabled.value },
  ];
}

/** API 数据表操作分发。 */
function onApiSchemaAction(schema: ApiSchemaResponse, key: string): void {
  if (key === 'preview') {
    openPreviewTable(schema.name);
    return;
  }
  openEditApiSchema(schema.id);
}

async function onApiSchemaSaved(): Promise<void> {
  if (ds.value?.type === 'API') {
    await loadApiSchemas(ds.value.id);
  }
}
</script>

<template>
  <t-drawer
    v-model:visible="visible"
    :header="false"
    size="720px"
    :destroy-on-close="false"
    :z-index="LAYER.drawer"
    class="ds-detail-drawer"
  >
    <div v-if="ds" class="ds-detail">
      <!-- 头部：名称 / 状态 / 关闭入口 / 动作组 -->
      <div class="dda-section ds-detail__head">
        <div class="ds-detail__titlebar">
          <h3 class="ds-detail__title">{{ ds.name }}</h3>
          <StatusTag :code="ds.status" :dict="DATASOURCE_STATUS" />
          <t-button
            class="ds-detail__close"
            variant="text"
            shape="square"
            size="small"
            aria-label="关闭详情"
            @click="visible = false"
          >
            <t-icon name="close" />
          </t-button>
        </div>

        <div class="ds-actions">
          <t-button size="small" theme="primary" :loading="pendingAction === 'test'" @click="onTest">
            测试连接
          </t-button>
          <t-button size="small" @click="onEdit">编辑</t-button>
          <t-tooltip v-if="isDisabled" content="仅启用态可同步元数据">
            <t-button size="small" disabled>同步</t-button>
          </t-tooltip>
          <t-button v-else size="small" :loading="pendingAction === 'sync'" @click="onSync">同步</t-button>
          <t-tooltip v-if="isDisabled" content="仅启用态可浏览表与预览">
            <t-button size="small" disabled>浏览表</t-button>
          </t-tooltip>
          <t-button v-else size="small" @click="openTables">浏览表</t-button>
          <t-button
            v-if="isDisabled"
            size="small"
            theme="success"
            :loading="pendingAction === 'enable'"
            @click="onEnable"
          >
            启用
          </t-button>
          <t-button
            v-else
            size="small"
            theme="warning"
            :loading="pendingAction === 'disable'"
            @click="onDisable"
          >
            禁用
          </t-button>
          <t-tooltip v-if="isEnabled" content="仅禁用态可删除">
            <t-button size="small" theme="danger" disabled>删除</t-button>
          </t-tooltip>
          <t-button v-else size="small" theme="danger" @click="onDelete">删除</t-button>
        </div>
      </div>

      <!-- 基础信息 -->
      <div class="dda-section">
        <div class="ds-section-title">基础信息</div>
        <div class="dda-kv">
          <div class="dda-kv__k">类型 / 子类型</div>
          <div class="dda-kv__v">
            {{ ds.type }}<template v-if="ds.subType"> · {{ subTypeLabel }}</template>
          </div>
          <div class="dda-kv__k">状态</div>
          <div class="dda-kv__v">{{ dictLabel(DATASOURCE_STATUS, ds.status) }}</div>
          <div class="dda-kv__k">描述</div>
          <div class="dda-kv__v">{{ ds.description || '-' }}</div>
          <div class="dda-kv__k">创建时间</div>
          <div class="dda-kv__v">{{ fmtTime(ds.createdAt) }}</div>
          <div class="dda-kv__k">更新时间</div>
          <div class="dda-kv__v">{{ fmtTime(ds.updatedAt) }}</div>
        </div>
      </div>

      <!-- 连接配置（脱敏） -->
      <div class="dda-section">
        <div class="ds-section-title">连接配置（不含密码）</div>
        <div v-if="ds.jdbcConfig" class="dda-kv">
          <div class="dda-kv__k">主机</div>
          <div class="dda-kv__v">{{ ds.jdbcConfig.host }}</div>
          <div class="dda-kv__k">端口</div>
          <div class="dda-kv__v">{{ ds.jdbcConfig.port ?? '-' }}</div>
          <div class="dda-kv__k">数据库</div>
          <div class="dda-kv__v">{{ ds.jdbcConfig.database }}</div>
          <div class="dda-kv__k">用户名</div>
          <div class="dda-kv__v">{{ ds.jdbcConfig.username }}</div>
          <template v-if="ds.subType === 'POSTGRESQL'">
            <div class="dda-kv__k">Schema</div>
            <div class="dda-kv__v">{{ ds.jdbcConfig.schema || '-' }}</div>
          </template>
        </div>
        <div v-else class="dda-faint">API 数据源无 JDBC 连接配置。</div>
      </div>

      <!-- API 表清单 -->
      <div v-if="ds.type === 'API'" class="dda-section">
        <div class="ds-section-head">
          <div class="ds-section-title">API 数据表</div>
          <t-button size="small" theme="primary" @click="openAddApiSchema">
            <t-icon name="add" />
            添加数据表
          </t-button>
        </div>
        <EmptyGuide v-if="apiSchemas.length === 0" description="尚未配置 API 数据表" icon="api" />
        <div v-for="s in apiSchemas" :key="s.id" class="ds-api-item">
          <div class="ds-api-main" @click="openEditApiSchema(s.id)">
            <div class="ds-api-name dda-ellipsis">{{ s.name }}</div>
            <div class="ds-api-url dda-muted">
              <code class="ds-code">{{ s.method }}</code>
              {{ s.url }}
            </div>
          </div>
          <div class="ds-api-actions" @click.stop>
            <RowActions :actions="apiSchemaActions()" @select="(key: string) => onApiSchemaAction(s, key)" />
          </div>
        </div>
      </div>
    </div>

    <!-- 嵌套抽屉与弹窗 -->
    <ApiSchemaDrawer
      v-model:visible="apiSchemaDrawer.visible"
      :datasource="ds"
      :schema-id="apiSchemaDrawer.schemaId"
      @saved="onApiSchemaSaved"
    />
    <TablesDialog v-model:visible="tablesDialogVisible" :datasource="ds" />
    <PreviewDialog
      v-if="ds"
      v-model:visible="previewDialog.visible"
      :connection-id="ds.id"
      :table-name="previewDialog.tableName"
      :enabled="isEnabled"
    />
  </t-drawer>
</template>

<style scoped>
.ds-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ds-detail__head {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ds-detail__titlebar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.ds-detail__close {
  margin-left: auto;
  color: var(--td-text-color-secondary);
}

.ds-detail__title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--td-text-color-primary);
}

.ds-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--dda-card-border);
}

.ds-section-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.ds-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.ds-section-head .ds-section-title {
  margin-bottom: 0;
}

.ds-api-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  margin-top: 8px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
}

.ds-api-main {
  min-width: 0;
  cursor: pointer;
}

.ds-api-name {
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.ds-api-url {
  margin-top: 2px;
  font-size: 12px;
}

.ds-code {
  padding: 1px 6px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
  border-radius: var(--td-radius-small);
}

.ds-api-actions {
  flex-shrink: 0;
}
</style>
