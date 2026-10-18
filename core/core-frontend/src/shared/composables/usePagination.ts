import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import type { CursorPage, PaginatedResp } from '@/shared/types/api';

/**
 * 页码分页状态（KB/文档/切片/model-profiles/数据源）。
 * 后端兜底口径：page<1→1、size 上限 100；本组合式仅维护 UI 状态并生成请求参数。
 */
export interface PagePagination<T> {
  page: Ref<number>;
  size: Ref<number>;
  total: Ref<number>;
  /** 应用一页响应（回填 total 与越界回退） */
  apply: (resp: PaginatedResp<T>) => T[];
  /** 生成请求查询参数 */
  params: () => { page: number; size: number };
  /** 回到第一页 */
  reset: () => void;
  /** 当前页码（越界收敛后） */
  safePage: ComputedRef<number>;
  pageCount: ComputedRef<number>;
}

export function usePagePagination<T>(defaultSize = 10): PagePagination<T> {
  const page = ref(1);
  const size = ref(defaultSize);
  const total = ref(0);

  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)));
  const safePage = computed(() => Math.min(Math.max(1, page.value), pageCount.value));

  function apply(resp: PaginatedResp<T>): T[] {
    total.value = resp.total ?? 0;
    if (page.value > pageCount.value) {
      page.value = pageCount.value;
    }
    return resp.list ?? [];
  }

  function params(): { page: number; size: number } {
    return { page: page.value, size: size.value };
  }

  function reset(): void {
    page.value = 1;
  }

  return { page, size, total, apply, params, reset, safePage, pageCount };
}

/**
 * 游标分页状态（agents/environments/sessions/skills；files 特例经 mapCursor 适配）。
 * 语义为"加载更多"：追加式累积，next_page/last_id 作为下一页游标。
 */
export interface CursorPagination<T> {
  items: Ref<T[]>;
  hasMore: Ref<boolean>;
  loading: Ref<boolean>;
  /** 应用一页响应并累积（append=false 时替换为首页） */
  apply: (resp: CursorPage<T>, append: boolean) => void;
  /** 下一页游标参数（首次为 {}） */
  nextParams: () => Record<string, string | number>;
  /** 重置（筛选条件变化时） */
  reset: () => void;
}

export function useCursorPages<T>(limit = 20): CursorPagination<T> {
  const items = ref<T[]>([]) as Ref<T[]>;
  const hasMore = ref(false);
  const loading = ref(false);
  const nextCursor = ref<string | null>(null);

  function apply(resp: CursorPage<T>, append: boolean): void {
    const list = resp.data ?? [];
    items.value = append ? [...items.value, ...list] : list;
    hasMore.value = Boolean(resp.has_more);
    nextCursor.value = resp.next_page ?? resp.last_id ?? null;
  }

  function nextParams(): Record<string, string | number> {
    const params: Record<string, string | number> = { limit };
    if (items.value.length > 0 && nextCursor.value) {
      params.after_id = nextCursor.value;
    }
    return params;
  }

  function reset(): void {
    items.value = [];
    hasMore.value = false;
    nextCursor.value = null;
  }

  return { items, hasMore, loading, apply, nextParams, reset };
}
