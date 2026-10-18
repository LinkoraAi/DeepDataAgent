<script setup lang="ts">
import { computed } from 'vue';
import { dictLabel, dictTheme } from '@/shared/enums/dict';
import type { EnumDict } from '@/shared/enums/dict';

/** 枚举状态标签：编码 → 中文 + 主题色（未知编码原样展示）。 */
const props = defineProps<{
  /** 后端枚举编码 */
  code: string | null | undefined;
  /** 枚举字典（如 DOC_STATUS） */
  dict: EnumDict;
}>();

const label = computed(() => dictLabel(props.dict, props.code));
const theme = computed(() => dictTheme(props.dict, props.code));
</script>

<template>
  <t-tag :theme="theme" variant="light" size="small">{{ label }}</t-tag>
</template>
