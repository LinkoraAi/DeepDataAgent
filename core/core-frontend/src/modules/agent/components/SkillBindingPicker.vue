<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { errorMessage } from '@/shared/composables/useAsync';
import { MAX_SKILL_BINDINGS } from '../mappers';
import type { SkillBindingDraft } from '../mappers';
import { listSkills, listVersions } from '@/modules/skill/api';
import type { SkillVersionWire, SkillWire } from '@/modules/skill/api';

/**
 * 技能绑定选择器（仅本系统自建技能，≤20 条）。
 * <p>每项可在"动态最新版"与"指定版本（钉版）"之间切换：钉版从版本列表取 epoch 版本键写入 version 键，
 * 动态版省略该键（沙箱准备期解析当时最新版本）。版本列表按技能懒加载并缓存。</p>
 */
const props = defineProps<{
  /** 技能绑定草稿（v-model） */
  modelValue: SkillBindingDraft[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: SkillBindingDraft[]];
}>();

/** 技能清单（custom 来源，一次取满管理端规模）。 */
const skills = ref<SkillWire[]>([]);
/** 清单加载中。 */
const loadingSkills = ref(false);
/** 技能 ID → 版本列表（null = 未加载）。 */
const versionCache = ref<Record<string, SkillVersionWire[] | null>>({});
/** 技能 ID → 版本列表加载中。 */
const versionLoading = ref<Record<string, boolean>>({});

/** 下拉选项（value=技能 ID，label=展示名；已绑定但清单未含的技能补占位项，避免回显丢失）。 */
const skillOptions = computed(() => {
  const known = skills.value.map((item) => ({
    value: item.id,
    label: item.latest_version ? item.display_title : `${item.display_title}（暂无版本）`,
  }));
  const ids = new Set(skills.value.map((item) => item.id));
  const extras = props.modelValue
    .filter((binding) => !ids.has(binding.skillId))
    .map((binding) => ({ value: binding.skillId, label: `${binding.displayTitle || binding.skillId}（清单未含该技能）` }));
  return [...known, ...extras];
});

/** 已绑定技能 ID 集合（多选下拉回显用）。 */
const boundIds = computed(() => props.modelValue.map((item) => item.skillId));

/** 是否已达绑定上限。 */
const isMaxed = computed(() => props.modelValue.length >= MAX_SKILL_BINDINGS);

/** 取技能展示名（未加载清单时回落技能 ID）。 */
function titleOf(skillId: string): string {
  const hit = skills.value.find((item) => item.id === skillId);
  return hit ? hit.display_title : skillId;
}

/** 装载技能清单（失败直显后端原因，保留已选绑定）。 */
async function loadSkills(): Promise<void> {
  loadingSkills.value = true;
  try {
    const resp = await listSkills({ source: 'custom', limit: 100 });
    skills.value = resp.data ?? [];
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loadingSkills.value = false;
  }
}

/** 多选变更：保留既有绑定口径，新增项默认动态最新版；超上限截断并提示。 */
function onBoundChange(value: unknown): void {
  const ids = Array.isArray(value) ? value.map((item) => String(item)) : [];
  const next: SkillBindingDraft[] = [];
  for (const id of ids) {
    const existing = props.modelValue.find((item) => item.skillId === id);
    next.push(existing ? { ...existing } : { skillId: id, version: '', displayTitle: titleOf(id) });
  }
  if (next.length > MAX_SKILL_BINDINGS) {
    MessagePlugin.warning(`技能绑定最多 ${MAX_SKILL_BINDINGS} 个，已截断`);
    write(next.slice(0, MAX_SKILL_BINDINGS));
    return;
  }
  write(next);
}

/** 整体回写（同时刷新展示名）。 */
function write(next: SkillBindingDraft[]): void {
  emit('update:modelValue', next.map((item) => ({ ...item, displayTitle: titleOf(item.skillId) })));
}

/** 移除单条绑定。 */
function onRemove(index: number): void {
  write(props.modelValue.filter((_item, itemIndex) => itemIndex !== index));
}

/** 拉取指定技能的版本清单（懒加载 + 缓存；失败回落空数组以便重试）。 */
async function loadVersions(skillId: string): Promise<void> {
  if (versionCache.value[skillId]) {
    return;
  }
  versionLoading.value = { ...versionLoading.value, [skillId]: true };
  try {
    const resp = await listVersions(skillId, { limit: 100 });
    versionCache.value = { ...versionCache.value, [skillId]: resp.data ?? [] };
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
    versionCache.value = { ...versionCache.value, [skillId]: [] };
  } finally {
    versionLoading.value = { ...versionLoading.value, [skillId]: false };
  }
}

/** 版本口径切换：动态版清空版本键；切钉版时确保已加载版本列表。 */
function onModeChange(index: number, pinned: boolean): void {
  const next = [...props.modelValue];
  const current = next[index];
  if (!current) {
    return;
  }
  next[index] = { ...current, version: pinned ? current.version : '' };
  write(next);
  if (pinned) {
    void loadVersions(current.skillId);
  }
}

/** 写入钉版版本键（空值回落动态版）。 */
function onVersionChange(index: number, version: string): void {
  const next = [...props.modelValue];
  const current = next[index];
  if (!current) {
    return;
  }
  next[index] = { ...current, version: (version ?? '').trim() };
  emit('update:modelValue', next);
}

/** 该绑定是否为钉版（版本键非空即钉版）。 */
function isPinned(binding: SkillBindingDraft): boolean {
  return Boolean((binding.version ?? '').trim());
}

/** 版本下拉选项（epoch 原样 + 创建时间）。 */
function versionOptionsOf(skillId: string): Array<{ label: string; value: string }> {
  return (versionCache.value[skillId] ?? []).map((item) => ({
    label: `${item.version}（${item.created_at.slice(0, 19).replace('T', ' ')}）`,
    value: item.version,
  }));
}

onMounted(() => {
  void loadSkills();
});
</script>

<template>
  <div class="skill-binding">
    <div class="skill-binding__picker">
      <t-select
        :value="boundIds"
        :options="skillOptions"
        :loading="loadingSkills"
        :multiple="true"
        :filterable="true"
        :disabled="isMaxed"
        clearable
        :placeholder="isMaxed ? `已达 ${MAX_SKILL_BINDINGS} 个绑定上限` : '选择要绑定的自定义技能'"
        @change="onBoundChange"
      />
      <t-button variant="text" theme="primary" size="small" :loading="loadingSkills" @click="loadSkills">刷新</t-button>
    </div>
    <p class="skill-binding__hint">
      已绑定 {{ modelValue.length }} / {{ MAX_SKILL_BINDINGS }}；动态最新版不提交 version 键（装配期解析），
      指定版本为钉版（技能删除该版本时将返回冲突原因）。
    </p>

    <ul v-if="modelValue.length" class="skill-binding__list">
      <li v-for="(binding, index) in modelValue" :key="binding.skillId" class="skill-binding__row">
        <div class="skill-binding__title">
          <span class="skill-binding__name">{{ titleOf(binding.skillId) || binding.skillId }}</span>
          <span class="skill-binding__id">{{ binding.skillId }}</span>
        </div>
        <t-radio-group
          :value="isPinned(binding) ? 'pinned' : 'latest'"
          borderless
          size="small"
          @change="(value: string) => onModeChange(index, value === 'pinned')"
        >
          <t-radio value="latest">动态最新版</t-radio>
          <t-radio value="pinned">指定版本</t-radio>
        </t-radio-group>
        <t-select
          v-if="isPinned(binding)"
          :value="binding.version"
          :options="versionOptionsOf(binding.skillId)"
          :loading="versionLoading[binding.skillId] === true"
          placeholder="选择版本（epoch 键）"
          size="small"
          class="skill-binding__version"
          @change="(value: unknown) => onVersionChange(index, String(value ?? ''))"
        />
        <t-button size="extra-small" variant="text" theme="danger" @click="onRemove(index)">移除</t-button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.skill-binding__picker {
  display: flex;
  gap: 8px;
  align-items: center;
}

.skill-binding__hint {
  margin: 6px 0 8px;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.skill-binding__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.skill-binding__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 6px 8px;
  border: 1px solid var(--td-component-border, #e7e7e7);
  border-radius: 4px;
  margin-bottom: 6px;
}

.skill-binding__title {
  flex: 1 1 200px;
  min-width: 0;
}

.skill-binding__name {
  display: block;
  font-size: 13px;
}

.skill-binding__id {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--td-text-color-placeholder, rgba(0, 0, 0, 0.4));
}

.skill-binding__version {
  flex: 0 0 260px;
}
</style>
