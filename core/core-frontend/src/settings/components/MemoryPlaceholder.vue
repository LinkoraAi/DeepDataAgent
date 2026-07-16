<template>
  <div class="memory-placeholder">
    <div class="memory-placeholder__content">
      <div class="memory-placeholder__icon">
        <t-icon name="memory" size="64px" />
      </div>
      <h2 class="memory-placeholder__title">记忆管理</h2>
      <p class="memory-placeholder__description">即将推出，敬请期待</p>
      <div class="memory-placeholder__features">
        <div class="memory-placeholder__feature">
          <t-icon name="check-circle" size="20px" />
          <span>查看系统记忆列表</span>
        </div>
        <div class="memory-placeholder__feature">
          <t-icon name="check-circle" size="20px" />
          <span>搜索记忆内容</span>
        </div>
        <div class="memory-placeholder__feature">
          <t-icon name="check-circle" size="20px" />
          <span>查看记忆详情</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// No logic needed for placeholder
</script>

<style scoped lang="less">
.memory-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: 40px;

  &__content {
    text-align: center;
    max-width: 400px;
  }

  &__icon {
    margin-bottom: 24px;
    color: #0052d9;
    opacity: 0.6;
  }

  &__title {
    font-size: 24px;
    font-weight: 600;
    color: #0f172a;
    margin: 0 0 12px 0;
  }

  &__description {
    font-size: 14px;
    color: #64748b;
    margin: 0 0 32px 0;
  }

  &__features {
    display: flex;
    flex-direction: column;
    gap: 12px;
    text-align: left;
  }

  &__feature {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    color: #475569;

    .t-icon {
      color: #10b981;
    }
  }
}
</style>
