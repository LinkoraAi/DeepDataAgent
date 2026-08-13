import { computed, ref, watch, onUnmounted } from 'vue';
import { useAnalysisStore } from '../stores/analysis';

export type AnalysisPhase =
  | 'idle'
  | 'connecting'
  | 'thinking'
  | 'executing_tools'
  | 'executing_sql'
  | 'generating_chart'
  | 'generating_report';

const PHASE_LABELS: Record<AnalysisPhase, string> = {
  idle: '空闲',
  connecting: '正在连接...',
  thinking: 'Agent 思考中...',
  executing_tools: '执行工具调用...',
  executing_sql: '执行 SQL 查询...',
  generating_chart: '生成图表...',
  generating_report: '生成分析报告...',
};

/**
 * Composable for tracking analysis progress
 */
export function useAnalysisProgress() {
  const analysisStore = useAnalysisStore();
  const elapsedSeconds = ref(0);
  let timer: ReturnType<typeof setInterval> | null = null;

  const currentPhase = computed<AnalysisPhase>(() => {
    const s = analysisStore.state;
    if (!s.isAnalyzing) return 'idle';
    if (s.analysisReport) return 'generating_report';
    if (s.chartConfig || s.queryData.length > 0) return 'generating_chart';
    if (s.currentSQL) return 'executing_sql';
    // 基于统一内容流判定阶段（tool_call 与 tool_result 均为工具执行阶段；结果项进行中同样计入）
    const hasRunningTools = s.contentItems.some(i => (i.type === 'tool_call' || i.type === 'tool_result') && i.status === 'in_progress');
    const hasAnyToolCalls = s.contentItems.some(i => i.type === 'tool_call' || i.type === 'tool_result');
    const hasThinking = s.contentItems.some(i => i.type === 'thinking' && (i.status === 'in_progress' || i.content));
    if (hasRunningTools) return 'executing_tools';
    if (hasAnyToolCalls) return 'executing_tools';
    if (hasThinking) return 'thinking';
    return 'connecting';
  });

  const phaseLabel = computed(() => PHASE_LABELS[currentPhase.value] || '处理中...');

  const startTimer = () => {
    if (timer) return;
    elapsedSeconds.value = 0;
    timer = setInterval(() => {
      elapsedSeconds.value++;
    }, 1000);
  };

  const stopTimer = () => {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  };

  // Watch isAnalyzing 自动启停定时器
  watch(
    () => analysisStore.state.isAnalyzing,
    (isAnalyzing) => {
      if (isAnalyzing) {
        startTimer();
      } else {
        stopTimer();
      }
    },
    { immediate: true }
  );

  onUnmounted(() => {
    stopTimer();
  });

  return {
    currentPhase,
    phaseLabel,
    elapsedSeconds,
  };
}
