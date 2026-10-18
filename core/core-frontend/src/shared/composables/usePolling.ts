import { onScopeDispose, ref } from 'vue';
import type { Ref } from 'vue';

/**
 * 条件轮询（文档解析/删除收敛等）。
 * - tick 返回 true 表示"仍在进行"，继续轮询；返回 false 自动停止；
 * - 页面不可见（visibilitychange）暂停、恢复可见立即补一次；
 * - 作用域销毁自动清理定时器。
 */
export interface PollingHandle {
  running: Ref<boolean>;
  /** 启动轮询（已启动则忽略） */
  start: () => void;
  /** 手动停止 */
  stop: () => void;
}

export function usePolling(tick: () => Promise<boolean>, intervalMs = 4000): PollingHandle {
  const running = ref(false);
  let timer: ReturnType<typeof setTimeout> | null = null;
  let active = false;

  async function loop(): Promise<void> {
    if (!active) {
      return;
    }
    let shouldContinue = false;
    try {
      shouldContinue = await tick();
    } catch {
      // 单次轮询失败不中断整体轮询（网络抖动容忍）
      shouldContinue = true;
    }
    if (!active) {
      return;
    }
    if (shouldContinue) {
      timer = setTimeout(loop, intervalMs);
    } else {
      stop();
    }
  }

  function start(): void {
    if (active) {
      return;
    }
    active = true;
    running.value = true;
    void loop();
  }

  function stop(): void {
    active = false;
    running.value = false;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  const onVisibility = (): void => {
    if (document.visibilityState === 'visible') {
      if (running.value) {
        void loop();
      }
    } else {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    }
  };

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibility);
  }

  onScopeDispose(() => {
    stop();
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisibility);
    }
  });

  return { running, start, stop };
}
