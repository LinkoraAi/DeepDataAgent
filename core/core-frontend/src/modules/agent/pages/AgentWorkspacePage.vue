<template>
  <PageSection
    title="Agent 会话工作台"
    description="这里预留 AgentScope 会话编排、TDesign Chat 交互面板与 OpenSandbox 执行入口。"
  >
    <div class="page-grid">
      <t-card title="会话上下文">
        <p>后续将接入 Agent 会话、系统提示词、推理轨迹与工具调用状态。</p>
        <t-tag theme="primary" variant="light">AgentScope</t-tag>
      </t-card>
      <t-card title="聊天面板">
        <p>本模块已引入 TDesign Chat，后续在这里承接消息流、输入框与沙盒执行反馈。</p>
        <t-tag theme="success" variant="light">TDesign Chat</t-tag>
      </t-card>
      <t-card title="执行沙盒">
        <p>OpenSandbox 作为 Agent 基础设施能力接入，后续将支持命令执行、文件处理和代码解释。</p>
        <t-tag theme="warning" variant="light">OpenSandbox</t-tag>
      </t-card>
    </div>
  </PageSection>
</template>

<script setup lang="ts">
import PageSection from '@/shared/components/PageSection.vue';
</script>
