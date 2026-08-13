import { ref, readonly } from 'vue';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { AgentEvent } from '@/modules/agent/types';

/**
 * SSE 连接状态
 */
export type SSEConnectionStatus = 'disconnected' | 'connecting' | 'connected';

/**
 * SSE 事件回调
 * <p>所有回调均接收 sessionId 参数，支持多会话并行分析场景。</p>
 */
export interface SSEEventCallbacks {
  onEvent: (sessionId: string, event: AgentEvent) => void;
  onError?: (sessionId: string, error: Error) => void;
  onComplete?: (sessionId: string) => void;
}

/**
 * 全局 SSE 连接管理器
 * <p>单例模式，管理客户端与后端的 SSE 长连接。
 * 所有会话的分析事件通过此连接推送，前端按 sessionId 路由。</p>
 */
class SSEConnectionManager {
  private static instance: SSEConnectionManager;
  
  private clientId: string | null = null;
  private controller: AbortController | null = null;
  private status = ref<SSEConnectionStatus>('disconnected');
  private callbacks: Map<string, SSEEventCallbacks> = new Map();
  /** 重连成功回调：SSE 连接重连后通知调用方更新 clientId */
  private onReconnectCallback: (() => void) | null = null;
  /** 自动重连成功回调：SSE 连接自动重连（onclose 触发）后通知调用方重新恢复运行中会话订阅 */
  private onAutoReconnectCallback: (() => void) | null = null;
  
  private constructor() {}
  
  /**
   * 获取单例实例
   */
  static getInstance(): SSEConnectionManager {
    if (!SSEConnectionManager.instance) {
      SSEConnectionManager.instance = new SSEConnectionManager();
    }
    return SSEConnectionManager.instance;
  }
  
  /**
   * 建立 SSE 连接
   * <p>如果已连接则复用现有连接。如果连接断开，自动重新建立连接。</p>
   */
  async connect(): Promise<string> {
    if (this.status.value === 'connected' && this.clientId) {
      return this.clientId;
    }

    // 如果之前有旧连接，先清理
    if (this.controller) {
      this.controller.abort();
      this.controller = null;
    }

    this.status.value = 'connecting';
    this.clientId = null;
    this.controller = new AbortController();

    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const url = `${baseUrl}/api/agent/sse/connect`;

    return new Promise((resolve, reject) => {
      fetchEventSource(url, {
        method: 'GET',
        headers: {
          'Accept': 'text/event-stream',
        },
        signal: this.controller!.signal,
        async onopen(response) {
          if (!response.ok) {
            const errorMsg = `SSE connection failed: ${response.status} ${response.statusText}`;
            throw new Error(errorMsg);
          }
        },
        onmessage: (ev) => {
          if (ev.event === 'CONNECTED' && ev.data) {
            try {
              const data = JSON.parse(ev.data);
              this.clientId = data.clientId;
              this.status.value = 'connected';
              console.log('[SSE] Connected with clientId:', this.clientId);
              if (this.clientId) {
                resolve(this.clientId);
                // 重连成功后通知调用方更新 clientId
                if (this.onReconnectCallback) {
                  console.log('[SSE] Reconnection detected, notifying callback');
                  this.onReconnectCallback();
                }
              } else {
                reject(new Error('SSE connected but clientId is null'));
              }
            } catch (err) {
              console.error('[SSE] Failed to parse CONNECTED event:', err);
              reject(err);
            }
          } else if (ev.event === 'ANALYSIS_EVENT' && ev.data) {
            this.handleAnalysisEvent(ev.data);
          } else if (ev.event === 'STATE_REPLAY' && ev.data) {
            this.handleStateReplay(ev.data);
          }
        },
        onerror: (err) => {
          console.error('[SSE] Connection error:', err);
          this.status.value = 'disconnected';
          this.clientId = null;
          // 只 reject 如果尚未 resolve（连接尚未建立）
          // 连接已建立后的错误由 onclose 处理
          reject(err);
          // 不自动重连，由调用方（useDataAnalysis）在用户操作时重新连接
          throw err;
        },
        onclose: () => {
          console.log('[SSE] Connection closed');
          this.status.value = 'disconnected';
          this.clientId = null;
          // 如果有活跃的回调，尝试自动重连
          if (this.callbacks.size > 0) {
            console.log('[SSE] Connection closed with active callbacks, attempting to reconnect...');
            setTimeout(() => {
              this.connect().then(() => {
                // 自动重连成功后，通知调用方重新恢复运行中会话的订阅（绑定新 clientId）
                if (this.onAutoReconnectCallback) {
                  console.log('[SSE] Auto-reconnect complete, notifying auto-reconnect callback');
                  this.onAutoReconnectCallback();
                }
              }).catch(err => {
                console.error('[SSE] Auto-reconnect failed:', err);
              });
            }, 1000);
          }
        },
        openWhenHidden: true,
      }).catch((err: any) => {
        if (err.name === 'AbortError') {
          console.debug('[SSE] Connection aborted by user');
        } else {
          console.error('[SSE] Connection error:', err);
          this.status.value = 'disconnected';
          this.clientId = null;
        }
      });
    });
  }
  
  /**
   * 处理分析事件
   */
  private handleAnalysisEvent(data: string) {
    try {
      const wrapper = JSON.parse(data);
      const sessionId = wrapper.sessionId;
      const event = wrapper.event;
      
      // 查找该 sessionId 的回调
      const callbacks = this.callbacks.get(sessionId);
      if (callbacks) {
        callbacks.onEvent(sessionId, event);
      } else {
        // 诊断日志：记录被丢弃的事件类型，判断是分析完成后的尾部事件还是分析中的实时事件
        console.warn('[SSE] No callbacks registered for sessionId:', sessionId, 'eventType:', event?.type);
      }
    } catch (err) {
      console.error('[SSE] Failed to parse ANALYSIS_EVENT:', err, 'Raw:', data);
    }
  }
  
  /**
   * 处理状态回放事件（STATE_REPLAY）
   * <p>刷新恢复（resume）时后端一次性推送断线期间累积的分析事件列表。
   * 这里逐个事件按原处理路径重建该会话的分析状态，弥补断线窗口内事件的丢失。</p>
   *
   * @param data STATE_REPLAY 的原始数据（{"sessionId": "...", "events": [...]}）
   */
  private handleStateReplay(data: string) {
    try {
      const payload = JSON.parse(data);
      const sessionId = payload.sessionId;
      const events: AgentEvent[] = payload.events || [];
      const callbacks = this.callbacks.get(sessionId);
      if (!callbacks) {
        console.warn('[SSE] No callbacks registered for sessionId:', sessionId);
        return;
      }
      // 诊断日志：确认回放事件是否含结束事件类型（排查「回放后回调被删导致后续事件丢失」）
      const endTypes = events.filter(e => e && ['AGENT_END', 'EXCEED_MAX_ITERS', 'ERROR'].includes(e.type));
      console.log('[SSE][diag] replay:', {
        count: events.length,
        firstType: events[0]?.type,
        lastType: events[events.length - 1]?.type,
        endEvents: endTypes.map(e => e.type),
      });
      for (const event of events) {
        callbacks.onEvent(sessionId, event);
      }
      console.log('[SSE] Replayed', events.length, 'buffered events for sessionId:', sessionId);
    } catch (err) {
      console.error('[SSE] Failed to parse STATE_REPLAY:', err, 'Raw:', data);
    }
  }

  /**
   * 注册会话事件回调
   */
  registerCallbacks(sessionId: string, callbacks: SSEEventCallbacks): () => void {
    this.callbacks.set(sessionId, callbacks);
    
    // 返回取消注册函数
    return () => {
      this.callbacks.delete(sessionId);
    };
  }
  
  /**
   * 获取客户端 ID
   */
  getClientId(): string | null {
    return this.clientId;
  }
  
  /**
   * 获取连接状态
   */
  getStatus() {
    return readonly(this.status);
  }
  
  /**
   * 获取会话回调数
   * <p>用于判断是否有活跃的分析会话，决定是否在重连后重新提交分析请求。</p>
   */
  getCallbacksCount(): number {
    return this.callbacks.size;
  }

  /**
   * 设置重连回调
   * <p>SSE 连接重连成功后调用，通知调用方更新 clientId 并重新提交分析请求。</p>
   */
  setOnReconnect(callback: () => void) {
    this.onReconnectCallback = callback;
  }

  /**
   * 设置自动重连成功回调
   * <p>SSE 连接断开后自动重连成功时调用，通知调用方重新恢复运行中会话的订阅
   * （将新 clientId 重新绑定到各会话）。区别于 {@link setOnReconnect}：
   * 该回调不依赖 lastRequest，适用于页面刷新后无待提交请求但仍有运行中会话的场景。</p>
   */
  setOnAutoReconnect(callback: () => void) {
    this.onAutoReconnectCallback = callback;
  }

  /**
   * 断开连接
   */
  disconnect() {
    if (this.controller) {
      this.controller.abort();
      this.controller = null;
    }
    this.clientId = null;
    this.status.value = 'disconnected';
    this.callbacks.clear();
  }
}

/**
 * 使用全局 SSE 连接
 */
export function useSSEConnection() {
  const manager = SSEConnectionManager.getInstance();
  
  return {
    connect: manager.connect.bind(manager),
    disconnect: manager.disconnect.bind(manager),
    getClientId: manager.getClientId.bind(manager),
    getStatus: manager.getStatus,
    registerCallbacks: manager.registerCallbacks.bind(manager),
    getCallbacksCount: manager.getCallbacksCount.bind(manager),
    setOnReconnect: manager.setOnReconnect.bind(manager),
    setOnAutoReconnect: manager.setOnAutoReconnect.bind(manager),
  };
}
