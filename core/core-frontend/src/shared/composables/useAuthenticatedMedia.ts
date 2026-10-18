import { onScopeDispose, ref } from 'vue';
import type { Ref } from 'vue';
import { fetchBlob } from '@/shared/api/blob';

/**
 * 鉴权媒体加载：后端图片/文档端点要求 Bearer 头，<img src> 直连会 401，
 * 统一经 fetchBlob 取二进制转一次性 object URL，组件作用域销毁自动 revoke。
 */
export interface AuthenticatedMediaHandle {
  /** 已就绪的 object URL（加载中/失败为空串） */
  url: Ref<string>;
  loading: Ref<boolean>;
  error: Ref<string>;
  /** 加载指定后端路径（同一路径重复调用直接复用） */
  load: (path: string) => Promise<string>;
}

export function useAuthenticatedMedia(): AuthenticatedMediaHandle {
  const url = ref('');
  const loading = ref(false);
  const error = ref('');
  let loadedPath = '';
  const created: string[] = [];

  async function load(path: string): Promise<string> {
    if (loadedPath === path && url.value) {
      return url.value;
    }
    loading.value = true;
    error.value = '';
    try {
      const blob = await fetchBlob(path);
      const objectUrl = URL.createObjectURL(blob);
      created.push(objectUrl);
      // 释放上一次的 URL（保留列表便于统一清理）
      url.value = objectUrl;
      loadedPath = path;
      return objectUrl;
    } catch (e) {
      error.value = e instanceof Error ? e.message : '媒体加载失败';
      return '';
    } finally {
      loading.value = false;
    }
  }

  onScopeDispose(() => {
    created.forEach((u) => URL.revokeObjectURL(u));
  });

  return { url, loading, error, load };
}
