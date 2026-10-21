import { post } from '@/shared/api/http';
import { openSseStream } from '@/shared/api/sse';
import type { SseHandle } from '@/shared/api/sse';

/**
 * 检索能力端点（后端 RetrievalController，前缀 /api/v1/rag/retrievals）。
 * <p>契约要点（对齐后端真实代码，检索唯一对外 HTTP 入口，两形态各占一个子端点）：</p>
 * <ul>
 *   <li>{@code POST /chunks} —— 只返回精排后全量命中切片（不作答，token 预算与本端点无关）；
 *       无命中时 chunks 为空数组而非 null；</li>
 *   <li>{@code POST /answer} —— SSE 流式返回 LLM 最终答案：{@code answer.started} →
 *       {@code answer.delta}×N → {@code answer.completed}（只含完整答案）或
 *       {@code answer.error}；建流前的参数/归属校验失败仍返回真实 HTTP 错误信封；</li>
 *   <li>两形态共用同一请求体：kbId / query 必填，其余可空（缺省走后端契约默认值）；</li>
 *   <li>库级检索策略（策略类型 / 结果数量 / 阈值 / 重排 / 融合）由后端从知识库配置读取，
 *       不经本请求体下发，参数调整须走 retrieval-config 端点；</li>
 *   <li>images 为内联附图（通路 A）：数量 ≤3、单图解码后 ≤4MB、类型限
 *       image/jpeg|png|gif|webp，任一非法整请求真实 HTTP 400。</li>
 * </ul>
 */

/** 统一前缀（版本 v1 与后端 ApiVersionConstants.CURRENT_API_VERSION 对齐）。 */
const BASE = '/api/v1/rag/retrievals';

/**
 * 检索请求附图（后端 QueryImageDTO 同构）。
 * <p>仅作一次性转译输入，零持久化；data 为不含 {@code data:} 前缀的 base64。</p>
 */
export interface QueryImage {
  /** 图片 MIME 类型（后端白名单：image/jpeg、image/png、image/gif、image/webp） */
  contentType: string;
  /** 图片内容 base64（不含 data: 前缀） */
  data: string;
}

/** 检索请求体（后端 RetrievalRequest 同构，两形态端点共用）。 */
export interface RetrievalPayload {
  /** 所属知识库ID（必填，单库隔离维度） */
  kbId: number;
  /** 用户原始问题（必填） */
  query: string;
  /** 显式高层关键词（可选，非空时跳过 LLM 提取） */
  hlKeywords?: string[];
  /** 显式低层关键词（可选，非空时跳过 LLM 提取） */
  llKeywords?: string[];
  /** 每通道向量召回数量（可选，缺省走后端契约默认值） */
  topK?: number;
  /** chunk 召回数量（可选，缺省回退 topK） */
  chunkTopK?: number;
  /** 上下文总 token 预算（可选，仅答案形态参与决策） */
  maxTotalTokens?: number;
  /** 内联附图（可选，≤3 张） */
  images?: QueryImage[];
  /** 通路 A（附图转译）开关（可选，缺省视为 true） */
  queryImageTranscribe?: boolean;
  /** 通路 B（作答原图直读）开关（可选，缺省视为 true） */
  answerImageDirectRead?: boolean;
}

/**
 * 命中切片（后端 ChunkReferenceResponse 同构）。
 * <p>modalFile 为后端在线预览端点的相对 URL（鉴权端点，不可直挂 img src），
 * 非图片切片为 null。</p>
 */
export interface ChunkReference {
  /** chunk 唯一标识 */
  chunkId: number;
  /** 切片正文（切片已删除或明细回取失败时为 null） */
  chunkContent: string | null;
  /** 来源文件名（可为 null） */
  sourceFile: string | null;
  /** 多模态资源预览相对 URL（非图片切片为 null） */
  modalFile: string | null;
  /** 相关性分值（RRF 累积分或重排分，越高越相关） */
  score: number;
}

/** 切片形态响应（RetrievalChunksResponse 同构）。 */
export interface RetrievalChunksWire {
  /** 命中切片（按精排序；后端为空数组，前端仍按可空防御） */
  chunks: ChunkReference[] | null;
}

/** 答案流事件名（与后端 AnswerStreamEventCodec 常量逐字一致）。 */
export const ANSWER_EVENT_STARTED = 'answer.started';

/** 答案流：增量事件名。 */
export const ANSWER_EVENT_DELTA = 'answer.delta';

/** 答案流：正常终结事件名。 */
export const ANSWER_EVENT_COMPLETED = 'answer.completed';

/** 答案流：异常终结事件名。 */
export const ANSWER_EVENT_ERROR = 'answer.error';

/** 答案流事件载荷（后端 data JSON 同构，字段按事件类型取用）。 */
interface AnswerStreamPayload {
  /** 事件类型（与 SSE event 名一致） */
  type?: string;
  /** 增量文本（answer.delta） */
  delta?: string;
  /** 完整答案（answer.completed；引用信息由模型在正文 References 段中生成） */
  answer?: string;
  /** 错误码（answer.error） */
  code?: string;
  /** 错误信息（answer.error） */
  message?: string;
}

/**
 * 答案流回调集合。
 * <p>{@code onCompleted} 与 {@code onError} 互斥：一次流只会走到其中之一。</p>
 */
export interface StreamAnswerHandlers {
  /** 检索已进入答案生成阶段 */
  onStarted?: () => void;
  /** 收到增量文本（按到达顺序追加） */
  onDelta: (delta: string) => void;
  /** 正常终结：完整答案（含模型生成的 References 段） */
  onCompleted: (answer: string) => void;
  /** 异常终结或连接失败 */
  onError: (message: string) => void;
}

/**
 * 重排配置（检索策略 JSON 的 rerankConfig 节点）。
 * <p>后端仅做结构校验：enabled=true 时 modelProfileId 必须非空，不查模型注册表。</p>
 */
export interface RerankConfig {
  /** 是否启用重排 */
  enabled?: boolean;
  /** 重排模型 profileId（引用模型注册表） */
  modelProfileId?: string;
  /** 重排保留的 top K 数量（可选） */
  topK?: number;
}

/** 融合配置（检索策略 JSON 的 fusionConfig 节点，RRF 与加权求和字段互斥）。 */
export interface FusionConfig {
  /** 融合类型：RRF / WEIGHTED_SUM */
  fusionType?: string;
  /** RRF 常数 K（仅 RRF 使用，缺省后端回写 60，必须 > 0） */
  rrfK?: number;
  /** 各通道稠密权重（仅 WEIGHTED_SUM 使用，键为 RetrievalChannel 枚举名） */
  channelDenseWeight?: Record<string, number>;
}

/**
 * 知识库检索策略配置（PUT retrieval-config 请求体中 JSON 字符串的反解形态）。
 * <p>除 strategyType 外字段均可缺省（后端按区间校验后回落默认值）。</p>
 */
export interface RetrievalStrategyConfig {
  /** 检索策略类型：NAIVE（向量+全文）/ MIX（含图谱） */
  strategyType?: string;
  /** 是否启用问题改写 */
  rewriteQuestion?: boolean;
  /** 结果返回数量（1~100） */
  resultChunkCount?: number;
  /** 相似度阈值（0~1） */
  similarThreshold?: number;
  /** 重排配置 */
  rerankConfig?: RerankConfig;
  /** 融合配置 */
  fusionConfig?: FusionConfig;
}

/**
 * 检索并只返回命中切片（同步，不作答）。
 * @param payload 检索请求体（kbId / query 必填）
 * @returns 切片形态响应
 */
export function retrieveChunks(payload: RetrievalPayload): Promise<RetrievalChunksWire> {
  return post<RetrievalChunksWire>(`${BASE}/chunks`, payload);
}

/**
 * 以 SSE 流式发起检索作答（一次性流，不自动重连）。
 * <p>建流前的参数/归属校验失败时后端返回 JSON 错误信封，此处由 {@code onError} 归一呈现。</p>
 * @param payload 检索请求体（kbId / query 必填，可携带内联附图）
 * @param handlers 事件回调（增量 / 终结 / 错误）
 * @returns 流句柄（停止生成或组件卸载时调用 close）
 */
export function streamAnswer(payload: RetrievalPayload, handlers: StreamAnswerHandlers): SseHandle {
  return openSseStream(`${BASE}/answer`, {
    method: 'POST',
    body: JSON.stringify(payload),
    autoReconnect: false,
    onFrame: (frame) => {
      let event: AnswerStreamPayload;
      try {
        event = JSON.parse(frame.data) as AnswerStreamPayload;
      } catch {
        return;
      }
      const type = event.type ?? frame.event;
      if (type === ANSWER_EVENT_STARTED) {
        handlers.onStarted?.();
        return;
      }
      if (type === ANSWER_EVENT_DELTA) {
        handlers.onDelta(event.delta ?? '');
        return;
      }
      if (type === ANSWER_EVENT_COMPLETED) {
        handlers.onCompleted(event.answer ?? '');
        return;
      }
      if (type === ANSWER_EVENT_ERROR) {
        handlers.onError(event.message ?? '答案生成失败');
      }
    },
    onError: (error) => handlers.onError(error.message),
  });
}
