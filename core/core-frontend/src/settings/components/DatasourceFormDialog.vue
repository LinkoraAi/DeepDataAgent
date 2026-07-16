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

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'" label="主机地址" name="host">
        <t-input v-model="formData.host" placeholder="请输入主机地址" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'" label="端口" name="port">
        <t-input v-model="formData.port" type="number" placeholder="请输入端口号" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'" label="数据库名" name="database">
        <t-input v-model="formData.database" placeholder="请输入数据库名" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'" label="用户名" name="username">
        <t-input v-model="formData.username" placeholder="请输入用户名" />
      </t-form-item>

      <t-form-item v-if="formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'" label="密码" name="password">
        <t-input
          v-model="formData.password"
          type="password"
          :placeholder="isEdit ? '留空表示不修改' : '请输入密码'"
        />
      </t-form-item>

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

const typeOptions = [
  { value: 'MYSQL', label: 'MySQL', icon: 'M' },
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
        if ((formData.type === 'MYSQL' || formData.type === 'POSTGRESQL') && !value) {
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
        if ((formData.type === 'MYSQL' || formData.type === 'POSTGRESQL') && !value) {
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
        if ((formData.type === 'MYSQL' || formData.type === 'POSTGRESQL') && !value) {
          return { result: false, message: '请输入数据库名' };
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
        if ((formData.type === 'MYSQL' || formData.type === 'POSTGRESQL') && !value) {
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
        if (!isEdit.value && (formData.type === 'MYSQL' || formData.type === 'POSTGRESQL') && !value) {
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
  (val) => {
    dialogVisible.value = val;
    if (val) {
      if (props.editDatasource) {
        isEdit.value = true;
        formData.name = props.editDatasource.name;
        formData.type = props.editDatasource.type;
        formData.host = props.editDatasource.host || '';
        formData.port = props.editDatasource.port || 3306;
        formData.database = props.editDatasource.database || '';
        formData.username = '';
        formData.password = '';
        formData.description = props.editDatasource.description || '';
      } else {
        isEdit.value = false;
        formData.name = '';
        formData.type = 'MYSQL';
        formData.host = '';
        formData.port = 3306;
        formData.database = '';
        formData.username = '';
        formData.password = '';
        formData.description = '';
      }
    }
  }
);

watch(
  () => dialogVisible.value,
  (val) => {
    emit('update:visible', val);
  }
);

function handleTypeChange(type: string) {
  formData.type = type;
  if (type === 'MYSQL') {
    formData.port = 3306;
  } else if (type === 'POSTGRESQL') {
    formData.port = 5432;
  }
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
      type: formData.type,
      jdbcConfig:
        formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'
          ? {
              host: formData.host,
              port: formData.port,
              database: formData.database,
              username: formData.username,
              password: formData.password,
            }
          : undefined,
    };

    await datasourceStore.testConnection(params);
    MessagePlugin.success('连接测试成功');
  } catch (err: any) {
    console.error('Test connection failed:', err);
    MessagePlugin.error(err.message || '连接测试失败');
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
        type: formData.type,
        description: formData.description,
        jdbcConfig:
          formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'
            ? {
                host: formData.host,
                port: formData.port,
                database: formData.database,
                username: formData.username,
                password: formData.password || undefined,
              }
            : undefined,
      });
      MessagePlugin.success('更新成功');
    } else {
      await datasourceStore.createDatasource({
        name: formData.name,
        type: formData.type,
        description: formData.description,
        jdbcConfig:
          formData.type === 'MYSQL' || formData.type === 'POSTGRESQL'
            ? {
                host: formData.host,
                port: formData.port,
                database: formData.database,
                username: formData.username,
                password: formData.password,
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
</style>
