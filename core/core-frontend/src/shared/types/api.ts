/**
 * 后端统一协议类型（对齐 core-backend shared/result 包）。
 * 注意：三域命名风格并存（云资源 snake_case、KB/RAG 与 model-profiles/auth camelCase），
 * 本文件只定义跨域通用的包装形态，业务字段类型在各模块 api 文件内定义。
 */

/** 成功响应包装（后端 ApiResponse：HTTP 200 + body；删除受理时 code="202"）。 */
export interface ApiResponse<T> {
  success: boolean;
  /** 业务码字符串："200" 常规成功、"202" 异步受理、"500" 兜底失败 */
  code: string;
  message: string;
  data: T;
}

/** 统一错误信封（后端 ErrorEnvelope，非 2xx 响应体）。 */
export interface ErrorEnvelope {
  error: {
    /** 错误类别：invalid_request_error / authentication_error / permission_error /
     *  not_found_error / conflict_error / request_too_large_error / rate_limit_error / api_error */
    type: string;
    message: string;
  };
  request_id: string;
  type: 'error';
}

/** 页码分页响应（KB/文档/切片/model-profiles/数据源统一形态，页码从 1 起）。 */
export interface PaginatedResp<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

/** 游标分页响应（agents/environments/sessions/skills 等云资源域；无 total）。 */
export interface CursorPage<T> {
  data: T[];
  first_id: string | null;
  last_id: string | null;
  has_more: boolean;
  /** 不透明翻页游标（部分端点返回；优先于 last_id 使用） */
  next_page?: string;
}

/** 文件列表特例形态（后端 FileListResponse：nextCursor 驼峰，非标准游标包装）。 */
export interface FileListResp<T> {
  data: T[];
  nextCursor: string | null;
}

/** 归一化后的 API 错误（http 客户端统一抛出）。 */
export class ApiError extends Error {
  /** HTTP 状态码 */
  readonly status: number;
  /** ErrorEnvelope.error.type（网络层失败时为空串） */
  readonly type: string;
  /** ErrorEnvelope.request_id（便于排障，可为空） */
  readonly requestId: string;

  constructor(status: number, type: string, message: string, requestId = '') {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.type = type;
    this.requestId = requestId;
  }

  /** 是否为"资源冲突"（409 conflict_error 或会话忙 409 invalid_request_error 的宽松判定） */
  get isConflict(): boolean {
    return this.status === 409;
  }

  /** 是否为未认证（401） */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }
}
