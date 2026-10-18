<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { createDatasource, updateDatasource } from '@/modules/datasource/api/datasource';
import type { DatasourceResponse } from '@/modules/datasource/api/datasource';
import { errorMessage } from '@/shared/composables/useAsync';
import { MAX_DESCRIPTION_LENGTH, validateDescription, validateName } from '@/modules/datasource/mappers';

/**
 * API 数据源主连接抽屉（新建 / 编辑复用）。
 * 新建：仅保存名称/描述/类型 API；数据表创建成功后由详情侧「添加数据表」经 ApiSchemaDrawer 配置。
 * 编辑：仅更新名称/描述（类型不可改，API 连接无 JDBC 配置）。
 */
const props = defineProps<{
  visible: boolean;
  /** 编辑时传入；为空表示新建 */
  datasource?: DatasourceResponse | null;
}>();

const emit = defineEmits<{ 'update:visible': [boolean]; saved: [DatasourceResponse] }>();

const isEdit = computed(() => !!props.datasource);
const form = reactive({ name: '', description: '' });
const errors = ref<Record<string, string>>({});
const saving = ref(false);

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
  errors.value = errs;
  return Object.keys(errs).length === 0;
}

async function onSave(): Promise<void> {
  if (!validate()) {
    MessagePlugin.error('请先修正表单错误');
    return;
  }
  saving.value = true;
  try {
    let saved: DatasourceResponse;
    if (isEdit.value && props.datasource) {
      saved = await updateDatasource(props.datasource.id, { name: form.name, description: form.description });
      MessagePlugin.success('API 数据源已更新');
    } else {
      saved = await createDatasource({ name: form.name, type: 'API', description: form.description });
      MessagePlugin.success('API 数据源已创建，请添加数据表');
    }
    emit('saved', saved);
    emit('update:visible', false);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      form.name = props.datasource?.name ?? '';
      form.description = props.datasource?.description ?? '';
      errors.value = {};
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
    :header="isEdit ? `编辑 API 数据源：${datasource?.name ?? ''}` : '新建 API 数据源'"
    size="520px"
    placement="right"
    @update:visible="(v: boolean) => emit('update:visible', v)"
    @close="onClose"
  >
    <t-alert v-if="!isEdit" theme="info" class="dda-apisource__tip">
      API 数据源先保存主连接，创建后在详情页「添加数据表」配置一张或多张 API 表。
    </t-alert>
    <t-form :data="form" label-align="top" @submit.prevent>
      <t-form-item label="名称" name="name">
        <t-input v-model="form.name" :maxlength="100" placeholder="中英文或字母开头，可含 - _ 数字" />
        <template #help><span v-if="errors.name" class="dda-apisource__error">{{ errors.name }}</span></template>
      </t-form-item>
      <t-form-item label="描述" name="description">
        <t-textarea v-model="form.description" :maxlength="MAX_DESCRIPTION_LENGTH" placeholder="选填，最多 500 字" :autosize="{ minRows: 2, maxRows: 4 }" />
        <template #help><span v-if="errors.description" class="dda-apisource__error">{{ errors.description }}</span></template>
      </t-form-item>
    </t-form>
    <template #footer>
      <div class="dda-apisource__footer">
        <t-button variant="outline" @click="onClose">取消</t-button>
        <t-button theme="primary" :loading="saving" @click="onSave">保存</t-button>
      </div>
    </template>
  </t-drawer>
</template>

<style scoped>
.dda-apisource__tip {
  margin-bottom: 16px;
}

.dda-apisource__error {
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.dda-apisource__footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  width: 100%;
}
</style>
