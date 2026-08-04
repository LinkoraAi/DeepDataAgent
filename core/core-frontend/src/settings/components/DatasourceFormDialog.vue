<template>
  <t-dialog
    v-model:visible="dialogVisible"
    :header="isEdit ? '编辑数据源' : '添加数据源'"
    :confirm-btn="{ loading: submitting }"
    @confirm="handleSubmit"
    @close="handleClose"
    width="600px"
  >
    <t-form ref="formRef" :data="formData" :rules="formRules" label-width="100px">
      <t-form-item label="数据源名称" name="name">
        <t-input v-model="formData.name" placeholder="请输入数据源名称" />
      </t-form-item>

      <t-form-item label="数据源类型" name="type">
        <div class="type-selector">
          <div
            v-for="typeOption in typeOptions"
            :key="typeOption.value"
            class="type-card"
            :class="{ active: formData.type === typeOption.value }"
            @click="handleTypeChange(typeOption.value)"
          >
            <div class="type-card__icon">{{ typeOption.icon }}</div>
            <div class="type-card__label">{{ typeOption.label }}</div>
          </div>
        </div>
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'CLICKHOUSE' || formData.type === 'POSTGRESQL'" label="主机地址" name="host">
        <t-input v-model="formData.host" placeholder="请输入主机地址" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'CLICKHOUSE' || formData.type === 'POSTGRESQL'" label="端口" name="port">
        <t-input v-model="formData.port" type="number" placeholder="请输入端口号" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'CLICKHOUSE' || formData.type === 'POSTGRESQL'" label="数据库名" name="database">
        <t-input v-model="formData.database" placeholder="请输入数据库名" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'POSTGRESQL'" label="schema" name="schema">
        <t-input v-model="formData.schema" placeholder="请输入 schema（默认 public）" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'CLICKHOUSE' || formData.type === 'POSTGRESQL'" label="用户名" name="username">
        <t-input v-model="formData.username" placeholder="请输入用户名" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'CLICKHOUSE' || formData.type === 'POSTGRESQL'" label="密码" name="password">
        <!-- 编辑模式:显示纯文本提示,密码不可修改 -->
        <template v-if="isEdit">
          <span class="password-readonly">......</span>
        </template>
        <!-- 新增模式:显示密码输入框 -->
        <t-input
          v-else
          v-model="formData.password"
          type="password"
          placeholder="请输入密码"
        />
      </t-form-item>

      <!-- API 类型配置展示 -->
      <template v-if="formData.type === 'API' && isEdit">
        <t-form-item label="API 配置">
          <div v-if="loadingApiConfig" class="api-config-loading">加载中...</div>
          <div v-else-if="currentApiSchema" class="api-config-display">
            <div class="api-config-item">
              <span class="api-config-label">请求地址：</span>
              <span class="api-config-value">{{ currentApiSchema.url }}</span>
            </div>
            <div class="api-config-item">
              <span class="api-config-label">请求方法：</span>
              <span class="api-config-value">{{ currentApiSchema.method }}</span>
            </div>
            <div v-if="currentApiSchema.headers && Object.keys(currentApiSchema.headers).length > 0" class="api-config-item">
              <span class="api-config-label">请求头：</span>
              <div class="api-config-headers">
                <div v-for="(value, key) in currentApiSchema.headers" :key="key" class="api-config-header-item">
                  {{ key }}: {{ value }}
                </div>
              </div>
            </div>
            <div v-if="currentApiSchema.body" class="api-config-item">
              <span class="api-config-label">请求体：</span>
              <pre class="api-config-body">{{ currentApiSchema.body }}</pre>
            </div>
            <div class="api-config-tip">API 配置详情请在数据源管理页面查看和编辑</div>
          </div>
          <div v-else class="api-config-empty">
            该数据源尚未配置 API 表，请在数据源管理页面配置
          </div>
        </t-form-item>
      </template>

      <t-form-item label="描述" name="description">
        <t-textarea v-model="formData.description" placeholder="请输入描述（可选）" :maxlength="200" />
      </t-form-item>
    </t-form>

    <template #footer>
      <t-button theme="default" variant="outline" :loading="testing" @click="handleTestConnection">
        测试连接
      </t-button>
      <t-button theme="default" @click="handleClose">取消</t-button>
      <t-button theme="primary" :loading="submitting" @click="handleSubmit">保存</t-button>
    </template>
  </t-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { FormInstanceFunctions, FormRule } from 'tdesign-vue-next';
import type { DatasourceConnection } from '@/modules/agent/types';
import { useDatasourceStore } from '@/modules/agent/stores/datasource';
import { listTables, getApiSchemaDetail, type TableResponse, type ApiSchemaDetailResponse } from '@/shared/api/datasourceApi';

const props = defineProps<{
  visible: boolean;
  editDatasource?: DatasourceConnection | null;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
  success: [];
}>();

const datasourceStore = useDatasourceStore();

const formRef = ref<FormInstanceFunctions>();
const submitting = ref(false);
const testing = ref(false);

const isEdit = ref(false);

// API 配置相关状态
const apiSchemaList = ref<TableResponse[]>([]);
const currentApiSchema = ref<ApiSchemaDetailResponse | null>(null);
const loadingApiConfig = ref(false);

const typeOptions = [
  { value: 'MYSQL', label: 'MySQL', icon: 'M' },
  { value: 'CLICKHOUSE', label: 'ClickHouse', icon: 'C' },
  { value: 'POSTGRESQL', label: 'PostgreSQL', icon: 'P' },
  { value: 'API', label: 'API', icon: 'A' },
];

const formData = reactive({
  name: '',
  type: 'MYSQL',
  host: '',
  port: 3306,
  database: '',
  username: '',
  password: '',
  schema: '',
  description: '',
});

const formRules: Record<string, FormRule[]> = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择数据源类型', trigger: 'change' }],
  host: [
    {
      required: true,
      message: '请输入主机地址',
      trigger: 'blur',
      validator: (value: string) => {
        if (isJdbcType(formData.type) && !value) {
          return { result: false, message: '请输入主机地址' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  port: [
    {
      required: true,
      message: '请输入端口号',
      trigger: 'blur',
      validator: (value: number) => {
        if (isJdbcType(formData.type) && !value) {
          return { result: false, message: '请输入端口号' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  database: [
    {
      required: true,
      message: '请输入数据库名',
      trigger: 'blur',
      validator: (value: string) => {
        if (isJdbcType(formData.type) && !value) {
          return { result: false, message: '请输入数据库名' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  schema: [
    {
      required: false,
      message: '请输入 schema',
      trigger: 'blur',
      validator: (value: string) => {
        // 仅 PostgreSQL 校验；schema 为空时使用默认 public，不做限制
        if (formData.type === 'POSTGRESQL' && value && value.includes(',')) {
          return { result: false, message: 'PostgreSQL 仅支持单个 schema，请勿填写多个值' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  username: [
    {
      required: true,
      message: '请输入用户名',
      trigger: 'blur',
      validator: (value: string) => {
        if (isJdbcType(formData.type) && !value) {
          return { result: false, message: '请输入用户名' };
        }
        return { result: true, message: '' };
      },
    },
  ],
  password: [
    {
      required: true,
      message: '请输入密码',
      trigger: 'blur',
      validator: (value: string) => {
        if (!isEdit.value && isJdbcType(formData.type) && !value) {
          return { result: false, message: '请输入密码' };
        }
        return { result: true, message: '' };
      },
    },
  ],
};

const dialogVisible = ref(props.visible);

watch(
  () => props.visible,
  async (val) => {
    dialogVisible.value = val;
    if (val) {
      if (props.editDatasource) {
        isEdit.value = true;
        formData.name = props.editDatasource.name;
        // 后端返回 type='JDBC' + subType='MYSQL'，表单内部用 subType 值（MYSQL/CLICKHOUSE/API）
        formData.type = props.editDatasource.type === 'JDBC'
          ? (props.editDatasource.subType || 'MYSQL')
          : props.editDatasource.type;
        formData.host = props.editDatasource.host || '';
        formData.port = props.editDatasource.port || 3306;
        formData.database = props.editDatasource.database || '';
        formData.schema = props.editDatasource.schema || 'public';
        formData.username = props.editDatasource.username || '';
        // 编辑时回显掩码密码（如 ****...****），用户可修改
        formData.password = props.editDatasource.maskedPassword || '';
        formData.description = props.editDatasource.description || '';

        // 如果是 API 类型，加载 API 配置
        if (props.editDatasource.type === 'API') {
          await loadApiConfig(props.editDatasource.id);
        }
      } else {
        isEdit.value = false;
        formData.name = '';
        formData.type = 'MYSQL';
        formData.host = '';
        formData.port = 3306;
        formData.database = '';
        formData.username = '';
        formData.password = '';
        formData.schema = '';
        formData.description = '';
        apiSchemaList.value = [];
        currentApiSchema.value = null;
      }
    }
  }
);

/**
 * 加载 API 配置
 */
async function loadApiConfig(datasourceId: number) {
  loadingApiConfig.value = true;
  try {
    // 获取该数据源下的 API Schema 列表
    const result = await listTables(datasourceId, 'API');
    apiSchemaList.value = result.list;
    
    // 如果有 API Schema，加载第一个的详细信息
    if (result.list.length > 0) {
      const schemaId = result.list[0].id;
      currentApiSchema.value = await getApiSchemaDetail(schemaId);
    } else {
      currentApiSchema.value = null;
    }
  } catch (error) {
    console.error('Failed to load API config:', error);
    currentApiSchema.value = null;
  } finally {
    loadingApiConfig.value = false;
  }
}

watch(
  () => dialogVisible.value,
  (val) => {
    emit('update:visible', val);
  }
);

function handleTypeChange(type: string) {
  formData.type = type;
  // 编辑模式下保留原始端口，新增模式下设置默认端口
  if (!isEdit.value) {
    if (type === 'MYSQL') {
      formData.port = 3306;
    } else if (type === 'CLICKHOUSE') {
      formData.port = 8123;
    } else if (type === 'POSTGRESQL') {
      formData.port = 5432;
    }
  }
}

/** 判断是否为 JDBC 类型(MYSQL / CLICKHOUSE / POSTGRESQL) */
function isJdbcType(type: string): boolean {
  return type === 'MYSQL' || type === 'CLICKHOUSE' || type === 'POSTGRESQL';
}

async function handleTestConnection() {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }

  testing.value = true;
  try {
    const params = {
      id: isEdit.value && props.editDatasource ? props.editDatasource.id : undefined,
      name: formData.name,
      // 后端期望 type='JDBC' + subType='MYSQL'，而非 type='MYSQL'
      type: isJdbcType(formData.type) ? 'JDBC' : formData.type,
      subType: isJdbcType(formData.type) ? formData.type : undefined,
      jdbcConfig: isJdbcType(formData.type)
        ? {
            host: formData.host,
            port: formData.port,
            database: formData.database,
            username: formData.username,
            password: formData.password,
            schema: formData.schema || 'public',
          }
        : undefined,
    };

    await datasourceStore.testConnection(params);
    MessagePlugin.success('连接测试成功');
  } catch (err: any) {
    // 错误提示已由 HTTP 拦截器统一弹出，这里仅记录日志避免重复弹窗
    console.error('Test connection failed:', err);
  } finally {
    testing.value = false;
  }
}

async function handleSubmit() {
  const valid = await formRef.value?.validate();
  if (valid !== true) {
    return;
  }

  submitting.value = true;
  try {
    if (isEdit.value && props.editDatasource) {
      await datasourceStore.updateDatasource({
        id: props.editDatasource.id,
        name: formData.name,
        description: formData.description,
        jdbcConfig: isJdbcType(formData.type)
          ? {
              host: formData.host,
              port: formData.port,
              database: formData.database,
              username: formData.username,
              schema: formData.schema || 'public',
              // 编辑模式不发送密码,保持原有密码不变
            }
          : undefined,
      });
      MessagePlugin.success('更新成功');
    } else {
      await datasourceStore.createDatasource({
        name: formData.name,
        type: isJdbcType(formData.type) ? 'JDBC' : formData.type,
        subType: isJdbcType(formData.type) ? formData.type : undefined,
        description: formData.description,
        jdbcConfig: isJdbcType(formData.type)
          ? {
              host: formData.host,
              port: formData.port,
              database: formData.database,
              username: formData.username,
              password: formData.password,
              schema: formData.schema || 'public',
            }
          : undefined,
      });
      MessagePlugin.success('添加成功');
    }
    emit('success');
    handleClose();
  } catch (err: any) {
    console.error('Submit failed:', err);
    MessagePlugin.error(err.message || '操作失败');
  } finally {
    submitting.value = false;
  }
}

function handleClose() {
  dialogVisible.value = false;
  formRef.value?.reset();
}
</script>

<style scoped lang="less">
.type-selector {
  display: flex;
  gap: 12px;
}

.type-card {
  flex: 1;
  padding: 16px;
  border: 2px solid rgba(15, 23, 42, 0.12);
  border-radius: 8px;
  text-align: center;
  cursor: pointer;
  transition: all 0.15s;

  &:hover {
    border-color: rgba(0, 82, 217, 0.4);
    background: rgba(0, 82, 217, 0.02);
  }

  &.active {
    border-color: #0052d9;
    background: rgba(0, 82, 217, 0.04);
  }

  &__icon {
    width: 36px;
    height: 36px;
    margin: 0 auto 8px;
    border-radius: 8px;
    background: rgba(0, 82, 217, 0.08);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #0052d9;
    font-weight: 600;
    font-size: 16px;
  }

  &__label {
    font-weight: 500;
    font-size: 14px;
    color: #0f172a;
  }
}

.api-config-loading {
  padding: 12px;
  text-align: center;
  color: #94a3b8;
}

.api-config-display {
  padding: 12px;
  background: #f8fafc;
  border-radius: 6px;
}

.api-config-item {
  margin-bottom: 8px;
  font-size: 13px;
}

.api-config-label {
  color: #64748b;
  font-weight: 500;
}

.api-config-value {
  color: #0f172a;
  word-break: break-all;
}

.api-config-headers {
  margin-top: 4px;
  padding-left: 8px;
}

.api-config-header-item {
  font-size: 12px;
  color: #475569;
  margin-bottom: 2px;
}

.api-config-body {
  margin-top: 4px;
  padding: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  font-size: 12px;
  color: #334155;
  overflow-x: auto;
}

.api-config-tip {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid #e2e8f0;
  font-size: 12px;
  color: #94a3b8;
}

.api-config-empty {
  padding: 16px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}

.password-readonly {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 14px;
  color: #94a3b8;
  letter-spacing: 4px;
  user-select: none;
}
</style>
