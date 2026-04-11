<template>
  <PageSection
    title="Datasource 数据连接上下文"
    description="这里预留数据库元数据、API Schema、表结构与字段结构管理。"
  >
    <div class="page-grid">
      <t-card title="连接管理">预留 DatasourceConnection 录入与状态检测。</t-card>
      <t-card title="Schema 探查">预留 DatabaseSchema、ApiSchema、TableInfo、ApiField 与 ColumnInfo 视图。</t-card>
    </div>
  </PageSection>
</template>

<script setup lang="ts">
import PageSection from '@/shared/components/PageSection.vue';
</script>
