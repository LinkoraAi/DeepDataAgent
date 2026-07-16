<template>
  <div class="data-preview">
    <div class="data-preview__header">
      <div class="data-preview__info">
        <span class="data-preview__count">{{ totalRows }} 行数据</span>
        <span v-if="isFiltered" class="data-preview__filter-info">（已筛选）</span>
      </div>
      <div class="data-preview__actions">
        <t-input
          v-model="searchKeyword"
          placeholder="搜索..."
          size="small"
          clearable
          style="width: 200px"
        />
        <t-button theme="default" variant="outline" size="small" @click="exportCSV">
          <template #icon><download-icon /></template>
          导出 CSV
        </t-button>
      </div>
    </div>

    <div class="data-preview__table-wrapper">
      <t-table
        :data="paginatedData"
        :columns="columns"
        :pagination="paginationConfig"
        :sort="sortConfig"
        @sort-change="handleSortChange"
        @page-change="handlePageChange"
        bordered
        stripe
        hover
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { DownloadIcon } from 'tdesign-icons-vue-next';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol, PageInfo, SortInfo } from 'tdesign-vue-next';

const props = defineProps<{
  data: Record<string, any>[];
}>();

const searchKeyword = ref('');
const currentPage = ref(1);
const pageSize = ref(20);
const sortConfig = ref<SortInfo | null>(null);

// Generate columns from data
const columns = computed<PrimaryTableCol[]>(() => {
  if (props.data.length === 0) return [];
  const firstRow = props.data[0];
  return Object.keys(firstRow).map((key) => ({
    colKey: key,
    title: key,
    sorter: true,
    width: 150,
    ellipsis: true,
  }));
});

// Filter data by search keyword
const filteredData = computed(() => {
  if (!searchKeyword.value.trim()) {
    return props.data;
  }
  const keyword = searchKeyword.value.toLowerCase();
  return props.data.filter((row) =>
    Object.values(row).some((val) =>
      String(val).toLowerCase().includes(keyword)
    )
  );
});

// Sort data
const sortedData = computed(() => {
  if (!sortConfig.value) {
    return filteredData.value;
  }
  const { sortBy, descending } = sortConfig.value;
  return [...filteredData.value].sort((a, b) => {
    const aVal = a[sortBy];
    const bVal = b[sortBy];
    if (aVal === bVal) return 0;
    if (aVal === null || aVal === undefined) return 1;
    if (bVal === null || bVal === undefined) return -1;
    const comparison = aVal < bVal ? -1 : 1;
    return descending ? -comparison : comparison;
  });
});

// Paginate data
const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  const end = start + pageSize.value;
  return sortedData.value.slice(start, end);
});

const totalRows = computed(() => sortedData.value.length);
const isFiltered = computed(() => searchKeyword.value.trim().length > 0);

const paginationConfig = computed(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: totalRows.value,
  showJumper: true,
  showPageSize: true,
  pageSizeOptions: [10, 20, 50, 100],
}));

function handleSortChange(sort: SortInfo) {
  sortConfig.value = sort;
  currentPage.value = 1; // Reset to first page on sort
}

function handlePageChange(pageInfo: PageInfo) {
  currentPage.value = pageInfo.current;
  pageSize.value = pageInfo.pageSize;
}

function exportCSV() {
  try {
    if (props.data.length === 0) {
      MessagePlugin.warning('没有数据可导出');
      return;
    }

    const headers = Object.keys(props.data[0]);
    const csvRows = [
      headers.join(','),
      ...props.data.map((row) =>
        headers
          .map((header) => {
            const val = row[header];
            const escaped = String(val ?? '').replace(/"/g, '""');
            return `"${escaped}"`;
          })
          .join(',')
      ),
    ];

    const csvContent = csvRows.join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `data-export-${new Date().toISOString().slice(0, 10)}.csv`;
    link.click();
    URL.revokeObjectURL(url);

    MessagePlugin.success('导出成功');
  } catch (error) {
    console.error('Export CSV failed:', error);
    MessagePlugin.error('导出失败');
  }
}
</script>

<style scoped lang="less">
.data-preview {
  &__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
  }

  &__info {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    color: var(--td-text-color-secondary);
  }

  &__count {
    font-weight: 500;
  }

  &__filter-info {
    font-size: 12px;
    color: var(--td-brand-color);
  }

  &__actions {
    display: flex;
    gap: 8px;
  }

  &__table-wrapper {
    overflow-x: auto;
  }
}
</style>
