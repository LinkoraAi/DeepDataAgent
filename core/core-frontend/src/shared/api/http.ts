import { ApiError } from '@/shared/types/api';
import type { ApiResponse, ErrorEnvelope } from '@/shared/types/api';

/**
 * 统一 HTTP 客户端（全应用唯一出口）。
 *
 * 契约要点（对齐后端真实行为，勿凭直觉改动）：
 * - 成功：HTTP 200 + `ApiResponse{success,code,message,data}`，`request` 解包返回 data；
 *   删除类端点为 HTTP 200 + body `code="202"`（异步受理），需感知 code 的调用 `requestEnvelope`。
 * - 失败：真实 HTTP 状态码 + `ErrorEnvelope{error:{type,message},request_id}`，
 *   统一抛 {@link ApiError}；401（认证端点除外）清会话并整页跳 /login?redirect=。
 * - 鉴权：`Authorization: Bearer <token>`（localStorage 持久化）；204/空体安全返回 undefined。
 * - 路径约定：全部以 `/api` 开头（Vite 代理 /api → http://localhost:18080）。
 */

/** 认证会话 localStorage 键名（集中定义，auth store 与本模块共用）。 */
export const AUTH_STORAGE_KEYS = {
  token: 'deepdataagent.auth.token',
  user: 'deepdataagent.auth.user',
} as const;

/** 公开认证端点前缀：其 401（如密码错误）不得触发会话清理与跳转，避免登录页自跳转死循环。 */
const AUTH_ENDPOINT_PREFIX = '/api/auth/';

/** 读取当前 Bearer token（未登录为 null）。 */
export function getAuthToken(): string | null {
  return localStorage.getItem(AUTH_STORAGE_KEYS.token);
}

/** 写入登录会话（token + 用户信息 JSON 串）。 */
export function setAuthSession(token: string, userJson: string): void {
  localStorage.setItem(AUTH_STORAGE_KEYS.token, token);
  localStorage.setItem(AUTH_STORAGE_KEYS.user, userJson);
}

/** 清理本地会话。 */
export function clearAuthSession(): void {
  localStorage.removeItem(AUTH_STORAGE_KEYS.token);
  localStorage.removeItem(AUTH_STORAGE_KEYS.user);
}

/** 组装鉴权请求头（SSE/blob/上传等旁路场景同样复用）。 */
export function authHeaders(extra?: HeadersInit): Headers {
  const headers = new Headers(extra);
  const token = getAuthToken();
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return headers;
}

/** 401 统一处置：清会话并整页跳登录（整页跳转规避 http→router 循环依赖）。 */
function interceptUnauthorized(path: string, status: number): void {
  if (status !== 401 || path.startsWith(AUTH_ENDPOINT_PREFIX)) {
    return;
  }
  clearAuthSession();
  if (window.location.pathname === '/login') {
    return;
  }
  const current = `${window.location.pathname}${window.location.search}`;
  const query = current === '/' ? '' : `?redirect=${encodeURIComponent(current)}`;
  window.location.assign(`/login${query}`);
}

/** 非 2xx 响应 → ApiError（信封解析失败时回落状态码 + 原文截断；空响应体给完整可读话术，不留悬空冒号）。 */
async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '');
  try {
    const envelope = JSON.parse(text) as ErrorEnvelope;
    if (envelope?.error?.message) {
      return new ApiError(response.status, envelope.error.type ?? '', envelope.error.message, envelope.request_id ?? '');
    }
  } catch {
    // 非信封形态（网关纯文本错误等）走回落
  }
  const trimmed = text.trim();
  return new ApiError(
    response.status,
    '',
    trimmed
      ? `请求失败（${response.status}）：${trimmed.slice(0, 200)}`
      : `服务暂时不可用（${response.status}），请稍后重试`,
  );
}

/** 常规请求默认超时（毫秒）：后端不可用时避免 Promise 永不 settle、loading 永久转圈。 */
export const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

/** 二进制读取/下载默认超时（毫秒）：下载类放宽，但仍保证超时后必然结束。 */
export const DEFAULT_BLOB_TIMEOUT_MS = 120_000;

/** 上传默认超时（毫秒）：大文件上传放宽，避免长耗时上传被误判失败。 */
export const DEFAULT_UPLOAD_TIMEOUT_MS = 300_000;

/** 超时类错误的 ApiError.type 标识：用于与网络错误区分，便于用户理解与排障。 */
export const TIMEOUT_ERROR_TYPE = 'timeout_error';

/**
 * 构造超时 {@link ApiError}（status=0，type=timeout_error）。
 * @param timeoutMs 触发的超时毫秒数
 * @param action 动作文案（如"请求"/"文件下载"），拼入提示便于定位
 */
export function createTimeoutError(timeoutMs: number, action = '请求'): ApiError {
  const seconds = Math.round(timeoutMs / 1000);
  return new ApiError(0, TIMEOUT_ERROR_TYPE, `${action}超时（${seconds}s），请检查后端服务后重试`);
}

/**
 * 组装带超时的 AbortSignal（http/blob/upload 复用同一超时语义）。
 * <p>合并调用方 signal 的取消语义；超时或外部取消后 abort。返回的 isTimedOut 用于把
 * AbortError 归因为"超时"而非普通网络错误；请求结束后务必调用 cleanup 释放定时器。</p>
 * @param init 原始请求配置（取其 signal 透传取消语义）
 * @param timeoutMs 超时毫秒数；≤0 表示不启用超时
 */
export function withTimeoutSignal(
  init: RequestInit | undefined,
  timeoutMs: number,
): { signal: AbortSignal; isTimedOut: () => boolean; cleanup: () => void } {
  const controller = new AbortController();
  const callerSignal = init?.signal ?? undefined;
  if (callerSignal) {
    if (callerSignal.aborted) {
      controller.abort(callerSignal.reason);
    } else {
      callerSignal.addEventListener('abort', () => controller.abort(callerSignal.reason), { once: true });
    }
  }
  let timedOut = false;
  const timer = timeoutMs > 0 ? setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs) : undefined;
  return {
    signal: controller.signal,
    isTimedOut: () => timedOut,
    cleanup: () => {
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    },
  };
}

/**
 * 请求基座：注入 Bearer、字符串 body 补 JSON Content-Type、401 拦截、超时中断。
 * <p>超时（或外部取消）通过 AbortController 中断 fetch，保证 Promise 必然 settle，
 * 使调用方 useAsync 的 finally 能执行、loading 归位。</p>
 * @param timeoutMs 超时毫秒数，默认 {@link DEFAULT_REQUEST_TIMEOUT_MS}，≤0 关闭超时
 */
async function rawFetch(path: string, init?: RequestInit, timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS): Promise<Response> {
  const headers = authHeaders(init?.headers);
  if (typeof init?.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const timeout = withTimeoutSignal(init, timeoutMs);
  try {
    const response = await fetch(path, { ...init, headers, signal: timeout.signal });
    interceptUnauthorized(path, response.status);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return response;
  } catch (error) {
    // 仅把"超时触发的 abort"归一为超时错误；外部取消/网络错误原样抛出，不吞原始错误
    if (timeout.isTimedOut()) {
      throw createTimeoutError(timeoutMs);
    }
    throw error;
  } finally {
    timeout.cleanup();
  }
}

/**
 * 请求并返回完整 ApiResponse 包装（需要感知 code="202" 异步受理的调用方使用）。
 * @param timeoutMs 超时毫秒数（默认 30s，传 0 关闭超时）
 */
export async function requestEnvelope<T>(
  path: string,
  init?: RequestInit,
  timeoutMs?: number,
): Promise<ApiResponse<T>> {
  const response = await rawFetch(path, init, timeoutMs);
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return { success: true, code: '204', message: '', data: undefined as T };
  }
  return (await response.json()) as ApiResponse<T>;
}

/**
 * 请求并解包业务数据（绝大多数调用使用本函数）。
 * @param timeoutMs 超时毫秒数（默认 30s，传 0 关闭超时）
 */
export async function request<T>(path: string, init?: RequestInit, timeoutMs?: number): Promise<T> {
  const wrapper = await requestEnvelope<T>(path, init, timeoutMs);
  return wrapper.data;
}

/** GET 便捷方法（timeoutMs 默认 30s，可覆盖或传 0 关闭） */
export function get<T>(path: string, init?: RequestInit, timeoutMs?: number): Promise<T> {
  return request<T>(path, { ...init, method: 'GET' }, timeoutMs);
}

/** POST JSON 便捷方法（timeoutMs 默认 30s） */
export function post<T>(path: string, body?: unknown, timeoutMs?: number): Promise<T> {
  return request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }, timeoutMs);
}

/** PUT JSON 便捷方法（timeoutMs 默认 30s） */
export function put<T>(path: string, body?: unknown, timeoutMs?: number): Promise<T> {
  return request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }, timeoutMs);
}

/** DELETE 便捷方法（timeoutMs 默认 30s） */
export function del<T>(path: string, timeoutMs?: number): Promise<T> {
  return request<T>(path, { method: 'DELETE' }, timeoutMs);
}

/** 拼查询串（跳过 undefined/null/空串；布尔与数字转字符串）。 */
export function buildQuery(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}
