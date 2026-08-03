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
    const url = `${baseUrl}/agent/sse/connect`;

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
              this.connect().catch(err => {
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
        console.warn('[SSE] No callbacks registered for sessionId:', sessionId);
      }
    } catch (err) {
      console.error('[SSE] Failed to parse ANALYSIS_EVENT:', err, 'Raw:', data);
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
  };
}
