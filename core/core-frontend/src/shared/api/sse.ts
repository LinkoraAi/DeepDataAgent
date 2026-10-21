import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { EventSourceMessage } from '@microsoft/fetch-event-source';
import { authHeaders, clearAuthSession } from './http';

/**
 * SSE 客户端（基于 @microsoft/fetch-event-source，硬约束：原生 EventSource 无法带 Authorization 头）。
 *
 * 后端帧约定（runtime ChatEventCodec）：
 * - buffered 事件：`event: <type>` + `id: evt_xxx` + data 为扁平事件 JSON；
 * - 增量帧：event_start / event_delta（需连接时协商 event_deltas[]）；
 * - 注释帧 `: connected` / `: heartbeat`（库自动忽略）；
 * - 断线重连以 Last-Event-ID 头回传最后事件 id；
 * - SSE 建立失败时后端以 application/json 错误信封返回（非 event-stream）。
 */

/** 原始帧（透传给上层归一化器） */
export interface RawSseFrame {
  event: string;
  id: string;
  data: string;
}

export interface SseOptions {
  /** 重连游标（上次收到的 evt_ id） */
  lastEventId?: string | null;
  /** 协商增量输出的事件类型（仅 agent.message / agent.thinking 合法） */
  deltaTypes?: string[];
  /**
   * 请求方法（默认 GET）。
   * <p>POST 用于需要在请求体中携带较大载荷的场景（如检索附图 base64），
   * 此时会自动补充 {@code Content-Type: application/json}。</p>
   */
  method?: 'GET' | 'POST';
  /** 请求体（仅 POST 使用，调用方负责序列化为 JSON 字符串） */
  body?: string;
  /**
   * 是否在网络错误后自动重连（默认 true）。
   * <p>一次性流（如检索答案）必须显式关闭：重连会重复触发服务端检索与模型调用。</p>
   */
  autoReconnect?: boolean;
  /** 每帧回调（含 buffered 与增量帧；注释帧不会到达） */
  onFrame: (frame: RawSseFrame) => void;
  /** 致命错误（4xx/鉴权失败等，不再重连）或网络错误（重连间隙提示） */
  onError: (error: Error) => void;
  /** 连接建立回调 */
  onOpen?: () => void;
}

export interface SseHandle {
  close: () => void;
}

/** 拼接 event_deltas[] 查询参数 */
function withDeltaQuery(path: string, deltaTypes?: string[]): string {
  if (!deltaTypes || deltaTypes.length === 0) {
    return path;
  }
  const search = new URLSearchParams();
  deltaTypes.forEach((t) => search.append('event_deltas[]', t));
  return `${path}${path.includes('?') ? '&' : '?'}${search.toString()}`;
}

/**
 * 打开 SSE 流。返回句柄用于组件卸载时关闭；
 * 网络级错误默认自动重连（库默认指数退避），4xx 视为致命不再重连。
 * <p>{@code autoReconnect=false} 时任何错误或连接关闭都不再重连，适用于一次性流
 * （如检索答案：重连会重复触发服务端检索与模型调用）。POST 模式下请求体经 {@code body} 下发，
 * 并自动补充 JSON 内容类型。</p>
 */
export function openSseStream(path: string, options: SseOptions): SseHandle {
  const controller = new AbortController();
  let closed = false;
  // 致命错误标记（4xx/非流响应）：置位后 onerror 重抛以终止库的自动重连
  let fatal = false;
  const autoReconnect = options.autoReconnect !== false;
  const method = options.method ?? 'GET';
  const url = withDeltaQuery(path, options.deltaTypes);
  const headers: Record<string, string> = {};
  authHeaders(headers).forEach((value, key) => {
    headers[key] = value;
  });
  if (method === 'POST') {
    headers['Content-Type'] = 'application/json';
  }
  if (options.lastEventId) {
    headers['Last-Event-ID'] = options.lastEventId;
  }

  void fetchEventSource(url, {
    method,
    body: options.body,
    headers,
    signal: controller.signal,
    openWhenHidden: true,
    onopen: async (res) => {
      const contentType = res.headers.get('content-type') ?? '';
      if (res.ok && contentType.includes('text/event-stream')) {
        options.onOpen?.();
        return;
      }
      // 非流响应：错误信封（如 401/400 非法游标），读取消息后终止重连
      let message = `SSE 连接失败(${res.status})`;
      try {
        const envelope = JSON.parse(await res.text());
        if (envelope?.error?.message) {
          message = envelope.error.message;
        }
      } catch {
        // 保留默认消息
      }
      if (res.status === 401) {
        clearAuthSession();
        if (window.location.pathname !== '/login') {
          window.location.assign('/login');
        }
      }
      fatal = true;
      options.onError(new Error(message));
      throw new Error(message);
    },
    onmessage: (msg: EventSourceMessage) => {
      if (!closed) {
        options.onFrame({ event: msg.event, id: msg.id, data: msg.data });
      }
    },
    onerror: (err) => {
      if (closed) {
        return;
      }
      if (fatal || !autoReconnect) {
        // 重抛终止自动重连（v2.0.1 未导出 FatalError，以标志位等价实现）
        options.onError(err instanceof Error ? err : new Error(String(err)));
        throw err;
      }
      options.onError(err instanceof Error ? err : new Error(String(err)));
      // 返回 undefined 让库按默认退避重连
    },
    onclose: () => {
      closed = true;
      if (!autoReconnect) {
        // 一次性流：服务端正常结束即终止连接，阻止库在 onclose 后自动重连
        controller.abort();
      }
    },
  }).catch(() => {
    // fetchEventSource 的 reject 已由 onopen/onerror 路径处理，此处防未捕获告警
  });

  return {
    close: () => {
      closed = true;
      controller.abort();
    },
  };
}
