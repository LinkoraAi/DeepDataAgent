import { post } from '@/shared/api/http';

/**
 * 检索能力端点（后端 RetrievalController，前缀 /api/v1/rag/retrievals）。
 * <p>契约要点（对齐后端真实代码，检索唯一对外 HTTP 入口，两形态各占一个子端点）：</p>
 * <ul>
 *   <li>{@code POST /chunks} —— 只返回精排后全量命中切片（不作答，token 预算与本端点无关）；
 *       无命中时 chunks 为空数组而非 null；</li>
 *   <li>{@code POST /answer} —— 只返回 LLM 最终答案；答案生成降级时 answer 为 null
 *       （响应不携带引用列表与降级标记）；</li>
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
 * 答案形态请求超时（毫秒）。
 * <p>答案端点是同一条完整链路的同步接口：作答阶段（Stage 5）单次 LLM 调用即可达 ~30s，
 * 冷缓存时首轮查询改写另需十余秒，整体常在 30~45s 区间返回；http 客户端常规请求默认
 * 30s 会先于后端返回把请求中断，前端表现为误报「问答失败：请求超时」。</p>
 * <p>放宽到 10 分钟（600000ms）覆盖该区间。沿用 http 客户端「≤0 关闭超时」语义，
 * 故取值必须为正数；亦不得超过 nginx 该路径的 proxy_read_timeout（见 deploy/nginx/default.conf）。</p>
 */
const RETRIEVAL_ANSWER_TIMEOUT_MS = 600_000;

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

/** 答案形态响应（RetrievalAnswerResponse 同构）。 */
export interface RetrievalAnswerWire {
  /** 最终答案文本（答案生成降级时为 null） */
  answer: string | null;
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
 * 检索并只返回 LLM 最终答案（同步）。
 * @param payload 检索请求体（kbId / query 必填，可携带内联附图）
 * @returns 答案形态响应（answer 可为 null，表示模型降级）
 */
export function retrieveAnswer(payload: RetrievalPayload): Promise<RetrievalAnswerWire> {
  return post<RetrievalAnswerWire>(`${BASE}/answer`, payload, RETRIEVAL_ANSWER_TIMEOUT_MS);
}
