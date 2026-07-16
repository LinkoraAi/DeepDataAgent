<template>
  <div class="welcome-screen">
    <div class="welcome-screen__header">
      <div class="welcome-screen__logo">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2L2 7L12 12L22 7L12 2Z" fill="#0052D9" opacity="0.8"/>
          <path d="M2 17L12 22L22 17" stroke="#0052D9" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2 12L12 17L22 12" stroke="#0052D9" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <h1 class="welcome-screen__title">Deep Data Agent</h1>
      <p class="welcome-screen__subtitle">AI驱动的数据分析新方式，让数据主动说话、自动协作。</p>
    </div>

  </div>
</template>

<script setup lang="ts">
</script>

<style scoped lang="less">
.welcome-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px 24px 24px;

  &__header {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  &__logo {
    width: 48px;
    height: 48px;
    margin-bottom: 16px;

    svg {
      width: 100%;
      height: 100%;
    }
  }

  &__title {
    font-size: 32px;
    font-weight: 700;
    color: #0f172a;
    margin: 0 0 12px;
    letter-spacing: -0.5px;
  }

  &__subtitle {
    font-size: 15px;
    color: #64748b;
    margin: 0;
    line-height: 1.6;
  }

}
</style>
