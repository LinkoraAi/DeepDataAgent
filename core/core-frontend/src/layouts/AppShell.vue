<template>
  <t-layout class="app-shell">
    <t-header class="app-shell__header">
      <div>
        <p class="app-shell__eyebrow">AgentScope Java + OpenSandbox</p>
        <h1 class="app-shell__title">DeepDataAgent</h1>
      </div>
      <div class="app-shell__nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="app-shell__nav-link">
          <t-button variant="text" theme="default">{{ item.label }}</t-button>
        </RouterLink>
      </div>
    </t-header>
    <t-content class="app-shell__content">
      <RouterView />
    </t-content>
  </t-layout>
</template>

<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router';
import type { NavigationItem } from '@/shared/types/navigation';

const navItems: NavigationItem[] = [
  { label: 'Agent', to: '/agent' },
  { label: 'Skills', to: '/skills' },
  { label: 'Memory', to: '/memory' },
  { label: 'Datasource', to: '/datasource' },
];
</script>
