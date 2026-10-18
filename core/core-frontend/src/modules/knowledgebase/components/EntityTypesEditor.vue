<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { BUILTIN_ENTITY_TYPES } from '@/shared/enums/dict';
import { MAX_ENTITY_TYPES } from '../kbDraft';

/**
 * 实体类型编辑器：内置 12 类只读展示 + 自定义类型标签录入。
 * <p>内置类型由后端内置语义决定、不可配置，故以只读样式呈现（描边小标签、无删除入口），
 * 与可编辑的自定义胶囊在视觉上明确区隔，避免用户误以为可删除；自定义类型以
 * 「标签胶囊 + 末尾『＋』原位展开输入框」录入。</p>
 * <p>合法性口径（2~30 字符、仅中英文数字下划线、不与内置 12 类重名）统一由上层 validateDraft
 * 在提交前拦截，本组件只负责录入与展示，错误经 error 属性回显。</p>
 * <p><b>关键不变量</b>：达到上限时只禁用「＋」入口，MUST NOT 把 disabled 施加到已添加项。
 * TDesign 的 t-tag-input 在 disabled 时会连带隐藏标签的关闭图标
 * （源码 useTagList：closable = !isDisabled），一旦禁用便既无法新增也无法删除，形成死局；
 * 故上限闸门单独由「＋」按钮承载，标签的删除入口恒可用。</p>
 */
const props = defineProps<{
  /** 自定义实体类型名称列表 */
  modelValue: string[];
  /** 实体类型字段错误信息（提交时统一校验产物） */
  error?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();

/** 是否已达自定义类型上限（仅用于禁用「＋」入口，不传导到已添加项） */
const isMaxed = computed(() => props.modelValue.length >= MAX_ENTITY_TYPES);

/** 是否处于新增输入态 */
const editing = ref(false);
/** 新增输入框草稿值 */
const draft = ref('');
/** 输入框实例（仅调用 focus，避免引入组件类型依赖） */
const inputRef = ref<{ focus: () => void } | null>(null);

/** 底部提示文案：上限态优先说明出路，常态说明录入方式与内置保留规则 */
const hint = computed(() =>
  isMaxed.value
    ? `已达自定义实体类型上限 ${MAX_ENTITY_TYPES} 个，删除后可再添加。`
    : '回车添加，点击标签上的关闭图标可删除。「其他」为强制保留类型，禁止自定义同名条目。',
);

/** 进入新增态：复位草稿并聚焦输入框；已达上限时不响应 */
function startEdit(): void {
  if (isMaxed.value) {
    return;
  }
  editing.value = true;
  draft.value = '';
  // 输入框由 v-if 渲染，需等下一帧拿到实例后再聚焦
  void nextTick(() => inputRef.value?.focus());
}

/** 退出新增态并清空草稿 */
function stopEdit(): void {
  editing.value = false;
  draft.value = '';
}

/**
 * 提交草稿：空白视为取消；非空则 trim 后追加（越界兜底截断到上限）。
 * @param keepEditing 提交后是否停留在输入态（回车连续录入为 true，失焦收尾为 false）
 */
function commit(keepEditing: boolean): void {
  const name = draft.value.trim();
  if (name === '') {
    stopEdit();
    return;
  }
  const next = [...props.modelValue, name].slice(0, MAX_ENTITY_TYPES);
  emit('update:modelValue', next);
  draft.value = '';
  if (!keepEditing || next.length >= MAX_ENTITY_TYPES) {
    stopEdit();
    return;
  }
  // 连续录入：清空后重新聚焦，避免用户每加一条都要再点一次「＋」
  void nextTick(() => inputRef.value?.focus());
}

/** 回车提交：保持输入态，支持连续录入多条 */
function onEnter(): void {
  commit(true);
}

/** 失焦收尾：非空即提交并退出输入态；已退出输入态时忽略（Esc 之后的善后事件） */
function onBlur(): void {
  if (!editing.value) {
    return;
  }
  commit(false);
}

/**
 * 键盘处理：Esc 取消录入。
 * <p>keydown 是 t-input 的组件事件而非原生事件，签名为 {@code (value, { e })}；
 * 需 stopPropagation——抽屉默认 close-on-esc-keydown，事件继续冒泡会连带关闭整个表单。</p>
 */
function onKeydown(_value: string | number, context: { e: KeyboardEvent }): void {
  const e = context?.e;
  if (!e || e.key !== 'Escape') {
    return;
  }
  e.stopPropagation();
  stopEdit();
}

/** 删除指定下标的已添加项（删除入口恒可用，不受上限态影响） */
function removeAt(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index));
}
</script>

<template>
  <div>
    <div class="kb-field">
      <label class="kb-field__label">内置实体类型（只读，共 {{ BUILTIN_ENTITY_TYPES.length }} 类）</label>
      <div class="kb-entity__builtin">
        <t-tag
          v-for="builtin in BUILTIN_ENTITY_TYPES"
          :key="builtin"
          variant="light-outline"
          size="small"
          :title="builtin"
        >
          {{ builtin }}
        </t-tag>
      </div>
    </div>

    <div class="kb-field">
      <label class="kb-field__label">
        自定义实体类型（最多 {{ MAX_ENTITY_TYPES }} 个，已用 {{ modelValue.length }}）
      </label>

      <div class="kb-entity__custom">
        <t-tag
          v-for="(name, index) in modelValue"
          :key="`${name}-${index}`"
          class="kb-entity__chip"
          variant="light"
          size="medium"
          :max-width="260"
          :title="name"
          closable
          @close="removeAt(index)"
        >
          {{ name }}
        </t-tag>

        <t-input
          v-if="editing"
          ref="inputRef"
          v-model="draft"
          class="kb-entity__input"
          size="small"
          autofocus
          placeholder="输入名称后回车添加"
          @enter="onEnter"
          @blur="onBlur"
          @keydown="onKeydown"
        />

        <t-button
          v-else
          class="kb-entity__add"
          shape="square"
          size="small"
          variant="outline"
          :disabled="isMaxed"
          :aria-label="isMaxed ? '已达上限，删除后可再添加' : '添加实体类型'"
          @click="startEdit"
        >
          <t-icon name="add" />
        </t-button>
      </div>

      <p v-if="error" class="kb-field__error">{{ error }}</p>
      <p v-else class="kb-field__hint">{{ hint }}</p>
    </div>
  </div>
</template>

<style scoped>
.kb-field {
  margin-bottom: 12px;
}

.kb-field__label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--td-text-color-primary, rgba(0, 0, 0, 0.9));
}

/* 内置只读标签：描边 + 小号，与可编辑胶囊（实底 + 中号 + 关闭图标）形成明确的只读/可操作区隔 */
.kb-entity__builtin {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/*
 * 加深内置标签描边：TDesign light-outline 默认边框取 --td-component-border（gray-4 #ddd），
 * 而标签底色与所在分区同为 secondarycontainer，同色叠同浅边导致边界几乎不可见；
 * 故在灰阶上加深一档到 gray-5，仍取令牌不硬编码颜色。
 * 选择器经 :deep 编译后权重为 (0,3,0)，高于组件库默认的 (0,2,0)，不依赖样式加载顺序。
 */
.kb-entity__builtin :deep(.t-tag) {
  border-color: var(--td-gray-color-5, #c6c6c6);
}

/* 自定义胶囊与「＋」同一行自动换行，纵向居中对齐保证关闭图标与按钮视觉同高 */
.kb-entity__custom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.kb-entity__input {
  flex: 1 1 180px;
  min-width: 160px;
  max-width: 280px;
}

.kb-field__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-secondary, rgba(0, 0, 0, 0.6));
}

.kb-field__error {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-error-color, #d54941);
}
</style>
