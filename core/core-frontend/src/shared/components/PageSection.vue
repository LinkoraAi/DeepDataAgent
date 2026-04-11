<template>
  <section class="page-section">
    <header class="page-section__header">
      <p class="page-section__eyebrow">{{ title }}</p>
      <p class="page-section__description">{{ description }}</p>
    </header>
    <slot />
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
}>();
</script>
