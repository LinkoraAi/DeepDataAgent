<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { ApiSchemaDetailResponse, PreOperationTestResponse } from '@/modules/datasource/api/apiSchema';
import {
  createApiSchema,
  deleteApiSchema,
  getApiSchemaDetail,
  parseApiResponse,
  testPreOperation,
  updateApiSchema,
} from '@/modules/datasource/api/apiSchema';
import type { DatasourceResponse } from '@/modules/datasource/api/datasource';
import { errorMessage } from '@/shared/composables/useAsync';
import { confirmDangerous } from '@/shared/composables/useConfirm';
import { LAYER } from '@/shared/constants/layers';
import FormSection from '@/shared/components/FormSection.vue';
import KvEditor from '@/shared/components/KvEditor.vue';
import JsonEditor from '@/shared/components/JsonEditor.vue';
import {
  apiSchemaToDraft,
  AUTH_TYPES,
  BODY_TYPES,
  buildParsedFieldTree,
  checkedToFieldPayload,
  createEmptyApiSchemaDraft,
  createEmptyParamMappingDraft,
  createEmptyPreOperationDraft,
  draftToApiSchemaRequest,
  draftToParseRequest,
  flattenFieldTree,
  HTTP_METHODS,
  PAGINATION_TYPES,
  PARAM_LOCATIONS,
  preOpToTestRequest,
  rootPathHint,
  validateApiSchemaDraft,
  validateName,
} from '@/modules/datasource/mappers';
import type { ApiSchemaDraft, FieldTreeNode } from '@/modules/datasource/mappers';
import PreviewDialog from './PreviewDialog.vue';

/**
 * API 数据表配置抽屉（新建 / 编辑复用）。
 * 覆盖 spec 全部条件展开、字段解析与映射、试跑与"字段全量覆盖"确认。
 *
 * 使用：
 *   <ApiSchemaDrawer v-model:visible="open" :datasource="..." :schema-id="editingId" @saved="reload"/>
 * schemaId 为 undefined 时视为新建，需 datasource 已存在（即先建 API 主连接再添加数据表）；
 * schemaId 非空时进入编辑，回显走 GET /api-schemas/{id}（{schema, fields}）。
 */
const props = defineProps<{
  visible: boolean;
  /** 所属 API 主连接 */
  datasource: DatasourceResponse | null;
  /** 编辑时的表 id；新建留空 */
  schemaId?: number | null;
}>();

const emit = defineEmits<{ 'update:visible': [boolean]; saved: [] }>();

const draft = reactive<ApiSchemaDraft>(createEmptyApiSchemaDraft());
const errors = ref<Record<string, string>>({});
const loadingDetail = ref(false);
const saving = ref(false);

// 解析结果与字段勾选
const parsedTree = ref<FieldTreeNode[]>([]);
const parsedIndex = ref<Map<string, FieldTreeNode>>(new Map());
const checkedKeys = ref<string[]>([]);
const sampleRows = ref<Record<string, unknown>[]>([]);
const parsedFieldsMeta = reactive<Record<string, { displayName: string; description: string }>>({});
const parsing = ref(false);

// 试跑结果
const preOpTest = reactive<{ index: number; visible: boolean; data: PreOperationTestResponse | null; loading: boolean }>({
  index: -1,
  visible: false,
  data: null,
  loading: false,
});

// 预览
const previewVisible = ref(false);

const isEdit = computed(() => typeof draft.id === 'number');
const validateCtx = computed(() => ({ mode: isEdit.value ? ('edit' as const) : ('create' as const) }));
const rootHint = computed(() => rootPathHint(draft.rootPath));

// 字段映射表：仅显示勾选节点，displayName/description 独立可编辑
interface FieldRow {
  originalName: string;
  jsonPath: string;
  fieldType: string;
  displayName: string;
  description: string;
}
const fieldRows = computed<FieldRow[]>(() => {
  return checkedKeys.value
    .map((k) => parsedIndex.value.get(k))
    .filter((n): n is FieldTreeNode => !!n)
    .map((n) => {
      const meta = parsedFieldsMeta[n.jsonPath] ?? { displayName: n.originalName, description: '' };
      return {
        originalName: n.originalName,
        jsonPath: n.jsonPath,
        fieldType: n.fieldType,
        displayName: meta.displayName,
        description: meta.description,
      };
    });
});

/** 从 draft.fields 恢复勾选与编辑（编辑回显后调用）。 */
function restoreCheckedFromDraft(): void {
  const keys: string[] = [];
  const meta: Record<string, { displayName: string; description: string }> = {};
  for (const f of draft.fields) {
    if (!f.jsonPath) {
      continue;
    }
    keys.push(f.jsonPath);
    meta[f.jsonPath] = { displayName: f.displayName ?? f.originalName ?? '', description: f.description ?? '' };
  }
  checkedKeys.value = keys;
  Object.assign(parsedFieldsMeta, meta);
}

/** t-tree 勾选变化（值可能是 string|number，统一转 string）。 */
function onTreeChange(value: Array<string | number>): void {
  checkedKeys.value = value.map((v) => String(v));
}

async function loadDetail(): Promise<void> {
  if (typeof props.schemaId !== 'number') {
    return;
  }
  loadingDetail.value = true;
  try {
    const detail: ApiSchemaDetailResponse = await getApiSchemaDetail(props.schemaId);
    const mapped = apiSchemaToDraft(detail.schema, detail.fields);
    Object.assign(draft, mapped);
    restoreCheckedFromDraft();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loadingDetail.value = false;
  }
}

/** 重置：新建清空；编辑重新加载。 */
function resetForm(): void {
  Object.assign(draft, createEmptyApiSchemaDraft());
  errors.value = {};
  parsedTree.value = [];
  parsedIndex.value = new Map();
  checkedKeys.value = [];
  sampleRows.value = [];
  Object.keys(parsedFieldsMeta).forEach((k) => delete parsedFieldsMeta[k]);
}

function setFieldMeta(jsonPath: string, displayName: string, description: string): void {
  parsedFieldsMeta[jsonPath] = { displayName, description };
}

/** 收集当前勾选+编辑 → 更新 draft.fields。 */
function commitFieldsToDraft(): void {
  const basePayload = checkedToFieldPayload(checkedKeys.value, parsedIndex.value);
  const withEdits = basePayload.map((p) => {
    const meta = parsedFieldsMeta[p.jsonPath ?? ''] ?? { displayName: p.displayName ?? '', description: p.description ?? '' };
    return { ...p, displayName: meta.displayName || p.displayName, description: meta.description };
  });
  draft.fields = withEdits;
}

async function onParse(): Promise<void> {
  if (!props.datasource) {
    MessagePlugin.warning('请先选择数据源');
    return;
  }
  const errs = validateApiSchemaDraft(draft, validateCtx.value);
  if (Object.keys(errs).length > 0) {
    errors.value = errs;
    MessagePlugin.error('请先修正表单错误');
    return;
  }
  errors.value = {};
  parsing.value = true;
  try {
    const req = draftToParseRequest(draft, props.datasource.id);
    const res = await parseApiResponse(req);
    const tree: FieldTreeNode[] = buildParsedFieldTree(res.fieldTree);
    parsedTree.value = tree;
    parsedIndex.value = flattenFieldTree(tree);
    sampleRows.value = res.rows ?? [];
    // 编辑回显：若已有 fields 且解析树包含同名 jsonPath，保留勾选；否则清空
    const existing = new Set(draft.fields.map((f) => f.jsonPath));
    const next: string[] = [];
    for (const k of checkedKeys.value) {
      if (parsedIndex.value.has(k) && (!existing.size || existing.has(k))) {
        next.push(k);
      }
    }
    // 首次解析：若原本无 fields 则不预设勾选（用户手选）；已存 fields 命中树节点的保留
    checkedKeys.value = next;
    MessagePlugin.success(`解析完成：${tree.length} 个顶层字段`);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    parsing.value = false;
  }
}

function sampleColumns(): Array<{ colKey: string; title: string; ellipsis: boolean }> {
  const keys: string[] = [];
  for (const row of sampleRows.value) {
    for (const k of Object.keys(row)) {
      if (!keys.includes(k)) {
        keys.push(k);
      }
    }
  }
  return keys.map((colKey) => ({ colKey, title: colKey, ellipsis: true, cell: 'formatted' }));
}

function formatCell(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v);
    } catch {
      return String(v);
    }
  }
  return String(v);
}

// ==================== 前置操作 ====================
function addPreOp(): void {
  draft.preOperations.push(createEmptyPreOperationDraft());
}
function removePreOp(index: number): void {
  draft.preOperations.splice(index, 1);
}
function movePreOp(index: number, delta: number): void {
  const target = index + delta;
  if (target < 0 || target >= draft.preOperations.length) {
    return;
  }
  const [item] = draft.preOperations.splice(index, 1);
  draft.preOperations.splice(target, 0, item);
}
function addParamMapping(index: number): void {
  draft.preOperations[index].paramMappings.push(createEmptyParamMappingDraft());
}
function removeParamMapping(index: number, mappingIndex: number): void {
  draft.preOperations[index].paramMappings.splice(mappingIndex, 1);
}

async function runPreOpTest(index: number): Promise<void> {
  const pre = draft.preOperations[index];
  if (!pre) {
    return;
  }
  preOpTest.index = index;
  preOpTest.visible = true;
  preOpTest.data = null;
  preOpTest.loading = true;
  try {
    preOpTest.data = await testPreOperation(preOpToTestRequest(pre, draft.auth));
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    preOpTest.visible = false;
  } finally {
    preOpTest.loading = false;
  }
}

// ==================== 保存 ====================
async function onSave(): Promise<void> {
  const nameErr = validateName(draft.name);
  if (nameErr) {
    errors.value = { ...errors.value, name: nameErr };
    return;
  }
  const errs = validateApiSchemaDraft(draft, validateCtx.value);
  if (Object.keys(errs).length > 0) {
    errors.value = errs;
    MessagePlugin.error('表单存在校验错误');
    return;
  }
  errors.value = {};
  commitFieldsToDraft();

  if (isEdit.value && draft.fields.length > 0) {
    const confirmed = await confirmDangerous({
      header: '确认覆盖字段映射',
      body: 'API 数据表的字段映射将被全量覆盖（未勾选项将被删除），是否继续？',
      theme: 'warning',
      confirmText: '确认覆盖',
    });
    if (!confirmed) {
      return;
    }
  } else if (isEdit.value && draft.fields.length === 0) {
    const confirmed = await confirmDangerous({
      header: '确认清空字段映射',
      body: '当前未勾选任何字段，保存将清空原有字段映射，是否继续？',
      theme: 'warning',
      confirmText: '确认清空',
    });
    if (!confirmed) {
      return;
    }
  }

  saving.value = true;
  try {
    const request = draftToApiSchemaRequest(draft, validateCtx.value);
    if (typeof draft.id === 'number') {
      await updateApiSchema(draft.id, request);
      MessagePlugin.success('API 表已保存');
    } else {
      if (!props.datasource) {
        throw new Error('缺少数据源上下文');
      }
      await createApiSchema(props.datasource.id, request);
      MessagePlugin.success('API 表已创建');
    }
    emit('saved');
    emit('update:visible', false);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}

async function onDeleteSchema(): Promise<void> {
  if (typeof draft.id !== 'number') {
    return;
  }
  const confirmed = await confirmDangerous({
    header: `删除 API 表：${draft.name}`,
    body: '删除后该表的字段映射将被一并清理，操作不可撤销。',
    theme: 'danger',
    confirmText: '确认删除',
  });
  if (!confirmed) {
    return;
  }
  try {
    await deleteApiSchema(draft.id);
    MessagePlugin.success('已删除');
    emit('saved');
    emit('update:visible', false);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      if (typeof props.schemaId === 'number') {
        void loadDetail();
      } else {
        resetForm();
      }
    }
  },
);

function onClose(): void {
  emit('update:visible', false);
}
</script>

<template>
  <t-drawer
    :visible="visible"
    :header="isEdit ? `编辑 API 表：${draft.name || '(未命名)'}` : '新建 API 表'"
    size="85%"
    placement="right"
    :close-on-overlay-click="false"
    attach="body"
    :z-index="LAYER.drawerNested"
    @update:visible="(v: boolean) => emit('update:visible', v)"
    @close="onClose"
  >
    <t-loading :loading="loadingDetail" show-overlay>
      <!-- 基本 -->
      <FormSection title="基本配置" hint="表名、请求地址与方法、请求头/查询参数/请求体、根 JsonPath">
        <t-form :data="draft" label-align="top">
          <t-form-item label="表名" name="name">
            <t-input v-model="draft.name" placeholder="仅中英文/字母开头，可含 - _ 数字" :maxlength="100" />
            <template #help><span v-if="errors.name" class="dda-field-error">{{ errors.name }}</span></template>
          </t-form-item>
          <t-form-item label="请求 URL" name="url">
            <t-input v-model="draft.url" placeholder="https://api.example.com/orders" />
            <template #help><span v-if="errors.url" class="dda-field-error">{{ errors.url }}</span></template>
          </t-form-item>
          <t-form-item label="请求方法" name="method">
            <t-radio-group v-model="draft.method" variant="default-filled">
              <t-radio-button v-for="m in HTTP_METHODS" :key="m" :value="m">{{ m }}</t-radio-button>
            </t-radio-group>
          </t-form-item>
          <t-form-item label="请求头" name="headers">
            <KvEditor v-model="draft.headers" key-placeholder="Header 名" value-placeholder="Header 值" />
          </t-form-item>
          <t-form-item label="查询参数" name="params">
            <KvEditor v-model="draft.params" key-placeholder="参数名" value-placeholder="参数值" />
          </t-form-item>
          <t-form-item label="请求体类型" name="bodyType">
            <t-select v-model="draft.bodyType" :options="BODY_TYPES.map((v) => ({ value: v, label: v }))" />
          </t-form-item>
          <t-form-item label="请求体" name="body">
            <JsonEditor v-model="draft.body" placeholder="{}" :validate="draft.bodyType === 'JSON'" :rows="6" />
          </t-form-item>
          <t-form-item label="根 JsonPath" name="rootPath">
            <t-input v-model="draft.rootPath" placeholder="例：$. 或 $.data.list.*" />
            <template #help>
              <span v-if="rootHint" class="dda-field-hint">{{ rootHint }}</span>
            </template>
          </t-form-item>
        </t-form>
      </FormSection>

      <!-- 鉴权 -->
      <FormSection title="鉴权" hint="选择 BASIC_AUTH 时用户名/密码必填；编辑态密码留空表示沿用">
        <t-radio-group v-model="draft.auth.authType" variant="default-filled">
          <t-radio-button v-for="a in AUTH_TYPES" :key="a" :value="a">
            {{ a === 'NO_AUTH' ? 'NO_AUTH · 无鉴权' : 'BASIC_AUTH · 用户名密码' }}
          </t-radio-button>
        </t-radio-group>
        <div v-if="draft.auth.authType === 'BASIC_AUTH'" class="dda-auth-grid">
          <t-form label-align="top">
            <t-form-item label="用户名" name="authUsername">
              <t-input v-model="draft.auth.username" placeholder="鉴权用户名" />
              <template #help><span v-if="errors.authUsername" class="dda-field-error">{{ errors.authUsername }}</span></template>
            </t-form-item>
            <t-form-item label="密码" name="authPassword">
              <t-input
                v-model="draft.auth.password"
                type="password"
                :placeholder="isEdit && draft.auth.credentialConfigured ? '已配置凭证，留空表示不修改' : '鉴权密码'"
              />
              <template #help>
                <span v-if="errors.authPassword" class="dda-field-error">{{ errors.authPassword }}</span>
                <span v-else-if="isEdit && draft.auth.credentialConfigured" class="dda-field-hint">后端不回显密码；提交留空即沿用原凭证</span>
              </template>
            </t-form-item>
          </t-form>
        </div>
      </FormSection>

      <!-- 分页 -->
      <FormSection title="分页" hint="PAGE_BASED 需页码/页大小参数名与范围">
        <t-radio-group v-model="draft.pagination.paginationType" variant="default-filled">
          <t-radio-button v-for="p in PAGINATION_TYPES" :key="p" :value="p">
            {{ p === 'NONE' ? '不分页' : 'PAGE_BASED · 页码+页大小' }}
          </t-radio-button>
        </t-radio-group>
        <t-form v-if="draft.pagination.paginationType === 'PAGE_BASED'" label-align="top" class="dda-pagination-grid">
          <t-form-item label="页码参数名" name="pageParamName">
            <t-input v-model="draft.pagination.pageParamName" placeholder="page / pageNo / offset" />
            <template #help><span v-if="errors.pageParamName" class="dda-field-error">{{ errors.pageParamName }}</span></template>
          </t-form-item>
          <t-form-item label="页大小参数名" name="sizeParamName">
            <t-input v-model="draft.pagination.sizeParamName" placeholder="size / limit / pageSize" />
            <template #help><span v-if="errors.sizeParamName" class="dda-field-error">{{ errors.sizeParamName }}</span></template>
          </t-form-item>
          <t-form-item label="每页大小 (1~500)" name="pageSize">
            <t-input-number v-model="draft.pagination.pageSize" :min="1" :max="500" theme="column" />
            <template #help><span v-if="errors.pageSize" class="dda-field-error">{{ errors.pageSize }}</span></template>
          </t-form-item>
          <t-form-item label="最大页数 (1~10000)" name="maxPages">
            <t-input-number v-model="draft.pagination.maxPages" :min="1" :max="10000" theme="column" />
            <template #help><span v-if="errors.maxPages" class="dda-field-error">{{ errors.maxPages }}</span></template>
          </t-form-item>
          <t-form-item label="总条数 JsonPath" name="totalCountJsonPath">
            <t-input v-model="draft.pagination.totalCountJsonPath" placeholder="例：$.data.total" />
          </t-form-item>
        </t-form>
      </FormSection>

      <!-- 超时/重试 -->
      <FormSection title="超时与重试">
        <t-form label-align="top" class="dda-timeout-grid">
          <t-form-item label="超时秒数（默认 180）" name="timeout">
            <t-input-number v-model="draft.timeout" :min="1" theme="column" />
            <template #help><span v-if="errors.timeout" class="dda-field-error">{{ errors.timeout }}</span></template>
          </t-form-item>
          <t-form-item label="重试次数 0~5" name="retryCount">
            <t-input-number v-model="draft.retryCount" :min="0" :max="5" theme="column" />
            <template #help><span v-if="errors.retryCount" class="dda-field-error">{{ errors.retryCount }}</span></template>
          </t-form-item>
        </t-form>
      </FormSection>

      <!-- 前置操作 -->
      <FormSection title="前置操作" hint="在主请求之前执行，用于换取 token 等；参数映射从响应抽取并注入主请求">
        <div v-for="(pre, i) in draft.preOperations" :key="i" class="dda-preop-card">
          <div class="dda-preop-head">
            <span class="dda-preop-title">前置操作 {{ i + 1 }}</span>
            <t-space size="4px">
              <t-button size="small" variant="text" :disabled="i === 0" @click="movePreOp(i, -1)">↑</t-button>
              <t-button size="small" variant="text" :disabled="i === draft.preOperations.length - 1" @click="movePreOp(i, 1)">↓</t-button>
              <t-button size="small" variant="text" theme="primary" @click="runPreOpTest(i)" :loading="preOpTest.index === i && preOpTest.loading">试跑</t-button>
              <t-button size="small" variant="text" theme="danger" @click="removePreOp(i)">删除</t-button>
            </t-space>
          </div>
          <div class="dda-preop-switch">
            <t-checkbox v-model="pre.enabled">启用</t-checkbox>
          </div>
          <t-form label-align="top">
            <t-form-item label="URL" name="preUrl">
              <t-input v-model="pre.url" :disabled="!pre.enabled" placeholder="https://api.example.com/token" />
              <template #help><span v-if="errors[`preOperations.${i}.url`]" class="dda-field-error">{{ errors[`preOperations.${i}.url`] }}</span></template>
            </t-form-item>
            <t-form-item label="方法" name="preMethod">
              <t-radio-group v-model="pre.method" variant="default-filled" :disabled="!pre.enabled">
                <t-radio-button v-for="m in HTTP_METHODS" :key="m" :value="m">{{ m }}</t-radio-button>
              </t-radio-group>
            </t-form-item>
            <t-form-item label="请求头" name="preHeaders">
              <KvEditor v-model="pre.headers" key-placeholder="Header 名" value-placeholder="Header 值" />
            </t-form-item>
            <t-form-item label="查询参数" name="preParams">
              <KvEditor v-model="pre.params" key-placeholder="参数名" value-placeholder="参数值" />
            </t-form-item>
            <t-form-item label="请求体类型" name="preBodyType">
              <t-select v-model="pre.bodyType" :disabled="!pre.enabled" :options="BODY_TYPES.map((v) => ({ value: v, label: v }))" />
            </t-form-item>
            <t-form-item label="请求体" name="preBody">
              <JsonEditor v-model="pre.body" placeholder="{}" :validate="pre.bodyType === 'JSON'" :rows="4" />
            </t-form-item>
            <t-form-item label="参数映射" name="preMappings">
              <div class="dda-mappings">
                <div v-for="(m, mi) in pre.paramMappings" :key="mi" class="dda-mapping-row">
                  <t-input v-model="m.paramName" placeholder="参数名" :disabled="!pre.enabled" style="flex: 1 1 20%" />
                  <t-select v-model="m.paramLocation" :disabled="!pre.enabled" :options="PARAM_LOCATIONS.map((v) => ({ value: v, label: v }))" style="flex: 0 0 20%" />
                  <t-input v-model="m.jsonPath" placeholder="$.data.token" :disabled="!pre.enabled" style="flex: 1 1 45%" />
                  <t-button size="small" variant="text" theme="danger" :disabled="!pre.enabled" @click="removeParamMapping(i, mi)">✕</t-button>
                </div>
                <t-button size="small" variant="dashed" :disabled="!pre.enabled" @click="addParamMapping(i)">+ 添加映射</t-button>
              </div>
              <template #help>
                <span v-if="errors[`preOperations.${i}.paramMappings`]" class="dda-field-error">{{ errors[`preOperations.${i}.paramMappings`] }}</span>
              </template>
            </t-form-item>
          </t-form>
        </div>
        <t-button variant="dashed" size="small" @click="addPreOp">+ 添加前置操作</t-button>
      </FormSection>

      <!-- 解析与字段映射 -->
      <FormSection title="测试并解析" hint="调用后端解析端点，返回字段树与前 10 行样例；勾选字段后配置显示名/描述">
        <t-button theme="primary" :loading="parsing" @click="onParse">测试并解析</t-button>
        <span v-if="draft.fields.length > 0 && parsedTree.length === 0" class="dda-parse-hint">
          已配置 {{ draft.fields.length }} 个字段映射，点击「测试并解析」以刷新字段树
        </span>
        <div v-if="parsedTree.length > 0" class="dda-parse-grid">
          <div class="dda-parse-tree">
            <div class="dda-panel-title">字段树</div>
            <t-tree
              :data="parsedTree"
              :value="checkedKeys"
              checkable
              hover
              expand-all
              transition
              @change="onTreeChange"
            />
          </div>
          <div class="dda-parse-sample">
            <div class="dda-panel-title">前 10 行样例</div>
            <t-table
              :data="sampleRows"
              :columns="sampleColumns()"
              size="small"
              max-height="320"
              table-layout="auto"
              empty="无样例行"
            >
              <template #formatted="{ row, col }">
                <span :title="formatCell(row[col.colKey])">{{ formatCell(row[col.colKey]) }}</span>
              </template>
            </t-table>
          </div>
        </div>
        <div v-if="fieldRows.length > 0" class="dda-field-mapping">
          <div class="dda-panel-title">字段映射</div>
          <t-table
            :data="fieldRows"
            row-key="jsonPath"
            size="small"
            max-height="320"
            :columns="[
              { colKey: 'originalName', title: '原始名', width: 160, ellipsis: true },
              { colKey: 'jsonPath', title: 'JsonPath', width: 220, ellipsis: true },
              { colKey: 'fieldType', title: '类型', width: 100 },
              { colKey: 'displayName', title: '显示名', width: 180 },
              { colKey: 'description', title: '描述', width: 240 },
            ]"
          >
            <template #displayName="{ row }">
              <t-input
                :model-value="row.displayName"
                size="small"
                @update:model-value="(v: string) => setFieldMeta(row.jsonPath, v, row.description)"
              />
            </template>
            <template #description="{ row }">
              <t-input
                :model-value="row.description"
                size="small"
                @update:model-value="(v: string) => setFieldMeta(row.jsonPath, row.displayName, v)"
              />
            </template>
          </t-table>
        </div>
      </FormSection>
    </t-loading>

    <template #footer>
      <div class="dda-drawer-footer">
        <t-button v-if="isEdit" theme="danger" variant="outline" @click="onDeleteSchema">删除</t-button>
        <t-button v-if="isEdit && datasource?.status === 'ENABLED'" theme="default" variant="outline" @click="previewVisible = true">预览</t-button>
        <span class="dda-flex-fill" />
        <t-button variant="outline" @click="onClose">取消</t-button>
        <t-button theme="primary" :loading="saving" @click="onSave">保存</t-button>
      </div>
    </template>

    <!-- 试跑结果弹窗 -->
    <t-dialog
      :visible="preOpTest.visible"
      header="前置操作试跑结果"
      width="720px"
      placement="center"
      attach="body"
      :z-index="LAYER.dialog"
      :footer="false"
      @update:visible="(v: boolean) => (preOpTest.visible = v)"
    >
      <t-loading :loading="preOpTest.loading" show-overlay>
        <div v-if="preOpTest.data" class="dda-preop-result">
          <div class="dda-preop-result__status">
            <t-tag :theme="preOpTest.data.success ? 'success' : 'danger'" variant="light">
              {{ preOpTest.data.success ? '成功' : '失败' }}
            </t-tag>
          </div>
          <pre class="dda-mono dda-preop-result__body">{{ preOpTest.data.rawResponse ?? '(无响应体)' }}</pre>
        </div>
      </t-loading>
    </t-dialog>

    <PreviewDialog
      v-if="datasource"
      v-model:visible="previewVisible"
      :connection-id="datasource.id"
      :table-name="draft.name"
      :enabled="datasource.status === 'ENABLED'"
    />
  </t-drawer>
</template>

<style scoped>
.dda-drawer-footer {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.dda-flex-fill {
  flex: 1 1 auto;
}

.dda-field-error {
  color: var(--td-error-color, #d54941);
  font-size: 12px;
}

.dda-field-hint {
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  font-size: 12px;
}

.dda-auth-grid,
.dda-pagination-grid,
.dda-timeout-grid {
  margin-top: 12px;
}

.dda-pagination-grid :deep(.t-form__item),
.dda-timeout-grid :deep(.t-form__item) {
  display: inline-block;
  min-width: 220px;
  margin-right: 16px;
  vertical-align: top;
}

.dda-parse-hint {
  margin-left: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
  font-size: 12px;
}

.dda-parse-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
  margin-top: 12px;
}

.dda-panel-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--td-brand-color);
}

.dda-preop-card {
  margin-bottom: 12px;
  padding: 12px;
  background: var(--td-bg-color-secondarycontainer, #f6f7f8);
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 6px;
}

.dda-preop-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.dda-preop-title {
  font-weight: 600;
}

.dda-mappings {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dda-mapping-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.dda-preop-result__status {
  margin-bottom: 8px;
}

.dda-preop-result__body {
  max-height: 360px;
  padding: 8px;
  margin: 0;
  overflow: auto;
  background: var(--dda-codeblock-bg);
  color: var(--dda-codeblock-fg);
  border-radius: 4px;
}
</style>
