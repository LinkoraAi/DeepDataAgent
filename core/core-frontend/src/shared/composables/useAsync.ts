import { ref } from 'vue';
import type { Ref } from 'vue';
import { ApiError } from '@/shared/types/api';

/**
 * 异步动作三态封装：loading / error / run。
 * 错误统一捕获为 ApiError（或包装为 ApiError(0)），由调用方决定提示方式。
 */
export interface UseAsyncReturn<T> {
  loading: Ref<boolean>;
  error: Ref<ApiError | null>;
  /** 执行异步函数；返回 undefined 表示出错（成功值经 result 或返回值获取） */
  run: (fn: () => Promise<T>) => Promise<T | undefined>;
  result: Ref<T | undefined>;
}

export function useAsync<T>(): UseAsyncReturn<T> {
  const loading = ref(false);
  const error = ref<ApiError | null>(null);
  const result = ref<T | undefined>(undefined) as Ref<T | undefined>;
  /** 最新任务序号（每次 run 自增）：并发调用时只保留最后一次为"当前任务"。 */
  let latestId = 0;

  async function run(fn: () => Promise<T>): Promise<T | undefined> {
    // 递增任务序号：仅"最新一次发起"的任务负责写入三态，避免被更早的慢请求覆盖/提前熄灭 loading。
    const id = ++latestId;
    loading.value = true;
    error.value = null;
    try {
      const value = await fn();
      if (id === latestId) {
        result.value = value;
      }
      // 返回值语义保持向后兼容：每次调用都返回自身结果，不受是否被更新任务取代影响
      return value;
    } catch (e) {
      const apiError = e instanceof ApiError ? e : new ApiError(0, '', e instanceof Error ? e.message : String(e));
      if (id === latestId) {
        error.value = apiError;
      }
      return undefined;
    } finally {
      if (id === latestId) {
        loading.value = false;
      }
    }
  }

  return { loading, error, run, result };
}

/** 网络层错误特征（fetch 的 TypeError 等英文原文），用于归一为中文提示。 */
const NETWORK_ERROR_PATTERN = /failed to fetch|networkerror|network request failed|load failed/i;

/** 无法连接服务的统一中文提示（不向用户暴露英文底层错误）。 */
const NETWORK_ERROR_HINT = '无法连接服务，请检查网络或后端服务是否已启动';

/** 取 ApiError 的展示文案（message 优先，网络错误归一中文，兜底通用文案）。 */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 0 && NETWORK_ERROR_PATTERN.test(e.message)) {
      return NETWORK_ERROR_HINT;
    }
    return e.message || `请求失败(${e.status})`;
  }
  if (e instanceof Error && NETWORK_ERROR_PATTERN.test(e.message)) {
    return NETWORK_ERROR_HINT;
  }
  return e instanceof Error ? e.message : '操作失败';
}
