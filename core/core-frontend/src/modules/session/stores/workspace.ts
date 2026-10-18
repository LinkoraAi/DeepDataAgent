import { defineStore } from 'pinia';
import { computed, reactive, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { openSseStream } from '@/shared/api/sse';
import type { RawSseFrame, SseHandle } from '@/shared/api/sse';
import { ApiError } from '@/shared/types/api';
import { errorMessage } from '@/shared/composables/useAsync';
import { normalizeChatEvent } from '../chat/normalizeChatEvent';
import {
  applyEvent,
  createReducerState,
  selectLastEventId,
  selectTimeline,
} from '../chat/streamingReducer';
import type { ReducerState, TimelineItem } from '../chat/streamingReducer';
import { aggregateTokenUsage, isSessionReadOnly, statusFromEvent } from '../chat/workspaceLogic';
import type { TokenUsage } from '../chat/workspaceLogic';
import {
  archiveSession,
  cancelSession,
  deleteSession,
  getSession,
  listEvents,
  sendEvents,
  updateSessionTitle,
} from '../api/sessions';
import type { SessionCancelResult } from '../api/sessions';
import type { ChatEventWire, ContentBlock, OutboundEvent, SessionStatusWire, SessionWire } from '../types';

/**
 * 会话工作区 store（会话级，setup 风格）。
 * <p>编排三段生命周期：进入 → 拉会话详情 → 升序翻页回放历史 → 建立 SSE 实时订阅；
 * 运行期每帧经 {@link normalizeChatEvent} 归一后喂 {@link applyEvent} 归约（回放与实时按 evt_ id 幂等去重），
 * 会话状态事件同步 {@link status}；离开 {@link teardown} 关闭 SSE（断流 MUST NOT 取消后端任务）。</p>
 * <p>时间线 / token 计量 / 最后事件游标均为 {@code reducer} 派生的 computed，组件只消费不持有。</p>
 */

/** 会话事件流端点前缀（与 api/sessions BASE 同源；SSE 直连 fetch 层拼完整路径）。 */
const SESSIONS_BASE = '/api/v1/cloud/sessions';

/** 历史回放单页条数（后端上限 100）。 */
const HISTORY_PAGE_LIMIT = 100;

/** 协商增量输出的事件类型（后端仅接受 agent.message / agent.thinking）。 */
const DELTA_TYPES = ['agent.message', 'agent.thinking'];

/** 待发送的图片附件（base64 源，data URL 前缀已由上层剥离）。 */
export interface ImageAttachment {
  /** MIME 类型（如 image/png） */
  mediaType: string;
  /** base64 正文（不含 data: 前缀） */
  data: string;
}

export const useWorkspaceStore = defineStore('session-workspace', () => {
  // ==================== state ====================

  /** 当前会话 ID（load 记录、teardown 复位）。 */
  const sessionId = ref('');
  /** 会话详情对象（含冻结智能体快照 / environment_id / resources）。 */
  const session = ref<SessionWire | null>(null);
  /** 归约器状态（reactive 包裹以驱动时间线 computed；就地修改、load 时清空复位）。 */
  const reducer = reactive<ReducerState>(createReducerState());
  /** 会话四态（由 session.status 与 SSE 状态事件同步）。 */
  const status = ref<SessionStatusWire>('idle');
  /** SSE 是否在线。 */
  const connected = ref(false);
  /** SSE 连接异常提示（顶部黄条；空串 = 无）。 */
  const streamError = ref('');
  /** 发送中（乐观禁用输入，避免连点）。 */
  const sending = ref(false);
  /** 初始化中（会话详情 + 历史回放阶段）。 */
  const initializing = ref(false);

  /** SSE 句柄（teardown 关闭）。 */
  let sseHandle: SseHandle | null = null;

  // ==================== getters ====================

  /** 时间线（首次出现顺序；含流式占位与 buffered 完整帧）。 */
  const timeline = computed<TimelineItem[]>(() => selectTimeline(reducer));

  /** 最后一条 buffered 事件 id（SSE 重连 Last-Event-ID 游标）。 */
  const lastEventId = computed<string | null>(() => selectLastEventId(reducer));

  /** token 计量聚合（In/Out；时间线 span.model_request_end 派生）。 */
  const tokenUsage = computed<TokenUsage>(() => aggregateTokenUsage(timeline.value));

  /** 是否只读（归档或终止：禁发消息与挂载操作）。 */
  const readOnly = computed<boolean>(() => isSessionReadOnly(session.value));

  /** 是否运行中（中断 / 取消按钮与发送框禁用的判据）。 */
  const running = computed<boolean>(() => status.value === 'running');

  // ==================== internal ====================

  /** 归约一帧原始 SSE：归一化 → 应用 → 状态事件同步会话态。 */
  function ingestFrame(raw: RawSseFrame): void {
    const evt = normalizeChatEvent(raw);
    if (!evt) {
      return;
    }
    applyEvent(reducer, evt);
    if (evt.kind === 'buffered') {
      const next = statusFromEvent(typeof evt.event.type === 'string' ? evt.event.type : '');
      if (next) {
        status.value = next;
        if (session.value) {
          session.value.status = next;
        }
      }
    }
  }

  /** 乐观归约 POST 回显的已落库事件（按 evt_ id 幂等，SSE 同帧到达时不重复）。 */
  function ingestPersisted(events: ChatEventWire[]): void {
    for (const event of events) {
      if (!event || typeof event.id !== 'string' || typeof event.type !== 'string') {
        continue;
      }
      applyEvent(reducer, { kind: 'buffered', id: event.id, event: event as unknown as Record<string, unknown> });
      const next = statusFromEvent(event.type);
      if (next && session.value) {
        status.value = next;
        session.value.status = next;
      }
    }
  }

  /** 建立（或重建）SSE 订阅，携带最后事件游标与增量协商。 */
  function openStream(): void {
    if (!sessionId.value) {
      return;
    }
    closeStream();
    const path = `${SESSIONS_BASE}/${encodeURIComponent(sessionId.value)}/events/stream`;
    sseHandle = openSseStream(path, {
      lastEventId: lastEventId.value,
      deltaTypes: DELTA_TYPES,
      onFrame: ingestFrame,
      onOpen: () => {
        connected.value = true;
        streamError.value = '';
      },
      onError: (error) => {
        connected.value = false;
        streamError.value = error.message || '连接中断，正在重连';
      },
    });
  }

  /** 关闭 SSE 句柄（不触发后端取消）。 */
  function closeStream(): void {
    if (sseHandle) {
      sseHandle.close();
      sseHandle = null;
    }
    connected.value = false;
  }

  /** 清空归约器状态（load 复位用；就地清空以触发 computed）。 */
  function resetTimeline(): void {
    reducer.order.length = 0;
    reducer.items.clear();
  }

  /** 升序翻页回放历史事件（游标 after_id，has_more 终止）。 */
  async function replayHistory(): Promise<void> {
    let afterId: string | undefined;
    // 上限保护：极端历史量下最多回放 50 页（5000 条），防失控循环
    for (let page = 0; page < 50; page += 1) {
      const resp = await listEvents(sessionId.value, {
        order: 'asc',
        limit: HISTORY_PAGE_LIMIT,
        after_id: afterId,
      });
      for (const event of resp.data ?? []) {
        applyEvent(reducer, { kind: 'buffered', id: event.id, event: event as unknown as Record<string, unknown> });
      }
      if (!resp.has_more || !resp.last_id) {
        break;
      }
      afterId = resp.last_id;
    }
  }

  // ==================== actions ====================

  /**
   * 进入工作区：会话详情 → 历史回放 → 建立 SSE。
   * @param target 会话业务 ID
   */
  async function load(target: string): Promise<void> {
    sessionId.value = target;
    initializing.value = true;
    resetTimeline();
    streamError.value = '';
    try {
      const detail = await getSession(target);
      session.value = detail;
      status.value = detail.status;
      await replayHistory();
      openStream();
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
    } finally {
      initializing.value = false;
    }
  }

  /**
   * 发送一条用户消息（文本 + 可选图片附件）。
   * <p>忙冲突（running 下提交含文本消息）后端 409，友好提示"会话正忙"；成功仅表示已落库，
   * 回答以 SSE 到达为准。</p>
   * @param text 文本正文（可为空，仅图片时）
   * @param images 图片附件（base64）
   * @returns 是否已受理（false = 被拦截或失败）
   */
  async function sendText(text: string, images: ImageAttachment[] = []): Promise<boolean> {
    const trimmed = text.trim();
    if (!sessionId.value || readOnly.value) {
      return false;
    }
    if (!trimmed && images.length === 0) {
      return false;
    }
    const content: ContentBlock[] = [];
    if (trimmed) {
      content.push({ type: 'text', text: trimmed });
    }
    for (const image of images) {
      content.push({ type: 'image', source: { type: 'base64', media_type: image.mediaType, data: image.data } });
    }
    sending.value = true;
    try {
      const persisted = await sendEvents(sessionId.value, [{ type: 'user.message', content }]);
      // 乐观渲染用户气泡（POST 已回显落库事件，SSE 同 id 帧到达由 reducer 幂等去重）
      ingestPersisted(persisted ?? []);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        MessagePlugin.warning('会话正忙，请等待当前执行结束或先中断');
      } else {
        MessagePlugin.error(errorMessage(e));
      }
      return false;
    } finally {
      sending.value = false;
    }
  }

  /** 提交一条入站控制事件（中断 / 确认），失败统一提示。 */
  async function sendControl(event: OutboundEvent): Promise<boolean> {
    if (!sessionId.value) {
      return false;
    }
    try {
      const persisted = await sendEvents(sessionId.value, [event]);
      // 回显落库事件（如 user.tool_confirmation），审批卡片即时更新
      ingestPersisted(persisted ?? []);
      return true;
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
      return false;
    }
  }

  /** 中断当前轮次（user.interrupt，回 idle；不受归档门禁限制）。 */
  function interrupt(): Promise<boolean> {
    return sendControl({ type: 'user.interrupt' });
  }

  /**
   * 提交工具人工审批裁决（user.tool_confirmation）。
   * @param toolUseId 工具调用 ID
   * @param allow 是否允许
   * @param reason 拒绝原因（仅 deny 合法）
   */
  function confirmTool(toolUseId: string, allow: boolean, reason?: string): Promise<boolean> {
    const event: OutboundEvent = allow
      ? { type: 'user.tool_confirmation', tool_use_id: toolUseId, result: 'allow' }
      : { type: 'user.tool_confirmation', tool_use_id: toolUseId, result: 'deny', deny_message: reason?.trim() || undefined };
    return sendControl(event);
  }

  /**
   * 取消当前执行（200/202 双态文案；不断 SSE，最终态由状态事件驱动）。
   * @returns 受理结果（失败为 null）
   */
  async function cancel(): Promise<SessionCancelResult | null> {
    if (!sessionId.value) {
      return null;
    }
    try {
      const result = await cancelSession(sessionId.value);
      if (result.accepted) {
        MessagePlugin.info('取消请求已受理，正在停止当前执行');
      } else {
        MessagePlugin.info('当前无进行中的执行，无需取消');
      }
      return result;
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
      return null;
    }
  }

  /**
   * 更新会话标题。
   * @param title 新标题
   * @returns 是否成功
   */
  async function rename(title: string): Promise<boolean> {
    if (!sessionId.value) {
      return false;
    }
    try {
      const updated = await updateSessionTitle(sessionId.value, title);
      session.value = updated;
      return true;
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
      return false;
    }
  }

  /**
   * 归档会话（写 archived_at 并中断运行中执行；成功后同步本地态）。
   * @returns 归档后的会话对象（失败为 null）
   */
  async function archive(): Promise<SessionWire | null> {
    if (!sessionId.value) {
      return null;
    }
    try {
      const archived = await archiveSession(sessionId.value);
      session.value = archived;
      status.value = archived.status;
      return archived;
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
      return null;
    }
  }

  /**
   * 删除会话（逻辑删除；成功后关闭流，导航由调用方处理）。
   * @returns 是否成功
   */
  async function remove(): Promise<boolean> {
    if (!sessionId.value) {
      return false;
    }
    try {
      await deleteSession(sessionId.value);
      closeStream();
      return true;
    } catch (e) {
      MessagePlugin.error(errorMessage(e));
      return false;
    }
  }

  /** 离开工作区：关闭 SSE（绝不触发取消）。 */
  function teardown(): void {
    closeStream();
    streamError.value = '';
    sending.value = false;
    initializing.value = false;
  }

  /** 复位全部状态（供单测隔离与页面重挂载）。 */
  function $reset(): void {
    teardown();
    resetTimeline();
    session.value = null;
    status.value = 'idle';
    sessionId.value = '';
  }

  return {
    // state
    sessionId,
    session,
    status,
    connected,
    streamError,
    sending,
    initializing,
    // getters
    timeline,
    lastEventId,
    tokenUsage,
    readOnly,
    running,
    // actions
    load,
    sendText,
    interrupt,
    cancel,
    confirmTool,
    rename,
    archive,
    remove,
    teardown,
    $reset,
  };
});
