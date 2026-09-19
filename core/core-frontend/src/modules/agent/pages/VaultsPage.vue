<template>
  <PageSection title="保管库管理" description="维护 MCP 凭证保管库（static_bearer / mcp_oauth）；凭证明文只写不读，会话创建时按 vault_ids 挂载并注入沙箱数据面。">
    <t-card :bordered="true">
      <div class="vault-toolbar">
        <t-button theme="primary" @click="openCreateDialog">新建保管库</t-button>
        <t-button variant="outline" @click="reload()">刷新</t-button>
        <t-input
          v-model="nameKeyword"
          placeholder="按显示名称搜索"
          clearable
          class="vault-name-filter"
          @enter="reload()"
        />
        <t-button variant="outline" @click="reload()">搜索</t-button>
        <t-checkbox v-model="includeArchived" @change="reload()">含已归档</t-checkbox>
      </div>

      <t-table
        :data="vaults"
        :columns="columns"
        row-key="id"
        :loading="loading"
        :hover="true"
      >
        <template #status="{ row }">
          <t-tag v-if="row.archived_at" theme="default" variant="light">已归档</t-tag>
          <t-tag v-else theme="success" variant="light">活跃</t-tag>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" @click="openCredentialDrawer(row)">凭证</t-button>
            <t-popconfirm v-if="!row.archived_at" content="归档后其凭证不再参与运行时注入，确认归档？" @confirm="handleArchive(row)">
              <t-button size="small" variant="text" theme="warning">归档</t-button>
            </t-popconfirm>
            <t-popconfirm content="删除要求无历史 Session 引用（否则拒绝），将级联删除其全部凭证，确认删除？" @confirm="handleDelete(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
      <div v-if="hasMore" class="vault-more">
        <t-button variant="text" theme="primary" :loading="loadingMore" @click="loadMore">加载更多</t-button>
      </div>
    </t-card>

    <!-- 新建保管库对话框 -->
    <t-dialog
      v-model:visible="createVisible"
      header="新建保管库"
      width="520px"
      :confirm-btn="{ content: '创建', loading: creating }"
      :cancel-btn="{}"
      @confirm="handleCreate"
    >
      <t-form :data="createForm" :rules="createRules" layout="vertical" label-align="left">
        <t-form-item label="显示名称" name="displayName">
          <t-input v-model="createForm.displayName" placeholder="保管库显示名（≤255 字符）" />
        </t-form-item>
        <t-form-item label="元数据（JSON 文本，可空）" name="metadata">
          <t-textarea v-model="createForm.metadata" :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder='如 {"team": "data-platform"}（key ≤64 字符、单值 ≤512 字符）' />
        </t-form-item>
      </t-form>
      <p v-if="createError" class="dialog-error">{{ createError }}</p>
    </t-dialog>

    <!-- 凭证管理抽屉（列表 + 新增 + 校验 + 归档 / 轮换） -->
    <t-drawer
      v-model:visible="credentialVisible"
      :header="`凭证管理（${credentialVault?.display_name || credentialVault?.id || ''}）`"
      size="680px"
      :footer="false"
    >
      <div class="vault-toolbar">
        <t-button theme="primary" size="small" @click="openAddDialog">新增凭证</t-button>
        <t-button variant="outline" size="small" @click="reloadCredentials()">刷新</t-button>
        <t-input
          v-model="credentialUrlKeyword"
          placeholder="按 MCP 服务器 URL 搜索"
          clearable
          class="vault-name-filter"
          @enter="reloadCredentials()"
        />
        <t-checkbox v-model="credentialIncludeArchived" @change="reloadCredentials()">含已归档</t-checkbox>
      </div>
      <t-table :data="credentials" :columns="credentialColumns" row-key="id" :loading="credentialLoading" :hover="true">
        <template #authType="{ row }">
          <span>{{ row.auth?.type ?? '—' }}</span>
        </template>
        <template #mcpServerUrl="{ row }">
          <span class="ellipsis">{{ row.auth?.mcp_server_url ?? '—' }}</span>
        </template>
        <template #status="{ row }">
          <t-tag v-if="row.archived_at" theme="default" variant="light">已归档</t-tag>
          <t-tag v-else theme="success" variant="light">活跃</t-tag>
        </template>
        <template #op="{ row }">
          <div class="op-cell">
            <t-button size="small" variant="text" :disabled="!!row.archived_at || row.auth?.type !== 'mcp_oauth'"
              :loading="validating === row.id" @click="handleValidate(row)">
              校验
            </t-button>
            <t-button size="small" variant="text" :disabled="!!row.archived_at" @click="openRotateDialog(row)">
              轮换
            </t-button>
            <t-popconfirm v-if="!row.archived_at" content="归档后该凭证不再注入新 Session，确认归档？" @confirm="handleArchiveCredential(row)">
              <t-button size="small" variant="text" theme="warning">归档</t-button>
            </t-popconfirm>
            <t-popconfirm content="删除后该凭证不可见且不再参与会话挂载鉴权，确认删除？" @confirm="handleDeleteCredential(row)">
              <t-button size="small" variant="text" theme="danger">删除</t-button>
            </t-popconfirm>
          </div>
        </template>
      </t-table>
      <div v-if="credentialHasMore" class="vault-more">
        <t-button variant="text" theme="primary" :loading="credentialLoadingMore" @click="loadMoreCredentials">
          加载更多
        </t-button>
      </div>
      <p v-if="credentialActionError" class="dialog-error">{{ credentialActionError }}</p>
      <p v-if="validateResult" class="dialog-error">{{ validateResult }}</p>

      <!-- 新增凭证内联表单（秘密材料嵌套在 auth 子树；令牌只写不读） -->
      <t-form v-if="addVisible" :data="addForm" layout="vertical" label-align="left" class="credential-form">
        <t-form-item label="鉴权类型">
          <t-select v-model="addForm.authType" :options="authTypeOptions" />
        </t-form-item>
        <t-form-item label="MCP 服务器 URL">
          <t-input v-model="addForm.mcpServerUrl" placeholder="https://mcp.example.com/sse" />
        </t-form-item>
        <t-form-item :label="addForm.authType === 'mcp_oauth' ? '访问令牌（明文，保存后不可再读取）' : '令牌（明文，保存后不可再读取）'">
          <t-input v-model="addForm.token" type="password" placeholder="凭证令牌明文" />
        </t-form-item>
        <div class="op-cell">
          <t-button theme="primary" size="small" :loading="adding" @click="handleAddCredential">添加</t-button>
          <t-button variant="outline" size="small" @click="closeAddDialog">取消</t-button>
        </div>
        <p v-if="addError" class="dialog-error">{{ addError }}</p>
      </t-form>
    </t-drawer>

    <!-- 更新凭证对话框（merge 补丁：只提交要变更的字段；身份字段不可修改） -->
    <t-dialog
      v-model:visible="rotateVisible"
      :header="`轮换凭证（${rotateTarget?.auth?.mcp_server_url || rotateTarget?.id || ''}）`"
      width="560px"
      :confirm-btn="{ content: '保存', loading: rotating }"
      :cancel-btn="{}"
      @confirm="handleRotateCredential"
      @close="clearRotateSecrets"
    >
      <t-alert
        theme="info"
        message="合并补丁语义：只提交要变更的字段，未填写的字段保持原值；勾选「清除」会显式置空该字段。"
        class="form-alert"
      />
      <t-form :data="rotateForm" layout="vertical" label-align="left">
        <t-form-item :label="rotateTarget?.auth?.type === 'mcp_oauth' ? '新访问令牌（可选）' : '新令牌（必填）'">
          <t-input v-model="rotateForm.token" type="password" placeholder="新令牌明文" />
        </t-form-item>

        <template v-if="rotateTarget?.auth?.type === 'mcp_oauth'">
          <t-form-item label="到期时间（可选，ISO-8601）">
            <div class="rotate-field">
              <t-input v-model="rotateForm.expiresAt" placeholder="如 2026-12-31T23:59:59+08:00"
                :disabled="rotateForm.clearExpiresAt" />
              <t-checkbox v-model="rotateForm.clearExpiresAt">清除</t-checkbox>
            </div>
          </t-form-item>
          <template v-if="rotateHasRefreshConfig">
            <t-form-item label="新刷新令牌（可选，替换既有）">
              <t-input v-model="rotateForm.refreshToken" type="password" placeholder="refresh_token 明文" />
            </t-form-item>
            <t-form-item label="授权范围（可选，空格分隔）">
              <div class="rotate-field">
                <t-input v-model="rotateForm.scope" placeholder="如 mcp.read mcp.write"
                  :disabled="rotateForm.clearScope" />
                <t-checkbox v-model="rotateForm.clearScope">清除</t-checkbox>
              </div>
            </t-form-item>
            <t-form-item label="令牌端点鉴权方式（可选）">
              <t-select v-model="rotateForm.tokenEndpointAuthType" :options="tokenEndpointAuthOptions" clearable
                placeholder="不修改" />
            </t-form-item>
            <t-form-item v-if="rotateNeedsClientSecret" label="客户端密钥（明文，只写不读，必填）">
              <t-input v-model="rotateForm.clientSecret" type="password" placeholder="client_secret 明文" />
            </t-form-item>
          </template>
        </template>
      </t-form>
      <p v-if="rotateError" class="dialog-error">{{ rotateError }}</p>
    </t-dialog>
  </PageSection>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormRule, PrimaryTableCol } from 'tdesign-vue-next';
import PageSection from '@/shared/components/PageSection.vue';
import {
  addVaultCredential,
  archiveVault,
  archiveVaultCredential,
  createVault,
  deleteVault,
  deleteVaultCredential,
  listVaultCredentials,
  listVaults,
  updateVaultCredential,
  validateVaultCredential,
  VAULT_AUTH_TYPES,
  type UpdateVaultCredentialPayload,
  type VaultCredentialDto,
  type VaultCredentialValidationDto,
  type VaultDto,
} from '../api/vaults';

const columns: PrimaryTableCol<VaultDto>[] = [
  { colKey: 'display_name', title: '显示名称', ellipsis: true },
  { colKey: 'id', title: '保管库 ID', width: 300, ellipsis: true },
  { colKey: 'status', title: '状态', width: 90 },
  { colKey: 'created_at', title: '创建时间', width: 180 },
  { colKey: 'op', title: '操作', width: 190 },
];

const credentialColumns: PrimaryTableCol<VaultCredentialDto>[] = [
  { colKey: 'authType', title: '鉴权类型', width: 120 },
  { colKey: 'mcpServerUrl', title: 'MCP 服务器', ellipsis: true },
  { colKey: 'status', title: '状态', width: 90 },
  { colKey: 'op', title: '操作', width: 230 },
];

/** 鉴权类型下拉（值域复用 API 常量，避免与后端值域重复维护）。 */
const authTypeOptions = VAULT_AUTH_TYPES.map((type) => ({
  label: type === 'static_bearer' ? 'static_bearer（静态令牌）' : 'mcp_oauth（OAuth 令牌）',
  value: type,
}));

/** 令牌端点鉴权方式（与后端值域一致；none 之外需带客户端密钥）。 */
const tokenEndpointAuthOptions = [
  { label: 'none', value: 'none' },
  { label: 'client_secret_basic', value: 'client_secret_basic' },
  { label: 'client_secret_post', value: 'client_secret_post' },
];

const vaults = ref<VaultDto[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(false);
const lastId = ref('');
const nameKeyword = ref('');
/** 归档态过滤（后端 include_archived：false = 仅未归档，true = 不限含归档）。 */
const includeArchived = ref(false);

// —— 新建对话框 ——
const createVisible = ref(false);
const creating = ref(false);
const createError = ref('');
const createForm = reactive({ displayName: '', metadata: '' });
const createRules: Record<string, FormRule[]> = {
  displayName: [{ required: true, message: '显示名称不能为空' }],
};

// —— 凭证抽屉 ——
const credentialVisible = ref(false);
const credentialVault = ref<VaultDto | null>(null);
const credentials = ref<VaultCredentialDto[]>([]);
const credentialLoading = ref(false);
const credentialLoadingMore = ref(false);
const credentialHasMore = ref(false);
const credentialLastId = ref('');
const credentialUrlKeyword = ref('');
const credentialIncludeArchived = ref(false);
const validating = ref('');
/** 凭证校验结果展示槽（专供校验结论使用）。 */
const validateResult = ref('');
/** 凭证归档 / 删除等操作失败提示（与校验结论分槽，避免互相覆盖）。 */
const credentialActionError = ref('');
const addVisible = ref(false);
const adding = ref(false);
const addError = ref('');
const addForm = reactive({ authType: 'static_bearer', mcpServerUrl: '', token: '' });

// —— 轮换对话框（merge 补丁：未填字段不改，勾选「清除」显式置空） ——
const rotateVisible = ref(false);
const rotating = ref(false);
const rotateError = ref('');
const rotateTarget = ref<VaultCredentialDto | null>(null);
const rotateForm = reactive({
  token: '',
  expiresAt: '',
  clearExpiresAt: false,
  refreshToken: '',
  scope: '',
  clearScope: false,
  tokenEndpointAuthType: '',
  clientSecret: '',
});

/** 既有凭证是否带 refresh 配置（无配置不可补加，后端 400，故整段隐藏）。 */
const rotateHasRefreshConfig = computed(() => rotateTarget.value?.auth?.refresh != null);

/** 令牌端点鉴权选择非 none 时须带客户端密钥。 */
const rotateNeedsClientSecret = computed(
  () => rotateForm.tokenEndpointAuthType !== '' && rotateForm.tokenEndpointAuthType !== 'none',
);

/** 立即清除新增表单中的秘密字段（明文不驻留响应式状态）。 */
function clearAddSecrets(): void {
  addForm.token = '';
}

/** 立即清除轮换表单中的秘密字段（明文不驻留响应式状态）。 */
function clearRotateSecrets(): void {
  rotateForm.token = '';
  rotateForm.refreshToken = '';
  rotateForm.clientSecret = '';
}

function openCreateDialog(): void {
  createForm.displayName = '';
  createForm.metadata = '';
  createError.value = '';
  createVisible.value = true;
}

/** 解析元数据 JSON 文本为键值对象（空 = 不提交该键）。 */
function parseMetadataText(text: string): Record<string, unknown> | undefined {
  const trimmed = text.trim();
  return trimmed ? (JSON.parse(trimmed) as Record<string, unknown>) : undefined;
}

async function handleCreate(): Promise<void> {
  if (!createForm.displayName.trim()) {
    createError.value = '显示名称为必填项';
    return;
  }
  creating.value = true;
  createError.value = '';
  try {
    await createVault({
      display_name: createForm.displayName.trim(),
      metadata: parseMetadataText(createForm.metadata) ?? null,
    });
    createVisible.value = false;
    await reload();
  } catch (e) {
    createError.value = (e as Error).message;
  } finally {
    creating.value = false;
  }
}

async function handleArchive(row: VaultDto): Promise<void> {
  try {
    await archiveVault(row.id);
  } catch (e) {
    MessagePlugin.error(`归档失败: ${(e as Error).message}`);
    return;
  }
  await reload();
}

async function handleDelete(row: VaultDto): Promise<void> {
  try {
    await deleteVault(row.id);
  } catch (e) {
    window.alert(`删除失败：${(e as Error).message}`);
    return;
  }
  await reload();
}

function openCredentialDrawer(row: VaultDto): void {
  credentialVault.value = row;
  validateResult.value = '';
  credentialActionError.value = '';
  // 切换保管库时一并清掉可能残留的明文，避免秘密材料驻留响应式状态
  clearAddSecrets();
  clearRotateSecrets();
  addVisible.value = false;
  credentialUrlKeyword.value = '';
  credentialIncludeArchived.value = false;
  credentialVisible.value = true;
  void reloadCredentials();
}

/** 凭证列表游标加载（append=true 时按 after_id 续拉并拼接；过滤变更回到首页）。 */
async function reloadCredentials(append = false): Promise<void> {
  if (!credentialVault.value) {
    return;
  }
  if (append) {
    credentialLoadingMore.value = true;
  } else {
    credentialLoading.value = true;
  }
  try {
    const page = await listVaultCredentials(credentialVault.value.id, {
      limit: 20,
      name: credentialUrlKeyword.value.trim() || undefined,
      includeArchived: credentialIncludeArchived.value ? true : undefined,
      afterId: append ? credentialLastId.value || undefined : undefined,
    });
    credentials.value = append ? [...credentials.value, ...page.data] : page.data;
    credentialHasMore.value = page.has_more;
    credentialLastId.value = page.last_id ?? '';
  } catch (e) {
    // 失败即清空列表并复位游标：不得残留上次过滤条件的数据或以陈旧游标续拉
    credentials.value = [];
    credentialHasMore.value = false;
    credentialLastId.value = '';
    MessagePlugin.error(`凭证列表加载失败: ${(e as Error).message}`);
  } finally {
    credentialLoading.value = false;
    credentialLoadingMore.value = false;
  }
}

function loadMoreCredentials(): void {
  void reloadCredentials(true);
}

function openAddDialog(): void {
  addForm.authType = 'static_bearer';
  addForm.mcpServerUrl = '';
  addForm.token = '';
  addError.value = '';
  addVisible.value = true;
}

/** 取消新增：先清掉明文再收起内联表单。 */
function closeAddDialog(): void {
  clearAddSecrets();
  addVisible.value = false;
}

async function handleAddCredential(): Promise<void> {
  if (!credentialVault.value) {
    return;
  }
  if (!addForm.mcpServerUrl.trim() || !addForm.token.trim()) {
    addError.value = 'MCP 服务器 URL 与令牌为必填项';
    return;
  }
  const oauth = addForm.authType === 'mcp_oauth';
  adding.value = true;
  addError.value = '';
  try {
    await addVaultCredential(credentialVault.value.id, {
      auth: {
        type: addForm.authType,
        mcp_server_url: addForm.mcpServerUrl.trim(),
        // 按类型限定密钥字段名：static_bearer → token，mcp_oauth → access_token
        ...(oauth ? { access_token: addForm.token } : { token: addForm.token }),
      },
    });
    clearAddSecrets();
    addVisible.value = false;
    await reloadCredentials();
  } catch (e) {
    addError.value = (e as Error).message;
  } finally {
    adding.value = false;
  }
}

async function handleValidate(row: VaultCredentialDto): Promise<void> {
  if (!credentialVault.value) {
    return;
  }
  validating.value = row.id;
  validateResult.value = '';
  try {
    const result = await validateVaultCredential(credentialVault.value.id, row.id);
    validateResult.value = formatValidation(result);
  } catch (e) {
    validateResult.value = `校验请求失败: ${(e as Error).message}`;
  } finally {
    validating.value = '';
  }
}

/** 校验结论可读化（三态结论 + 四态刷新 + 仅探测失败时出现的 mcp_probe）。 */
function formatValidation(result: VaultCredentialValidationDto): string {
  const statusText: Record<string, string> = {
    valid: '校验通过',
    invalid: '凭证无效',
    unknown: '无法判定（连接错误或服务端异常，不代表凭证失效）',
  };
  const refreshText: Record<string, string> = {
    no_refresh_token: '无刷新令牌',
    succeeded: '令牌已刷新',
    failed: '刷新被拒绝',
    connect_error: '刷新连接失败',
  };
  const parts = [
    statusText[result.status] ?? result.status,
    refreshText[result.refresh?.status] ?? result.refresh?.status ?? '',
  ];
  if (result.mcp_probe) {
    const code = result.mcp_probe.http_response
      ? `HTTP ${result.mcp_probe.http_response.status_code}`
      : '无响应';
    parts.push(`MCP ${result.mcp_probe.method} 探测失败（${code}）`);
  }
  return parts.filter((part) => part !== '').join('；');
}

async function handleArchiveCredential(row: VaultCredentialDto): Promise<void> {
  if (!credentialVault.value) {
    return;
  }
  credentialActionError.value = '';
  try {
    await archiveVaultCredential(credentialVault.value.id, row.id);
    await reloadCredentials();
  } catch (e) {
    credentialActionError.value = `归档凭证失败: ${(e as Error).message}`;
  }
}

/** 删除凭证（逻辑删；确认后调用，删除成功即重载列表）。 */
async function handleDeleteCredential(row: VaultCredentialDto): Promise<void> {
  if (!credentialVault.value) {
    return;
  }
  credentialActionError.value = '';
  try {
    await deleteVaultCredential(credentialVault.value.id, row.id);
    await reloadCredentials();
  } catch (e) {
    credentialActionError.value = `删除凭证失败: ${(e as Error).message}`;
  }
}

function openRotateDialog(row: VaultCredentialDto): void {
  rotateTarget.value = row;
  rotateForm.token = '';
  rotateForm.expiresAt = '';
  rotateForm.clearExpiresAt = false;
  rotateForm.refreshToken = '';
  rotateForm.scope = '';
  rotateForm.clearScope = false;
  rotateForm.tokenEndpointAuthType = '';
  rotateForm.clientSecret = '';
  rotateError.value = '';
  rotateVisible.value = true;
}

/**
 * 装配 merge 补丁（只提交变更项）：
 * `static_bearer` 仅 `auth.token`；`mcp_oauth` 仅 `access_token` / `expires_at` / 刷新配置子树，
 * 身份字段（mcp_server_url、refresh.client_id / token_endpoint）一律不提交；勾选「清除」时提交显式 null。
 */
function buildRotatePayload(): UpdateVaultCredentialPayload | null {
  const target = rotateTarget.value;
  if (!target) {
    return null;
  }
  if (target.auth?.type !== 'mcp_oauth') {
    if (!rotateForm.token.trim()) {
      rotateError.value = '新令牌不能为空';
      return null;
    }
    return { auth: { type: 'static_bearer', token: rotateForm.token } };
  }

  const auth: NonNullable<UpdateVaultCredentialPayload['auth']> = { type: 'mcp_oauth' };
  if (rotateForm.token.trim()) {
    auth.access_token = rotateForm.token;
  }
  if (rotateForm.clearExpiresAt) {
    auth.expires_at = null;
  } else if (rotateForm.expiresAt.trim()) {
    auth.expires_at = rotateForm.expiresAt.trim();
  }
  const refresh: NonNullable<NonNullable<UpdateVaultCredentialPayload['auth']>['refresh']> = {};
  if (rotateForm.refreshToken.trim()) {
    refresh.refresh_token = rotateForm.refreshToken;
  }
  if (rotateForm.clearScope) {
    refresh.scope = null;
  } else if (rotateForm.scope.trim()) {
    refresh.scope = rotateForm.scope.trim();
  }
  if (rotateForm.tokenEndpointAuthType) {
    // 后端值对象不变量：非 none 方式必须携带客户端密钥，缺失必被 400 拒绝
    if (rotateNeedsClientSecret.value && !rotateForm.clientSecret.trim()) {
      rotateError.value = '令牌端点鉴权方式非 none 时必须填写客户端密钥';
      return null;
    }
    refresh.token_endpoint_auth = rotateNeedsClientSecret.value
      ? { type: rotateForm.tokenEndpointAuthType, client_secret: rotateForm.clientSecret }
      : { type: rotateForm.tokenEndpointAuthType };
  }
  if (Object.keys(refresh).length) {
    auth.refresh = refresh;
  }
  if (auth.access_token == null && auth.expires_at === undefined && auth.refresh == null) {
    rotateError.value = '至少提交一项变更（访问令牌 / 到期时间 / 刷新配置）';
    return null;
  }
  return { auth };
}

async function handleRotateCredential(): Promise<void> {
  if (!credentialVault.value || !rotateTarget.value) {
    return;
  }
  rotateError.value = '';
  const payload = buildRotatePayload();
  if (!payload) {
    return;
  }
  rotating.value = true;
  try {
    await updateVaultCredential(credentialVault.value.id, rotateTarget.value.id, payload);
    clearRotateSecrets();
    rotateVisible.value = false;
    await reloadCredentials();
  } catch (e) {
    rotateError.value = (e as Error).message;
  } finally {
    rotating.value = false;
  }
}

/** 游标重载（append=true 时按 after_id 续拉并拼接当页数据；过滤切换时回到首页）。 */
async function reload(append = false): Promise<void> {
  if (append) {
    loadingMore.value = true;
  } else {
    loading.value = true;
  }
  try {
    const page = await listVaults({
      limit: 20,
      name: nameKeyword.value.trim() || undefined,
      includeArchived: includeArchived.value ? true : undefined,
      afterId: append ? lastId.value || undefined : undefined,
    });
    vaults.value = append ? [...vaults.value, ...page.data] : page.data;
    hasMore.value = page.has_more;
    lastId.value = page.last_id ?? '';
  } catch (e) {
    // 失败即清空列表并复位游标：不得残留上次过滤条件的数据或以陈旧游标续拉
    vaults.value = [];
    hasMore.value = false;
    lastId.value = '';
    MessagePlugin.error(`保管库列表加载失败: ${(e as Error).message}`);
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
.vault-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.vault-name-filter {
  width: 220px;
  margin-left: auto;
}

.vault-more {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.credential-form {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--td-component-stroke);
}

.rotate-field {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.form-alert {
  margin-bottom: 16px;
}

.ellipsis {
  display: block;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dialog-error {
  margin: 8px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}
</style>