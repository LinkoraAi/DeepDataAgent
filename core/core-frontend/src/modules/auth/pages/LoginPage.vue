<template>
  <div class="login-page">
    <t-card class="login-card" :bordered="true">
      <h2 class="login-card__title">登录 DeepDataAgent</h2>
      <p class="login-card__hint">请使用账号邮箱与密码登录（未注册账号请联系管理员开通，或经 /api/auth/register 注册）。</p>
      <t-form :data="form" :rules="rules" layout="vertical" label-align="left" @submit="handleLogin">
        <t-form-item label="邮箱" name="email">
          <t-input v-model="form.email" placeholder="账号邮箱" :disabled="submitting" />
        </t-form-item>
        <t-form-item label="密码" name="password">
          <t-input v-model="form.password" type="password" placeholder="登录密码" :disabled="submitting" @enter="handleLogin" />
        </t-form-item>
        <t-button theme="primary" type="submit" block :loading="submitting">登录</t-button>
      </t-form>
      <p v-if="loginError" class="login-error">{{ loginError }}</p>
    </t-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { FormRule } from 'tdesign-vue-next';
import { useAuthStore } from '@/app/store/auth';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const form = reactive({ email: '', password: '' });
const submitting = ref(false);
const loginError = ref('');

const rules: Record<string, FormRule[]> = {
  email: [{ required: true, message: '邮箱不能为空' }],
  password: [{ required: true, message: '密码不能为空' }],
};

/** 解析回跳地址：仅允许同源绝对路径（拒协议相对 //），防开放重定向。 */
function resolveRedirect(): string {
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '';
  return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/agent';
}

async function handleLogin(): Promise<void> {
  if (!form.email.trim() || !form.password) {
    loginError.value = '邮箱与密码不能为空';
    return;
  }
  submitting.value = true;
  loginError.value = '';
  try {
    await authStore.login(form.email.trim(), form.password);
    await router.replace(resolveRedirect());
  } catch (e) {
    loginError.value = `登录失败: ${(e as Error).message}`;
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  padding: 64px 16px;
}

.login-card {
  width: 400px;
}

.login-card__title {
  margin: 0 0 8px;
  font-size: 20px;
}

.login-card__hint {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.login-error {
  margin: 12px 0 0;
  color: var(--td-error-color);
  font-size: 13px;
}
</style>
