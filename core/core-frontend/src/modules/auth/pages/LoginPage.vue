<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { MessagePlugin } from 'tdesign-vue-next';
import { useAuthStore } from '@/app/store/auth';
import { errorMessage } from '@/shared/composables/useAsync';

/**
 * 登录/注册页（公开路由）。
 * 后端约束：密码 8~72 字符；注册 5 次/分/IP、登录 10 次/分/IP+邮箱（429 直显原因）。
 */
const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const mode = ref<'login' | 'register'>('login');
const loading = ref(false);
const form = reactive({ email: '', password: '' });
const formRef = ref();

const rules = {
  email: [
    { required: true, message: '请输入邮箱', type: 'error' as const },
    { email: true, message: '邮箱格式不正确', type: 'error' as const },
  ],
  password: [
    { required: true, message: '请输入密码', type: 'error' as const },
    { min: 8, message: '密码至少 8 个字符', type: 'error' as const },
    { max: 72, message: '密码最多 72 个字符', type: 'error' as const },
  ],
};

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate?.();
  if (valid !== true) {
    return;
  }
  loading.value = true;
  try {
    if (mode.value === 'register') {
      await authStore.register(form.email, form.password);
      MessagePlugin.success('注册成功，请登录');
      mode.value = 'login';
      return;
    }
    await authStore.login(form.email, form.password);
    const redirect = (route.query.redirect as string) || '/kb';
    router.replace(redirect);
  } catch (e) {
    MessagePlugin.error(errorMessage(e));
  } finally {
    loading.value = false;
  }
}

function switchMode(): void {
  mode.value = mode.value === 'login' ? 'register' : 'login';
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌介绍区：窄屏（<= 900px）自动隐藏 -->
    <aside class="login-brand">
      <div class="login-brand__glow login-brand__glow--a"></div>
      <div class="login-brand__glow login-brand__glow--b"></div>
      <div class="login-brand__inner">
        <div class="login-brand__logo">
          <span class="login-brand__logo-mark">D</span>
          <span class="login-brand__logo-text">DeepDataAgent</span>
        </div>

        <div class="login-brand__hero">
          <span class="login-brand__badge">
            <i class="login-brand__badge-dot"></i>企业级数据智能体平台
          </span>
          <h2 class="login-brand__headline">让企业数据，<span class="login-brand__headline-keep">可问、可信、可用</span></h2>
          <p class="login-brand__subtitle">
            统一纳管知识库 / 数据源 / 智能体 / 会话，让数据问答开箱即用，答案全程可溯源。
          </p>
          <ul class="login-brand__points">
            <li class="login-brand__point">
              <span class="login-brand__point-icon"><t-icon name="layers" /></span>
              <span>统一纳管知识库与数据源，沉淀企业数据资产</span>
            </li>
            <li class="login-brand__point">
              <span class="login-brand__point-icon"><t-icon name="app" /></span>
              <span>可视化编排智能体，让数据问答开箱即用</span>
            </li>
            <li class="login-brand__point">
              <span class="login-brand__point-icon"><t-icon name="chat-bubble" /></span>
              <span>多轮会话与引用溯源，答案可信可查</span>
            </li>
          </ul>
        </div>

        <p class="login-brand__footer">© 2026 DeepDataAgent · 数据智能体平台</p>
      </div>
    </aside>

    <!-- 右侧登录 / 注册表单卡 -->
    <section class="login-panel">
      <t-card class="login-card" :bordered="false">
        <!-- 窄屏品牌区隐藏后，卡片内补一个紧凑 Logo -->
        <div class="login-card__brand">
          <span class="login-card__brand-mark">D</span>
          <span class="login-card__brand-text">DeepDataAgent</span>
        </div>
        <div class="login-card__header">
          <h1 class="login-card__title">{{ mode === 'login' ? '登录' : '注册' }}</h1>
          <p class="login-card__desc">
            {{ mode === 'login' ? '欢迎回来，请登录你的 DeepDataAgent 账号' : '创建账号，开启你的数据智能体之旅' }}
          </p>
        </div>
        <t-form
          ref="formRef"
          class="auth-form"
          :data="form"
          :rules="rules"
          label-width="0"
          @submit="submit"
        >
          <t-form-item name="email" class="auth-field">
            <t-input
              v-model="form.email"
              name="email"
              autocomplete="email"
              placeholder="邮箱"
              size="large"
              clearable
            >
              <template #prefix-icon>
                <t-icon name="mail" />
              </template>
            </t-input>
          </t-form-item>
          <t-form-item name="password" class="auth-field">
            <t-input
              v-model="form.password"
              name="password"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              type="password"
              placeholder="密码（8~72 位）"
              size="large"
            >
              <template #prefix-icon>
                <t-icon name="lock-on" />
              </template>
            </t-input>
          </t-form-item>
          <div class="auth-submit">
            <t-button theme="primary" size="large" block type="submit" :loading="loading">
              {{ mode === 'login' ? '登 录' : '注 册' }}
            </t-button>
          </div>
        </t-form>
        <div class="login-switch">
          <t-link theme="primary" @click="switchMode">
            {{ mode === 'login' ? '没有账号？立即注册' : '已有账号？返回登录' }}
          </t-link>
        </div>
      </t-card>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--td-bg-color-page);
}

/* --------------------------- 左侧品牌区 --------------------------- */
.login-brand {
  position: relative;
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  padding: 56px 64px;
  overflow: hidden;
  background: linear-gradient(
    155deg,
    var(--td-brand-color-1) 0%,
    var(--td-brand-color-2) 52%,
    var(--td-bg-color-page) 100%
  );
}

/* 低对比度点阵纹理：向边缘渐隐，不抢内容焦点 */
.login-brand::before {
  position: absolute;
  inset: 0;
  content: '';
  pointer-events: none;
  background-image: radial-gradient(rgba(var(--td-brand-rgb), 0.16) 1.2px, transparent 1.2px);
  background-size: 24px 24px;
  -webkit-mask-image: radial-gradient(110% 90% at 35% 42%, #000 8%, transparent 72%);
  mask-image: radial-gradient(110% 90% at 35% 42%, #000 8%, transparent 72%);
}

/* 静态柔光斑块（无动画，仅作背景层次） */
.login-brand__glow {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(36px);
}

.login-brand__glow--a {
  top: -170px;
  right: -130px;
  width: 480px;
  height: 480px;
  background: radial-gradient(circle, rgba(var(--td-brand-rgb-5), 0.38), transparent 66%);
}

.login-brand__glow--b {
  bottom: -190px;
  left: -130px;
  width: 420px;
  height: 420px;
  background: radial-gradient(circle, rgba(var(--td-brand-rgb-4), 0.38), transparent 66%);
}

.login-brand__inner {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 500px;
  margin: auto;
  animation: auth-rise 0.5s cubic-bezier(0.22, 0.61, 0.36, 1) both;
}

.login-brand__logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-brand__logo-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  font-size: 23px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--td-brand-color-6), var(--td-brand-color-8));
  border-radius: var(--td-radius-large);
  box-shadow: 0 8px 18px -6px rgba(var(--td-brand-rgb), 0.55);
}

.login-brand__logo-text {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: var(--td-text-color-primary);
}

.login-brand__hero {
  margin-top: 56px;
}

.login-brand__badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 13px;
  font-size: 12px;
  font-weight: 500;
  color: var(--td-brand-color-9);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(var(--td-brand-rgb), 0.18);
  border-radius: var(--td-radius-round);
}

.login-brand__badge-dot {
  width: 6px;
  height: 6px;
  background: var(--td-brand-color);
  border-radius: 50%;
}

.login-brand__headline {
  margin: 20px 0 14px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0.5px;
  color: var(--td-text-color-primary);
}

/* 中文可在任意字间断行：锁住标语短语，保证换行只发生在逗号处 */
.login-brand__headline-keep {
  white-space: nowrap;
}

.login-brand__subtitle {
  max-width: 440px;
  margin: 0 0 34px;
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--td-text-color-secondary);
}

.login-brand__points {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.login-brand__point {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--td-text-color-secondary);
}

.login-brand__point-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  color: var(--td-brand-color);
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(var(--td-brand-rgb), 0.14);
  border-radius: 12px;
  box-shadow: var(--td-shadow-1);
}

.login-brand__point-icon :deep(.t-icon) {
  font-size: 20px;
}

.login-brand__footer {
  margin: 56px 0 0;
  font-size: 12.5px;
  color: var(--td-text-color-placeholder);
}

/* --------------------------- 右侧表单区 --------------------------- */
.login-panel {
  position: relative;
  display: flex;
  flex: 0 0 520px;
  align-items: center;
  justify-content: center;
  padding: 40px;
  overflow-y: auto;
  background: var(--td-bg-color-page);
}

/* 卡片背后的柔和品牌光晕 */
.login-panel::before {
  position: absolute;
  inset: 0;
  content: '';
  pointer-events: none;
  background: radial-gradient(520px 380px at 50% 28%, rgba(var(--td-brand-rgb), 0.07), transparent 70%);
}

.login-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-border-level-1-color);
  border-radius: var(--td-radius-extraLarge);
  box-shadow:
    0 2px 4px rgba(var(--dda-shadow-rgb), 0.03),
    0 22px 48px -14px rgba(var(--dda-shadow-rgb), 0.14);
  animation: auth-rise 0.45s cubic-bezier(0.22, 0.61, 0.36, 1) both;
}

.login-card :deep(.t-card__body) {
  padding: 40px 38px 30px;
}

/* 窄屏卡片内紧凑 Logo（桌面端隐藏） */
.login-card__brand {
  display: none;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
  font-size: 17px;
  font-weight: 700;
  color: var(--td-text-color-primary);
}

.login-card__brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--td-brand-color-6), var(--td-brand-color-8));
  border-radius: 10px;
}

.login-card__header {
  margin-bottom: 26px;
}

.login-card__title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: var(--td-text-color-primary);
}

.login-card__desc {
  margin: 0;
  font-size: 13.5px;
  color: var(--td-text-color-secondary);
}

/* --------------------------- 表单控件 --------------------------- */
.auth-form :deep(.t-form__item) {
  margin-bottom: 18px;
}

.auth-form :deep(.t-form__item:last-of-type) {
  margin-bottom: 0;
}

/* 输入框：48px 高、12px 圆角，悬停加深描边，聚焦带品牌色光环 */
.login-page .auth-field :deep(.t-input) {
  height: 48px;
  font-size: 15px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-border-level-2-color);
  border-radius: 12px;
  box-shadow: none;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.login-page .auth-field :deep(.t-input:hover) {
  border-color: var(--td-brand-color-4);
}

.login-page .auth-field :deep(.t-input):focus-within {
  border-color: var(--td-brand-color);
  box-shadow: 0 0 0 3px rgba(var(--td-brand-rgb), 0.14);
}

.login-page .auth-field :deep(.t-input__inner) {
  height: 46px;
  font-size: 15px;
}

.login-page .auth-field :deep(.t-input__prefix) {
  margin-right: 2px;
  color: var(--td-text-color-placeholder);
  transition: color 0.18s ease;
}

.login-page .auth-field :deep(.t-input__prefix .t-icon) {
  font-size: 18px;
}

.login-page .auth-field :deep(.t-input):focus-within .t-input__prefix {
  color: var(--td-brand-color);
}

/* 主按钮：48px 高、品牌色投影、悬停轻抬、按下回弹 */
.auth-submit {
  margin-top: 10px;
}

.login-page .auth-submit :deep(.t-button) {
  height: 48px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  background: linear-gradient(180deg, var(--td-brand-color-6) 0%, var(--td-brand-color) 100%);
  border: none;
  border-radius: 12px;
  box-shadow: 0 10px 22px -8px rgba(var(--td-brand-rgb), 0.55);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    background 0.18s ease;
}

.login-page .auth-submit :deep(.t-button:hover) {
  background: linear-gradient(180deg, var(--td-brand-color-5) 0%, var(--td-brand-color-6) 100%);
  box-shadow: 0 14px 28px -8px rgba(var(--td-brand-rgb), 0.6);
  transform: translateY(-1px);
}

.login-page .auth-submit :deep(.t-button:active) {
  background: linear-gradient(180deg, var(--td-brand-color) 0%, var(--td-brand-color-8) 100%);
  box-shadow: 0 6px 14px -6px rgba(var(--td-brand-rgb), 0.5);
  transform: translateY(0);
}

.login-switch {
  margin-top: 22px;
  font-size: 13.5px;
  text-align: center;
}

/* 入场动效（全局 prefers-reduced-motion 已兜底关闭） */
@keyframes auth-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* --------------------------- 窄屏适配 --------------------------- */
@media (max-width: 1100px) {
  .login-brand {
    padding: 48px;
  }

  .login-brand__headline {
    font-size: 30px;
  }
}

@media (max-width: 900px) {
  .login-brand {
    display: none;
  }

  .login-panel {
    flex: 1 1 auto;
    padding: 24px;
  }

  .login-card__brand {
    display: flex;
  }
}

@media (max-width: 480px) {
  .login-panel {
    padding: 16px;
  }

  .login-card :deep(.t-card__body) {
    padding: 30px 22px 24px;
  }
}
</style>
