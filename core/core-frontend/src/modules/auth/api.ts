import { post } from '@/shared/api/http';

/**
 * 认证端点（后端 AuthController，前缀 /api/auth，非版本化、免 Bearer）。
 * 字段 camelCase；限流：注册 5 次/分/IP，登录 10 次/分/IP + 10 次/分/邮箱（429）。
 */

/** 登录响应（LoginResponse） */
export interface LoginResponse {
  token: string;
  expiresInSeconds: number;
  userId: number;
  email: string;
}

/** 用户信息（UserResponse） */
export interface UserResponse {
  userId: number;
  email: string;
  createdAt: string;
}

/** 登录（成功返回 JWT；密码错误 401、限流 429 由 http 层抛 ApiError） */
export function login(email: string, password: string): Promise<LoginResponse> {
  return post<LoginResponse>('/api/auth/login', { email, password });
}

/** 注册（密码 8~72 字符，后端校验） */
export function register(email: string, password: string): Promise<UserResponse> {
  return post<UserResponse>('/api/auth/register', { email, password });
}

/** 登出（JWT jti 进 Redis 撤销名单） */
export function logout(): Promise<void> {
  return post<void>('/api/auth/logout');
}
