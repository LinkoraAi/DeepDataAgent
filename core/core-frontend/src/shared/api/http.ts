/**
 * 共享 HTTP 请求封装（REST + 统一响应解包 + Bearer 鉴权）。
 * <p>成功响应对齐后端 {@code shared/result/ApiResponse}（{@code 2xx} + {@code data} 解包）；
 * 错误响应自 6.1 起走统一错误信封（shared/api-conventions）：HTTP 状态码语义化
 * （400/401/403/404/409/429/500），body 为
 * {@code {"error":{"type","message"},"request_id":"...","type":"error"}}，
 * 非 2xx 一律抛 Error（消息携带 error.type 供机读判别）。</p>
 * <p>认证：所有请求经 {@link authHeaders} 注入 {@code Authorization: Bearer <token>}
 * （token 由 auth store 登录后写入 localStorage，键名集中于 {@link AUTH_STORAGE_KEYS}）；
 * 非认证端点收到 401（authentication_error）时清理本地会话并整页跳转
 * {@code /login?redirect=<当前路径>}（整页跳转以规避 http → router 的循环依赖，
 * 同时借应用重建保证内存会话态归零）。</p>
 */

/** 统一响应包装（后端 shared/result/ApiResponse，成功侧）。 */
export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
}

/** 统一错误信封（后端 shared/result/ErrorEnvelope，非 2xx 响应体）。 */
export interface ErrorEnvelope {
  error: { type: string; message: string };
  request_id: string;
  type: 'error';
}

/** 分页响应（后端 runtime/controller/response/PaginatedResponse，既有 page/size 形态，随 6.x 分批迁移游标）。 */
export interface PaginatedResponse<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

/**
 * Cursor 游标分页响应（后端 shared/result/CursorPage，6.1 统一约定）。
 * <p>列表按创建时间降序、不提供 total；翻页以当页 first_id/last_id 作 before_id/after_id 参数。</p>
 */
export interface CursorPageResponse<T> {
  data: T[];
  first_id: string | null;
  last_id: string | null;
  has_more: boolean;
  next_page?: string;
}

// ==================== 会话存储（键名集中定义，auth store 与本文件共用） ====================

/** 认证会话 localStorage 键名（集中定义防散落字面量漂移）。 */
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

/** 清理本地会话（token + 用户信息缓存）。 */
export function clearAuthSession(): void {
  localStorage.removeItem(AUTH_STORAGE_KEYS.token);
  localStorage.removeItem(AUTH_STORAGE_KEYS.user);
}

/**
 * 组装携带鉴权信息的请求头（fetchJson 内部与所有裸 fetch 调用方统一使用）。
 * @param extra 附加请求头（可空；同名键以调用方显式声明优先）
 */
export function authHeaders(extra?: HeadersInit): Headers {
  const headers = new Headers(extra);
  const token = getAuthToken();
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return headers;
}

/**
 * 401 统一处置：认证端点之外的未认证响应 → 清理会话并跳转登录页（携 redirect 回跳地址）。
 * @returns 是否已处置（调用方仍可照常抛错，跳转期间错误提示无害）
 */
export function interceptUnauthorized(path: string, status: number): boolean {
  if (status !== 401 || path.startsWith(AUTH_ENDPOINT_PREFIX)) {
    return false;
  }
  clearAuthSession();
  if (window.location.pathname === '/login') {
    return true;
  }
  const current = `${window.location.pathname}${window.location.search}`;
  const query = current === '/' ? '' : `?redirect=${encodeURIComponent(current)}`;
  window.location.assign(`/login${query}`);
  return true;
}

// ==================== fetch 封装 ====================

/** 请求头缺省装配：注入 Bearer token；body 为字符串且未显式声明 Content-Type 时统一补 application/json。 */
function withDefaultInit(init?: RequestInit): RequestInit {
  const headers = authHeaders(init?.headers);
  if (typeof init?.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return { ...init, headers };
}

/**
 * 请求并解包统一响应。
 * @param path 请求路径
 * @param init 可选的 RequestInit（method / headers / body 等；Authorization 自动注入）
 */
export async function fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, withDefaultInit(init));
  interceptUnauthorized(path, response.status);
  if (!response.ok) {
    // 统一错误信封：解析 {error:{type,message}}；解析失败回落状态码 + 原文截断
    const text = await response.text().catch(() => '');
    let message = `请求失败(${response.status}): ${text.slice(0, 200)}`;
    try {
      const envelope = JSON.parse(text) as ErrorEnvelope;
      if (envelope?.error?.message) {
        message = `${envelope.error.type}: ${envelope.error.message}`;
      }
    } catch {
      // 非信封形态（如网关纯文本错误）保留回落消息
    }
    throw new Error(message);
  }
  // 204 / 空响应体：无信封可解包，void 泛型安全返回 undefined（不触发 json 解析异常）
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const wrapper = (await response.json()) as ApiResponse<T>;
  return wrapper.data;
}
