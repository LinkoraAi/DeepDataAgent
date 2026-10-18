// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { useCursorPages, usePagePagination } from './usePagination';

describe('usePagePagination', () => {
  it('should 回填 total 并生成请求参数', () => {
    const pager = usePagePagination<string>(20);
    const list = pager.apply({ list: ['a'], total: 45, page: 1, size: 20 });
    expect(list).toEqual(['a']);
    expect(pager.total.value).toBe(45);
    expect(pager.params()).toEqual({ page: 1, size: 20 });
    expect(pager.pageCount.value).toBe(3);
  });

  it('should 页码越界回退_when 数据减少', () => {
    const pager = usePagePagination<string>();
    pager.page.value = 5;
    pager.apply({ list: [], total: 0, page: 5, size: 10 });
    expect(pager.safePage.value).toBe(1);
  });

  it('should reset 回第一页', () => {
    const pager = usePagePagination<string>();
    pager.page.value = 3;
    pager.reset();
    expect(pager.page.value).toBe(1);
  });
});

describe('useCursorPages', () => {
  it('should 追加累积并携带游标_when 加载更多', () => {
    const pager = useCursorPages<{ id: string }>(2);
    pager.apply({ data: [{ id: '1' }, { id: '2' }], first_id: '1', last_id: '2', has_more: true, next_page: 'cur_a' }, false);
    expect(pager.items.value).toHaveLength(2);
    expect(pager.nextParams()).toEqual({ limit: 2, after_id: 'cur_a' });
    pager.apply({ data: [{ id: '3' }], first_id: '2', last_id: '3', has_more: false }, true);
    expect(pager.items.value.map((i) => i.id)).toEqual(['1', '2', '3']);
    expect(pager.hasMore.value).toBe(false);
  });

  it('should 首页参数不含游标', () => {
    const pager = useCursorPages<string>();
    expect(pager.nextParams()).toEqual({ limit: 20 });
  });
});
