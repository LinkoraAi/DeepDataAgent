<template>
  <PageSection title="环境管理" description="维护 Agent 会话绑定的运行环境（cloud 云沙箱 / self_hosted 自托管，仅登记数据契约）。">
    <t-card :bordered="true">
      <div class="env-toolbar">
        <t-button theme="primary" @click="openDialog(null)">新建环境</t-button>
        <t-button variant="outline" @click="reload">刷新</t-button>
      </div>

      <t-table
        :data="environments"
        :columns="columns"
        row-key="id"
        :loading="loading"
        :hover="true"
      >
        <template #type="{ row }">
          <t-tag v-if="isSelfHosted(row.config?.type)" theme="danger" variant="light">
            {{ row.config?.type }}（不可执行）
          </t-tag>
          <t-tag v-else theme="brand" variant="light">{{ row.config?.type }}</t-tag>
        </template>
        <template #summary="{ row }">
          <span class="env-spec">{{ configSummary(row.config) }}</span>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openDialog(row)">编辑</t-button>
            <t-popconfirm content="删除后不可恢复，绑定该环境的新会话将无法创建，确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
      <div v-if="hasMore" class="env-more">
        <t-button variant="text" theme="primary" :loading="loadingMore" @click="loadMore">加载更多</t-button>
      </div>
    </t-card>

    <!-- 新建 / 编辑对话框（编辑为全量替换，metadata 原样回传） -->
    <t-dialog
      v-model:visible="dialogVisible"
      :header="editTarget ? `编辑环境（${editTarget.name}）` : '新建环境'"
      width="640px"
      :confirm-btn="{ content: '保存', loading: saving }"
      :cancel-btn="{}"
      @confirm="handleSave"
    >
      <t-form :data="form" :rules="rules" layout="vertical" label-align="left">
        <t-form-item label="名称" name="name">
          <t-input v-model="form.name" placeholder="环境名称" />
        </t-form-item>
        <t-form-item label="描述" name="description">
          <t-textarea v-model="form.description" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选" />
        </t-form-item>
        <t-form-item label="环境类型" name="type">
          <t-select v-model="form.type" :options="typeOptions" placeholder="cloud / self_hosted" />
        </t-form-item>
        <t-form-item label="准备脚本（setup_script，≤64KB）" name="setupScript">
          <t-textarea v-model="form.setupScript" :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder="沙箱准备阶段执行脚本（可空），如 pip install -r requirements.txt" />
        </t-form-item>
        <template v-if="!selfHosted">
          <div class="pkg-grid">
            <t-form-item v-for="manager in REQUEST_PACKAGE_MANAGERS" :key="manager" :label="`${manager} 包`">
              <t-input v-model="form.packages[manager]" placeholder="逗号分隔，如 pandas==2.1.4" />
            </t-form-item>
          </div>
          <p class="pkg-hint">请求侧仅接受 apt / npm / pip 三键；包版本须显式锁定（pip 用 ==，npm 支持 scoped 与 @版本）；self_hosted 类型不携带包声明。</p>
        </template>
      </t-form>
      <p v-if="dialogError" class="dialog-error">{{ dialogError }}</p>
    </t-dialog>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  REQUEST_PACKAGE_MANAGERS,
  createEnvironment,
  deleteEnvironment,
  emptyPackages,
  isSelfHosted,
  listEnvironments,
  updateEnvironment,
  type EnvironmentConfigDto,
  type EnvironmentConfigPayload,
  type EnvironmentDto,
  type EnvironmentPackagesDto,
  type EnvironmentPackagesPayload,
  type EnvironmentPayload,
  type RequestPackageManager,
} from '../api/environments';

/** 表单内包声明以逗号分隔文本承载，保存时解析为三键请求数组。 */
type PackagesForm = Record<RequestPackageManager, string>;

const columns: PrimaryTableCol<EnvironmentDto>[] = [
  { colKey: 'name', title: '名称', ellipsis: true },
  { colKey: 'type', title: '类型', width: 160 },
  { colKey: 'description', title: '描述', ellipsis: true },
  { colKey: 'summary', title: '配置概要' },
  { colKey: 'created_at', title: '创建时间', width: 180 },
  { colKey: 'op', title: '操作', width: 140 },
];

const typeOptions = [
  { label: 'cloud（云沙箱）', value: 'cloud' },
  { label: 'self_hosted（自托管，仅登记）', value: 'self_hosted' },
];

const environments = ref<EnvironmentDto[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(false);
const lastId = ref('');

const dialogVisible = ref(false);
const saving = ref(false);
const dialogError = ref('');
const editTarget = ref<EnvironmentDto | null>(null);

const form = reactive({
  name: '',
  description: '',
  type: 'cloud',
  setupScript: '',
  packages: emptyTextPackages(),
});

const selfHosted = computed(() => isSelfHosted(form.type));

// 包文本按逗号（含中文逗号）/空白切分为数组（非法格式由后端不变量兜底报 400）
function splitPackages(text: string): string[] {
  return text.split(/[,，\s]+/).map((item) => item.trim()).filter((item) => item.length > 0);
}

function emptyTextPackages(): PackagesForm {
  return { apt: '', npm: '', pip: '' };
}

/** 列表「配置概要」：仅统计参与装配的三键包数量 + 是否含准备脚本。 */
function configSummary(config?: EnvironmentConfigDto): string {
  if (!config) {
    return '—';
  }
  const packages: EnvironmentPackagesDto = config.packages ?? emptyPackages();
  const parts = REQUEST_PACKAGE_MANAGERS
    .map((manager) => ({ manager, count: packages[manager]?.length ?? 0 }))
    .filter((entry) => entry.count > 0)
    .map((entry) => `${entry.manager}×${entry.count}`);
  const packageText = parts.length > 0 ? parts.join(' ') : '无包声明';
  return `${packageText} · 脚本${config.setup_script ? '已配置' : '无'}`;
}

const rules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '环境名称不能为空' }],
  type: [{ required: true, message: '环境类型不能为空' }],
};

/** 打开新建 / 编辑对话框（编辑回填 description / config 结构化字段与 metadata）。 */
function openDialog(target: EnvironmentDto | null): void {
  editTarget.value = target;
  dialogError.value = '';
  if (target) {
    form.name = target.name;
    form.description = target.description ?? '';
    form.type = target.config?.type ?? 'cloud';
    form.setupScript = target.config?.setup_script ?? '';
    const packages: EnvironmentPackagesDto = target.config?.packages ?? emptyPackages();
    for (const manager of REQUEST_PACKAGE_MANAGERS) {
      form.packages[manager] = (packages[manager] ?? []).join(', ');
    }
  } else {
    form.name = '';
    form.description = '';
    form.type = 'cloud';
    form.setupScript = '';
    form.packages = emptyTextPackages();
  }
  dialogVisible.value = true;
}

async function handleSave(): Promise<void> {
  if (!form.name.trim()) {
    dialogError.value = '环境名称为必填项';
    return;
  }
  saving.value = true;
  dialogError.value = '';
  try {
    const config: EnvironmentConfigPayload = {
      type: form.type,
      setup_script: form.setupScript.trim() ? form.setupScript : null,
    };
    // self_hosted 不携带包声明（后端不变量：self_hosted + 非空 packages 拒绝）
    if (!selfHosted.value) {
      const packages: EnvironmentPackagesPayload = {};
      for (const manager of REQUEST_PACKAGE_MANAGERS) {
        packages[manager] = splitPackages(form.packages[manager]);
      }
      config.packages = packages;
    }
    const payload: EnvironmentPayload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      config,
      // 更新为全量替换：metadata 原样回传避免被清空
      metadata: editTarget.value?.metadata ?? {},
    };
    if (editTarget.value) {
      await updateEnvironment(editTarget.value.id, payload);
    } else {
      await createEnvironment(payload);
    }
    dialogVisible.value = false;
    await reload();
  } catch (e) {
    dialogError.value = (e as Error).message;
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: EnvironmentDto): Promise<void> {
  try {
    await deleteEnvironment(row.id);
  } catch (e) {
    MessagePlugin.error(`删除失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

/** 游标重载（append=true 时按 after_id 续拉并拼接当页数据）。 */
async function reload(append = false): Promise<void> {
  if (append) {
    loadingMore.value = true;
  } else {
    loading.value = true;
  }
  try {
    const page = await listEnvironments({
      limit: 20,
      afterId: append ? lastId.value || undefined : undefined,
    });
    environments.value = append ? [...environments.value, ...page.data] : page.data;
    hasMore.value = page.has_more;
    lastId.value = page.last_id ?? '';
  } catch (e) {
    MessagePlugin.error(`环境列表加载失败: ${(e as Error).message}`);
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function loadMore(): void {
  void reload(true);
}

onMounted(() => {
  void reload();
});
</script>

<style scoped>
.env-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.env-more {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.env-spec {
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.pkg-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.pkg-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.dialog-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}
</style>