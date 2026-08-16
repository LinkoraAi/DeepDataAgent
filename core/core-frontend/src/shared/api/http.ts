/**
 * 共享 HTTP 请求封装（REST + 统一响应解包）。
 * <p>对齐后端 {@code shared/result/ApiResponse}：业务失败（{@code success=false}）
 * 直接抛 Error；分页响应 {@code PaginatedResponse} 以 {@code list/total/page/size} 承载。</p>
 */

/** 统一响应包装（后端 shared/result/ApiResponse）。 */
export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
}

/** 分页响应（后端 runtime/controller/response/PaginatedResponse）。 */
export interface PaginatedResponse<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

/**
 * 请求并解包统一响应。
 * @param path 请求路径
 * @param init 可选的 RequestInit（method / headers / body 等）
 */
export async function fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`请求失败(${response.status}): ${text.slice(0, 200)}`);
  }
  const wrapper = (await response.json()) as ApiResponse<T>;
  if (!wrapper.success) {
    throw new Error(`${wrapper.code}: ${wrapper.message}`);
  }
  return wrapper.data;
}