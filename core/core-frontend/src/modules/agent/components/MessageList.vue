<template>
  <div ref="messageListRef" class="message-list" @scroll="handleScroll">
    <!-- 消息列表（按轮次分组：问题+回复配对显示） -->
    <div class="message-list__content">
      <div v-for="(round, index) in groupedMessages" :key="`round-${index}`" class="message-round">
        <!-- 用户提问（始终显示） -->
        <UserMessage :message="round.userMessage" />
        
        <!-- AI 回复（如果有完成的回复或正在分析中的回复） -->
        <AgentMessage
          v-if="round.agentMessage"
          :message="round.agentMessage"
          :is-analyzing="round.isAnalyzing"
          @retry="handleRetry"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue';
import { useSessionStore } from '../stores/session';
import { useAnalysisStore } from '../stores/analysis';
import type { ChatMessage } from '../types';
import UserMessage from './UserMessage.vue';
import AgentMessage from './AgentMessage.vue';

const emit = defineEmits<{
  (e: 'retry'): void;
}>();

const sessionStore = useSessionStore();
const analysisStore = useAnalysisStore();
const messageListRef = ref<HTMLElement>();
const shouldAutoScroll = ref(true);

/**
 * 是否应该显示当前分析中的消息
 * <p>只有当分析属于当前会话时才显示，防止不同会话的内容混合显示。</p>
 */
const shouldShowAnalyzingMessage = computed(() => {
  if (!analysisStore.state.isAnalyzing) return false;
  if (!analysisStore.analysisSessionId) return false;
  if (!sessionStore.currentSessionId) return false;
  return analysisStore.analysisSessionId === sessionStore.currentSessionId;
});

/**
 * 当前分析中的实时消息（用于显示正在进行的分析）
 */
const currentAnalysisMessage = computed<ChatMessage | null>(() => {
  if (!shouldShowAnalyzingMessage.value) return null;

  return {
    id: 'current-analysis',
    role: 'agent',
    content: '',
    timestamp: Date.now(),
    analysisState: {
      rounds: analysisStore.state.rounds.map(round => ({
        ...round,
        thinking: { ...round.thinking },
        toolCalls: round.toolCalls.map(t => ({ ...t })),
      })),
      isTimelineExpanded: analysisStore.state.isTimelineExpanded,
      searchResults: [...(analysisStore.state.searchResults || [])],
      currentSQL: analysisStore.state.currentSQL,
      queryData: [...analysisStore.state.queryData],
      chartConfig: analysisStore.state.chartConfig,
      chartType: analysisStore.state.chartType,
      analysisReport: analysisStore.state.analysisReport,
      isEmptyResult: analysisStore.state.isEmptyResult,
      errorMessage: analysisStore.state.errorMessage,
      analysisStartTime: analysisStore.state.analysisStartTime,
      analysisEndTime: null,
      suggestions: [...analysisStore.state.suggestions],
    },
  };
});

/**
 * 消息轮次类型
 */
interface MessageRound {
  /** 用户提问消息 */
  userMessage: ChatMessage;
  /** AI 回复消息（可能为 null，表示正在分析中） */
  agentMessage: ChatMessage | null;
  /** 是否正在分析中 */
  isAnalyzing: boolean;
}

/**
 * 将扁平的聊天消息列表按"用户提问 → AI 回复"配对分组
 * <p>实现"一问一答"的展示结构，每个用户问题独立对应一个回复。</p>
 * <p>最后一轮可能是：已完成的 AI 回复，或正在分析中的实时消息。</p>
 */
const groupedMessages = computed<MessageRound[]>(() => {
  const messages = [...sessionStore.chatMessages];
  const rounds: MessageRound[] = [];
  
  // 步骤1：将历史消息按 user→agent 配对分组
  let i = 0;
  while (i < messages.length) {
    const msg = messages[i];
    
    if (msg.role === 'user') {
      // 找到用户消息，向后查找对应的 agent 回复
      let agentMsg: ChatMessage | null = null;
      let j = i + 1;
      
      // 向后查找，直到遇到下一个 user 消息或列表结束
      while (j < messages.length && messages[j].role !== 'user') {
        if (messages[j].role === 'agent') {
          agentMsg = messages[j];
          break; // 取第一个 agent 回复
        }
        j++;
      }
      
      rounds.push({
        userMessage: msg,
        agentMessage: agentMsg,
        isAnalyzing: false,
      });
      
      // 跳过已处理的消息
      i = agentMsg ? messages.indexOf(agentMsg) + 1 : i + 1;
    } else {
      // 跳过非用户消息（理论上不应该单独出现）
      i++;
    }
  }
  
  // 步骤2：检查是否有正在分析中的实时消息需要追加
  if (shouldShowAnalyzingMessage.value && currentAnalysisMessage.value) {
    const analysisMsg = currentAnalysisMessage.value;
    
    // 检查最后一轮是否已经有 agent 回复
    if (rounds.length > 0 && !rounds[rounds.length - 1].agentMessage) {
      // 最后一轮没有 agent 回复，更新为正在分析中
      rounds[rounds.length - 1].agentMessage = analysisMsg;
      rounds[rounds.length - 1].isAnalyzing = true;
    } else {
      // 创建新的一轮（用户消息还未添加到 chatMessages 时的边界情况）
      // 查找最后一个用户消息作为这一轮的问题
      const lastUserMsg = [...messages].reverse().find(m => m.role === 'user');
      if (lastUserMsg && !rounds.find(r => r.userMessage.id === lastUserMsg.id)) {
        rounds.push({
          userMessage: lastUserMsg,
          agentMessage: analysisMsg,
          isAnalyzing: true,
        });
      }
    }
  }
  
  return rounds;
});

/** 顶部触发加载更早轮次的滚动阈值（px） */
const SCROLL_TOP_THRESHOLD = 80;

/** 滚动事件处理 */
function handleScroll() {
  if (!messageListRef.value) return;
  const { scrollTop, scrollHeight, clientHeight } = messageListRef.value;
  shouldAutoScroll.value = scrollHeight - scrollTop - clientHeight < 50;

  // 滚动到顶部附近时，加载更早的轮次（store 内部有防重入与 hasMore 判断）
  if (scrollTop < SCROLL_TOP_THRESHOLD) {
    void loadOlderRoundsWithAnchor();
  }
}

/**
 * 加载更早轮次并保持滚动位置
 * <p>前置插入旧消息会使内容顶部增高，需要同步下移 scrollTop，
 * 避免用户在滚动加载时看到视口内容跳动。</p>
 */
async function loadOlderRoundsWithAnchor() {
  const el = messageListRef.value;
  if (!el) return;
  const previousScrollHeight = el.scrollHeight;
  await sessionStore.loadOlderMessages();
  if (messageListRef.value) {
    messageListRef.value.scrollTop += messageListRef.value.scrollHeight - previousScrollHeight;
  }
}

/** 滚动到底部 */
function scrollToBottom(force: boolean = false) {
  if (!messageListRef.value) return;
  
  // 非强制模式下，只有 shouldAutoScroll 为 true 才滚动
  if (!force && !shouldAutoScroll.value) return;
  
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
    }
  });
}

/** 强制滚动到底部（不受 shouldAutoScroll 限制） */
function forceScrollToBottom() {
  scrollToBottom(true);
}

function handleRetry() {
  emit('retry');
}

// 合并滚动监听：减少重复滚动调用
watch(
  () => [
    sessionStore.chatMessages.length,
    analysisStore.state.rounds.length,
    analysisStore.state.isAnalyzing,
    analysisStore.state.analysisReport,
    analysisStore.state.queryData.length,
  ],
  () => {
    // 分析中且用户没有上滚时，才自动滚动
    if (analysisStore.state.isAnalyzing && shouldAutoScroll.value) {
      scrollToBottom();
    }
  }
);

// 监听当前轮次思考内容变化自动滚动（高频事件，单独处理）
watch(
  () => {
    const current = analysisStore.state.rounds.find(r => r.id === analysisStore.state.currentRoundId);
    return current?.thinking.content.length ?? 0;
  },
  () => {
    if (shouldAutoScroll.value) {
      scrollToBottom();
    }
  }
);

// 分析完成或报告更新时，强制滚动到底部
watch(
  () => [analysisStore.state.isAnalyzing, analysisStore.state.analysisReport],
  () => {
    if (!analysisStore.state.isAnalyzing) {
      nextTick(() => {
        forceScrollToBottom();
      });
    }
  }
);

/**
 * 会话历史加载完成后是否滚动到最新对话
 * <p>初次加载（刷新页面）与切换会话时，消息从后端按轮次分页加载（最新优先），
 * 列表需定位到最新对话（底部），而不是停留在最早加载的轮次处。</p>
 */
let pendingScrollToLatest = false;

// 会话切换时标记需要滚动到最新对话
watch(
  () => sessionStore.currentSessionId,
  (newId) => {
    pendingScrollToLatest = Boolean(newId);
  },
  { immediate: true }
);

// 历史消息完整加载后定位到最新对话
// <p>chatMessages 数组引用变化代表一次完整加载（loadMessages / 缓存恢复 / 加载更早轮次），
// 仅在会话初始加载时定位；加载更早轮次时 pending 已清除，不会误触。</p>
watch(
  () => sessionStore.chatMessages,
  async (messages) => {
    if (messages.length > 0 && pendingScrollToLatest) {
      pendingScrollToLatest = false;
      // 分析中的增量滚动由上方监听处理，此处仅处理非分析状态的历史加载
      if (!analysisStore.state.isAnalyzing) {
        await nextTick();
        await anchorToLatest();
      }
    }
  }
);

/**
 * 定位到最新对话（瞬时锚定 + 双 rAF 二次校准，无滚动动画）
 * <p>先在同一渲染帧内瞬时设置 scrollTop = scrollHeight（scrollTop 赋值本身无动画）；
 * 随后用双 requestAnimationFrame 二次校准，覆盖图表（autoresize）与分析报告
 * （rAF 渲染）异步内容造成的高度增量。每次校准前检查 shouldAutoScroll，
 * 用户已上滚时跳过，避免打断用户阅读。</p>
 */
async function anchorToLatest() {
  const el = messageListRef.value;
  if (!el) return;
  el.scrollTop = el.scrollHeight;
  // 双 rAF 二次校准：图表 / 报告渲染完成后高度可能继续增长
  for (let i = 0; i < 2; i++) {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    if (messageListRef.value && shouldAutoScroll.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
    }
  }
}

// 返回聊天页（组件重新挂载）且已有历史消息时，定位到最新对话
onMounted(async () => {
  if (
    sessionStore.currentSessionId &&
    sessionStore.chatMessages.length > 0 &&
    !analysisStore.state.isAnalyzing
  ) {
    await nextTick();
    await anchorToLatest();
  }
});
</script>

<style scoped lang="less">
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;

  &__content {
    display: flex;
    flex-direction: column;
    gap: 24px;
    max-width: 768px;
    margin: 0 auto;
    padding: 0 16px;
  }
}

/**
 * 消息轮次样式
 * <p>每轮对话（问题+回复）之间有适当的间距和分隔。</p>
 */
.message-round {
  display: flex;
  flex-direction: column;
  gap: 12px;
  
  // 轮次之间的分隔线（可选，根据需要调整）
  &:not(:last-child) {
    padding-bottom: 8px;
    border-bottom: 1px solid var(--td-border-level-1-color, #e7e7e7);
  }
}
</style>
