import { defineStore } from 'pinia';
import { ref } from 'vue';
import { AUTH_STORAGE_KEYS, clearAuthSession, setAuthSession } from '@/shared/api/http';
import * as authApi from '@/modules/auth/api';

/** 本地缓存的用户信息（登录响应或注册结果）。 */
export interface AuthUser {
  userId: number;
  email: string;
}

function readStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(AUTH_STORAGE_KEYS.user);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as AuthUser;
  } catch {
    return null;
  }
}

/**
 * 认证会话 store：token 与用户信息以 localStorage 持久化
 * （键名集中在 shared/api/http，供 http 层直读避免循环依赖）。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(AUTH_STORAGE_KEYS.token));
  const user = ref<AuthUser | null>(readStoredUser());

  const isAuthenticated = (): boolean => Boolean(token.value);

  async function login(email: string, password: string): Promise<void> {
    const result = await authApi.login(email, password);
    token.value = result.token;
    user.value = { userId: result.userId, email: result.email };
    setAuthSession(result.token, JSON.stringify(user.value));
  }

  async function register(email: string, password: string): Promise<void> {
    await authApi.register(email, password);
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      // 撤销失败也要清本地会话（token 可能已过期）
      token.value = null;
      user.value = null;
      clearAuthSession();
    }
  }

  return { token, user, isAuthenticated, login, register, logout };
});
