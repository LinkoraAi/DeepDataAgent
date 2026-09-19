/**
 * 认证接口模块（对齐后端 auth BC AuthController：/api/auth）。
 * <p>register / login 为公开端点（后端 JWT 过滤器精确白名单），logout 走撤销黑名单
 * （携带 Authorization 头，token 已失效时后端幂等空操作）；后端无 /me 端点，
 * 当前用户以登录响应内联的 userId / email 为准。响应字段对齐
 * LoginResponse（token / expiresInSeconds / userId / email）与 UserResponse（扁平结构，无嵌套 user）。</p>
 */
import { fetchJson } from '@/shared/api/http';

/** 登录响应（对齐后端 LoginResponse）。 */
export interface LoginResultDto {
  token: string;
  /** token 有效期秒数（前端仅持久展示，不做本地过期判定，过期由 401 统一处置兜底）。 */
  expiresInSeconds: number;
  userId: number;
  email: string;
}

/** 用户信息（对齐后端 UserResponse，注册回显用）。 */
export interface AuthUserDto {
  userId: number;
  email: string;
  createdAt: string;
}

/** 登录 / 注册提交凭证（后端字段名即 email / password）。 */
export interface AuthCredential {
  email: string;
  password: string;
}

/** 登录（成功返回 JWT；凭证错误 401 authentication_error，限流 429）。 */
export function login(credential: AuthCredential): Promise<LoginResultDto> {
  return fetchJson<LoginResultDto>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(credential),
  });
}

/** 注册（密码 8-72 位；成功仅回显用户信息，不自动登录）。 */
export function register(credential: AuthCredential): Promise<AuthUserDto> {
  return fetchJson<AuthUserDto>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(credential),
  });
}

/** 登出（当前 token 加入撤销黑名单；本地会话清理由 auth store 负责）。 */
export async function logout(): Promise<void> {
  await fetchJson<void>('/api/auth/logout', { method: 'POST' });
}
