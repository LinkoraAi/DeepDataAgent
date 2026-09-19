/**
 * 认证会话 store（Pinia，setup 风格）。
 * <p>登录成功持久化 token + 用户信息（localStorage，键名集中于 shared/api/http 的
 * AUTH_STORAGE_KEYS，刷新后可恢复会话）；登出先尽力调用后端撤销接口（失败不阻塞本地清理），
 * 再清空内存与会话存储。401 的被动清理由 shared/api/http 拦截器完成（整页跳转重建应用归零状态）。</p>
 */
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import {
  AUTH_STORAGE_KEYS,
  clearAuthSession,
  getAuthToken,
} from '@/shared/api/http';
import {
  login as loginApi,
  logout as logoutApi,
  register as registerApi,
  type AuthUserDto,
  type LoginResultDto,
} from '@/shared/api/auth';

/** 会话内当前用户（登录响应内联字段，无 /me 端点）。 */
export interface AuthUser {
  userId: number;
  email: string;
}

/** 读取持久化的用户信息（结构漂移 / 损坏时按未登录兜底）。 */
function readStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(AUTH_STORAGE_KEYS.user);
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed === 'object' && parsed !== null) {
      const candidate = parsed as Record<string, unknown>;
      if (typeof candidate.userId === 'number' && typeof candidate.email === 'string') {
        return { userId: candidate.userId, email: candidate.email };
      }
    }
    return null;
  } catch {
    return null;
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getAuthToken() ?? '');
  const user = ref<AuthUser | null>(readStoredUser());

  const isAuthenticated = computed(() => token.value.length > 0);

  /** 写入内存并同步持久化（空 token 视为登出，清理全部会话键）。 */
  function persist(nextToken: string, nextUser: AuthUser | null): void {
    token.value = nextToken;
    user.value = nextUser;
    if (!nextToken) {
      clearAuthSession();
      return;
    }
    localStorage.setItem(AUTH_STORAGE_KEYS.token, nextToken);
    if (nextUser) {
      localStorage.setItem(AUTH_STORAGE_KEYS.user, JSON.stringify(nextUser));
    } else {
      localStorage.removeItem(AUTH_STORAGE_KEYS.user);
    }
  }

  /** 登录：拉取 JWT 并持久化会话（失败抛错由调用方回显）。 */
  async function login(email: string, password: string): Promise<void> {
    const result: LoginResultDto = await loginApi({ email, password });
    persist(result.token, { userId: result.userId, email: result.email });
  }

  /** 注册（仅开通账号，不自动登录）。 */
  async function register(email: string, password: string): Promise<AuthUserDto> {
    return registerApi({ email, password });
  }

  /** 登出：尽力撤销服务端 token（已失效 / 网络异常不阻塞），本地会话必清。 */
  async function logout(): Promise<void> {
    if (token.value) {
      try {
        await logoutApi();
      } catch {
        // 撤销失败静默：token 可能已过期，本地登出语义不受影响
      }
    }
    persist('', null);
  }

  return { token, user, isAuthenticated, login, register, logout };
});
