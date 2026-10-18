<script setup lang="ts">
import { computed, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import DropZone from '@/shared/components/DropZone.vue';
import FormSection from '@/shared/components/FormSection.vue';
import { LAYER } from '@/shared/constants/layers';
import { useAsync, errorMessage } from '@/shared/composables/useAsync';
import { createSkill, createVersion } from '../api';
import type { SkillVersionWire, SkillWire } from '../api';
import { validateSkillZip } from '../mappers';

/**
 * 技能包上传对话框（新建技能 / 既有技能发版复用，props.mode 区分）。
 * 选文件入口为大号拖拽区（DropZone，内置隐藏 input[type=file]，accept=.zip）；
 * 前端拦截：仅 .zip 且 ≤50MB（validateSkillZip）；包结构错误由后端 400 回显，
 * 对话框保留并直显后端 message，允许改包后重新选择提交。
 */
const props = defineProps<{
  /** 模式：create=新建技能（POST /skills），version=发版（POST /skills/{id}/versions） */
  mode: 'create' | 'version';
  /** 发版模式的目标技能 ID（mode=version 必填） */
  skillId?: string;
  /** 发版模式的目标技能展示名（仅用于标题文案） */
  skillName?: string;
}>();

const emit = defineEmits<{
  /** 上传成功（携带后端返回的技能壳或版本） */
  success: [result: SkillWire | SkillVersionWire];
}>();

/** 对话框显隐（v-model:visible）。 */
const visible = defineModel<boolean>('visible', { required: true });

const selected = ref<File | null>(null);
const displayTitle = ref('');
/** 前端校验提示（选择即校验，不通过不落 selected）。 */
const pickError = ref('');
/** 后端错误提示（400 结构违规 / 413 超限等，保留对话框直显）。 */
const serverError = ref('');

const { loading, error, run } = useAsync<SkillWire | SkillVersionWire>();

const headerText = computed(() =>
  props.mode === 'create' ? '上传技能包（新建技能）' : `上传新版本：${props.skillName || props.skillId || ''}`,
);

/** 应用所选文件（先跑前端校验，未通过则仅提示不选中）。 */
function applyFile(file: File | null): void {
  serverError.value = '';
  pickError.value = '';
  if (!file) {
    selected.value = null;
    return;
  }
  const result = validateSkillZip(file);
  if (!result.ok) {
    selected.value = null;
    pickError.value = result.reason ?? '文件不符合要求';
    return;
  }
  selected.value = file;
}

/** 人性化大小展示（对话框局部小工具，不参与业务校验）。 */
function humanSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** 重置对话框内部状态（关闭后复位，避免脏文件/脏错误残留）。 */
function resetState(): void {
  selected.value = null;
  displayTitle.value = '';
  pickError.value = '';
  serverError.value = '';
}

/** 提交上传：成功关框并广播；失败保留对话框直显后端 message。 */
async function submit(): Promise<void> {
  pickError.value = '';
  const file = selected.value;
  if (!file) {
    pickError.value = '请先选择 .zip 技能包文件';
    return;
  }
  if (props.mode === 'version' && !props.skillId) {
    pickError.value = '缺少目标技能 ID，无法发版';
    return;
  }
  const title = displayTitle.value.trim();
  const result = await run(() =>
    props.mode === 'create' ? createSkill(file, title || undefined) : createVersion(props.skillId as string, file),
  );
  if (error.value || !result) {
    serverError.value = errorMessage(error.value ?? '技能包上传失败');
    return;
  }
  MessagePlugin.success(props.mode === 'create' ? '技能创建成功' : '新版本发布成功');
  emit('success', result);
  resetState();
  visible.value = false;
}

/** 关闭时复位状态。 */
function onClosed(): void {
  resetState();
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    :header="headerText"
    :width="560"
    :close-on-overlay-click="false"
    placement="center"
    attach="body"
    :z-index="LAYER.dialog"
    @closed="onClosed"
  >
    <DropZone
      title="拖拽技能包到此处或点击浏览"
      subtitle="仅接受单个 .zip 技能包，大小不超过 50MB"
      hint="包内需唯一顶级目录 + SKILL.md（YAML frontmatter 含 name / description）"
      accept=".zip"
      :disabled="loading"
      @selected="(files: File[]) => applyFile(files[0] ?? null)"
    />

    <div v-if="selected" class="skill-upload__picked">
      <t-icon name="code" size="16px" />
      <span class="skill-upload__picked-name">{{ selected.name }}（{{ humanSize(selected.size) }}）</span>
    </div>
    <p v-else class="skill-upload__hint">尚未选择技能包文件</p>
    <p v-if="pickError" class="skill-upload__error">{{ pickError }}</p>

    <div v-if="mode === 'create'" class="skill-upload__title">
      <label class="skill-upload__title-label">展示名（可选）</label>
      <t-input
        v-model="displayTitle"
        placeholder="留空则使用包内 frontmatter name 作为展示名"
        :maxcharacter="255"
        clearable
      />
    </div>

    <FormSection
      title="技能包结构要求"
      hint="以下要求由后端强校验，违反将返回 400 并在此处显示具体原因"
    >
      <ul class="skill-upload__rules">
        <li>仅支持单个 .zip 压缩包，大小不超过 50MB；</li>
        <li>包内必须且只能有一个顶级目录，目录名与 SKILL.md frontmatter 的 name 一致；</li>
        <li>顶级目录必须包含 SKILL.md，其 YAML frontmatter 必须含 name 与 description 字段；</li>
        <li>发版时新包的 frontmatter name 必须与技能首版一致（跨版本不可改名）。</li>
      </ul>
    </FormSection>

    <t-alert v-if="serverError" theme="error" :message="serverError" class="skill-upload__alert" />

    <template #footer>
      <t-space>
        <t-button variant="outline" @click="visible = false">取消</t-button>
        <t-button theme="primary" :loading="loading" @click="submit">
          {{ mode === 'create' ? '上传并创建' : '上传并发版' }}
        </t-button>
      </t-space>
    </template>
  </t-dialog>
</template>

<style scoped>
.skill-upload__rules {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.skill-upload__picked {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
  padding: 8px 12px;
  font-size: 13px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--dda-card-border);
  border-radius: var(--td-radius-medium);
}

.skill-upload__picked-name {
  overflow: hidden;
  font-weight: 600;
  color: var(--td-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-upload__hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.skill-upload__error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}

.skill-upload__title {
  margin-top: 16px;
}

.skill-upload__title-label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.skill-upload__alert {
  margin-top: 12px;
}
</style>