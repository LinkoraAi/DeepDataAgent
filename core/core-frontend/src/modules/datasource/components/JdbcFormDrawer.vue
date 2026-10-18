<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import {
  createDatasource,
  listDatasourceTypes,
  testTemporaryDatasource,
  updateDatasource,
} from '@/modules/datasource/api/datasource';
import type { DatasourceResponse, DatasourceTypeResponse, TestConnectionResponse } from '@/modules/datasource/api/datasource';
import { errorMessage } from '@/shared/composables/useAsync';
import {
  MAX_DESCRIPTION_LENGTH,
  PG_DEFAULT_SCHEMA,
  portForSubType,
  subTypeOptions,
  validateDescription,
  validateJdbcForm,
  validateName,
  validatePgSchema,
  validatePort,
} from '@/modules/datasource/mappers';
import TypeCards from './TypeCards.vue';

/**
 * JDBC 数据源表单抽屉：新建（类型卡 + 表单）与编辑（类型不可改）复用。
 * 字段：名称、描述、主机、端口（选类型联动默认）、数据库名、用户名、密码（编辑留空=不改）、schema（仅 PostgreSQL）。
 * 保存前支持"校验连接"（临时 test，不保存）；保存成功提示"已保存，元数据同步中"。
 */
const props = defineProps<{
  visible: boolean;
  /** 编辑时传入的数据源；为空表示新建 */
  datasource?: DatasourceResponse | null;
  /** 后端类型清单；未提供时抽屉内自行拉取 */
  types?: DatasourceTypeResponse[];
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  saved: [DatasourceResponse];
  /** 新建态选择了 API 类型：交由父级切换到 API 向导 */
  selectApi: [];
}>();

const isEdit = computed(() => !!props.datasource);

interface JdbcForm {
  name: string;
  description: string;
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  schema: string;
}

const form = reactive<JdbcForm>({
  name: '',
  description: '',
  host: '',
  port: 3306,
  database: '',
  username: '',
  password: '',
  schema: PG_DEFAULT_SCHEMA,
});
const errors = ref<Record<string, string>>({});
const subType = ref<string>('');
const typeList = ref<DatasourceTypeResponse[]>([]);
const testing = ref(false);
const testResult = ref<TestConnectionResponse | null>(null);

const showSchema = computed(() => subType.value === 'POSTGRESQL');
const subTypeLabel = computed(() => {
  const found = subTypeOptions(typeList.value).find((t) => t.subType === subType.value);
  return found?.name ?? subType.value ?? '-';
});

async function ensureTypes(): Promise<void> {
  const provided = props.types && props.types.length > 0 ? props.types : null;
  if (provided) {
    typeList.value = provided;
    return;
  }
  try {
    typeList.value = await listDatasourceTypes();
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

/** 选择类型卡：API 交父级，JDBC 联动默认端口。 */
function onSelectType(item: DatasourceTypeResponse): void {
  testResult.value = null;
  if (item.subType === 'API') {
    emit('selectApi');
    return;
  }
  subType.value = item.subType;
  const port = portForSubType(item.subType);
  if (port !== null) {
    form.port = port;
  }
  form.schema = item.subType === 'POSTGRESQL' ? PG_DEFAULT_SCHEMA : '';
}

function validate(): boolean {
  const errs: Record<string, string> = {};
  const nameErr = validateName(form.name);
  if (nameErr) {
    errs.name = nameErr;
  }
  const descErr = validateDescription(form.description);
  if (descErr) {
    errs.description = descErr;
  }
  if (!isEdit.value && !subType.value) {
    errs.subType = '请选择数据源类型';
  }
  const jdbcErrs = validateJdbcForm({
    host: form.host,
    port: form.port,
    database: form.database,
    username: form.username,
    schema: form.schema,
    subType: subType.value || undefined,
  });
  Object.assign(errs, jdbcErrs);
  errors.value = errs;
  return Object.keys(errs).length === 0;
}

/** 实时字段级校验（不阻断，仅提示）。 */
function onNameBlur(): void {
  const err = validateName(form.name);
  errors.value = { ...errors.value, name: err };
}
function onPortBlur(): void {
  errors.value = { ...errors.value, port: validatePort(form.port) };
}
function onSchemaBlur(): void {
  errors.value = { ...errors.value, schema: validatePgSchema(form.schema, subType.value) };
}

async function onTestConnection(): Promise<void> {
  if (!validate()) {
    return;
  }
  testing.value = true;
  testResult.value = null;
  try {
    testResult.value = await testTemporaryDatasource({
      type: 'JDBC',
      subType: subType.value,
      host: form.host,
      port: form.port,
      database: form.database,
      username: form.username,
      password: form.password || undefined,
      schema: form.schema || undefined,
    });
  } catch (e) {
    testResult.value = { success: false, message: errorMessage(e) };
  } finally {
    testing.value = false;
  }
}

async function onSave(): Promise<void> {
  if (!validate()) {
    MessagePlugin.error('请先修正表单错误');
    return;
  }
  const jdbcConfig = {
    host: form.host,
    port: form.port,
    database: form.database,
    username: form.username,
    password: form.password || undefined,
    schema: form.schema || undefined,
  };
  try {
    let saved: DatasourceResponse;
    if (isEdit.value && props.datasource) {
      saved = await updateDatasource(props.datasource.id, {
        name: form.name,
        description: form.description,
        jdbcConfig,
      });
    } else {
      saved = await createDatasource({
        name: form.name,
        type: 'JDBC',
        subType: subType.value,
        description: form.description,
        jdbcConfig,
      });
    }
    MessagePlugin.success('已保存，元数据同步中');
    emit('saved', saved);
    emit('update:visible', false);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  }
}

function reset(): void {
  Object.assign(form, {
    name: '',
    description: '',
    host: '',
    port: 3306,
    database: '',
    username: '',
    password: '',
    schema: PG_DEFAULT_SCHEMA,
  });
  errors.value = {};
  testResult.value = null;
}

function prefillFromDatasource(ds: DatasourceResponse): void {
  subType.value = ds.subType ?? '';
  form.name = ds.name;
  form.description = ds.description ?? '';
  const cfg = ds.jdbcConfig;
  form.host = cfg?.host ?? '';
  form.port = cfg?.port ?? portForSubType(ds.subType) ?? 3306;
  form.database = cfg?.database ?? '';
  form.username = cfg?.username ?? '';
  form.password = '';
  form.schema = cfg?.schema ?? (ds.subType === 'POSTGRESQL' ? PG_DEFAULT_SCHEMA : '');
}

watch(
  () => props.visible,
  async (open) => {
    if (!open) {
      return;
    }
    await ensureTypes();
    if (props.datasource) {
      prefillFromDatasource(props.datasource);
    } else {
      reset();
      subType.value = '';
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
    :header="isEdit ? `编辑数据源：${datasource?.name ?? ''}` : '新建数据源'"
    size="600px"
    placement="right"
    :close-on-overlay-click="false"
    @update:visible="(v: boolean) => emit('update:visible', v)"
    @close="onClose"
  >
    <div v-if="!isEdit" class="dda-jdbc__types">
      <div class="dda-jdbc__section-title">选择类型</div>
      <TypeCards :types="typeList" :model-value="subType" @select="onSelectType" />
      <span v-if="errors.subType" class="dda-jdbc__error">{{ errors.subType }}</span>
    </div>

    <div v-if="isEdit" class="dda-jdbc__locked-type">
      类型：<strong>{{ subTypeLabel }}</strong>（编辑时不可更改）
    </div>

    <t-form :data="form" label-align="top" @submit.prevent>
      <t-form-item label="名称" name="name">
        <t-input v-model="form.name" :maxlength="100" placeholder="中英文或字母开头，可含 - _ 数字" @blur="onNameBlur" />
        <template #help><span v-if="errors.name" class="dda-jdbc__error">{{ errors.name }}</span></template>
      </t-form-item>
      <t-form-item label="描述" name="description">
        <t-textarea v-model="form.description" :maxlength="MAX_DESCRIPTION_LENGTH" placeholder="选填，最多 500 字" :autosize="{ minRows: 2, maxRows: 4 }" />
        <template #help><span v-if="errors.description" class="dda-jdbc__error">{{ errors.description }}</span></template>
      </t-form-item>
      <t-form-item label="主机" name="host">
        <t-input v-model="form.host" placeholder="hostname / IP" />
        <template #help><span v-if="errors.host" class="dda-jdbc__error">{{ errors.host }}</span></template>
      </t-form-item>
      <t-form-item label="端口" name="port">
        <t-input-number v-model="form.port" :min="1" :max="65535" theme="column" @blur="onPortBlur" />
        <template #help><span v-if="errors.port" class="dda-jdbc__error">{{ errors.port }}</span></template>
      </t-form-item>
      <t-form-item label="数据库名" name="database">
        <t-input v-model="form.database" placeholder="database" />
        <template #help><span v-if="errors.database" class="dda-jdbc__error">{{ errors.database }}</span></template>
      </t-form-item>
      <t-form-item label="用户名" name="username">
        <t-input v-model="form.username" placeholder="username" />
        <template #help><span v-if="errors.username" class="dda-jdbc__error">{{ errors.username }}</span></template>
      </t-form-item>
      <t-form-item label="密码" name="password">
        <t-input v-model="form.password" type="password" :placeholder="isEdit ? '留空表示不修改' : '连接密码'" />
      </t-form-item>
      <t-form-item v-if="showSchema" label="Schema" name="schema">
        <t-input v-model="form.schema" :placeholder="`默认 ${PG_DEFAULT_SCHEMA}，禁选系统 schema`" @blur="onSchemaBlur" />
        <template #help><span v-if="errors.schema" class="dda-jdbc__error">{{ errors.schema }}</span></template>
      </t-form-item>
    </t-form>

    <div class="dda-jdbc__test">
      <t-button variant="outline" :loading="testing" @click="onTestConnection">校验连接</t-button>
      <t-alert
        v-if="testResult"
        class="dda-jdbc__test-result"
        :theme="testResult.success ? 'success' : 'error'"
        :message="testResult.message || (testResult.success ? '连接成功' : '连接失败')"
      />
    </div>

    <template #footer>
      <div class="dda-jdbc__footer">
        <t-button variant="outline" @click="onClose">取消</t-button>
        <t-button theme="primary" @click="onSave">保存</t-button>
      </div>
    </template>
  </t-drawer>
</template>

<style scoped>
.dda-jdbc__section-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.dda-jdbc__locked-type {
  margin-bottom: 12px;
  padding: 8px 12px;
  font-size: 13px;
  background: var(--td-bg-color-secondarycontainer, #f6f7f8);
  border-radius: 4px;
}

.dda-jdbc__error {
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.dda-jdbc__test {
  margin-top: 8px;
}

.dda-jdbc__test-result {
  margin-top: 10px;
}

.dda-jdbc__footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  width: 100%;
}
</style>
