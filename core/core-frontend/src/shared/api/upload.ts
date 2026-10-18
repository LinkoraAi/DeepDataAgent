import { ApiError } from '@/shared/types/api';
import type { ApiResponse, ErrorEnvelope } from '@/shared/types/api';
import { authHeaders, createTimeoutError, DEFAULT_UPLOAD_TIMEOUT_MS, withTimeoutSignal } from './http';

/**
 * multipart 上传封装（与 http.ts 同一套鉴权与错误归一语义）。
 * 后端三种形态：
 * 1. 知识库文档：part `file`（二进制）+ part `meta`（Content-Type: application/json 的 JSON part）；
 * 2. 会话文本文件：part `file` + 文本字段 purpose/filename/metadata；
 * 3. 技能包：part `files`（可多文件；MVP 单 zip）。
 * FormData 不手动设 Content-Type（浏览器自动带 boundary）。
 * 上传带超时（默认 {@link DEFAULT_UPLOAD_TIMEOUT_MS}，大文件放宽），超时抛超时 ApiError。
 */

/**
 * 上传响应（解包后的 data；错误统一抛 ApiError）。
 * @param timeoutMs 超时毫秒数，默认 300s（≤0 关闭超时）
 */
async function sendForm<T>(path: string, form: FormData, timeoutMs: number = DEFAULT_UPLOAD_TIMEOUT_MS): Promise<T> {
  const timeout = withTimeoutSignal(undefined, timeoutMs);
  try {
    const response = await fetch(path, { method: 'POST', headers: authHeaders(), body: form, signal: timeout.signal });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      try {
        const envelope = JSON.parse(text) as ErrorEnvelope;
        if (envelope?.error?.message) {
          throw new ApiError(response.status, envelope.error.type ?? '', envelope.error.message, envelope.request_id ?? '');
        }
      } catch (error) {
        if (error instanceof ApiError) {
          throw error;
        }
      }
      throw new ApiError(response.status, '', `上传失败(${response.status}): ${text.slice(0, 200)}`);
    }
    const wrapper = (await response.json()) as ApiResponse<T>;
    return wrapper.data;
  } catch (error) {
    // 超时归一为可读超时错误；其余（后端错误信封/网络错误）原样抛出
    if (timeout.isTimedOut()) {
      throw createTimeoutError(timeoutMs, '文件上传');
    }
    throw error;
  } finally {
    timeout.cleanup();
  }
}

/** 知识库文档上传：file + meta 双 part（meta 必须声明 application/json part 头）。 */
export function uploadDocument<T>(path: string, file: File, meta: unknown): Promise<T> {
  const form = new FormData();
  form.append('file', file);
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
  return sendForm<T>(path, form);
}

/** 会话文本文件上传：file + 可选 filename/purpose/metadata 文本字段。 */
export function uploadSessionFile<T>(
  path: string,
  file: File,
  fields: { purpose: string; filename?: string; metadata?: string },
): Promise<T> {
  const form = new FormData();
  form.append('file', file);
  form.append('purpose', fields.purpose);
  if (fields.filename) {
    form.append('filename', fields.filename);
  }
  if (fields.metadata) {
    form.append('metadata', fields.metadata);
  }
  return sendForm<T>(path, form);
}

/** 技能包上传：单 zip（part 名 files）。 */
export function uploadSkill<T>(path: string, zip: File, displayTitle?: string): Promise<T> {
  const form = new FormData();
  form.append('files', zip);
  if (displayTitle) {
    form.append('display_title', displayTitle);
  }
  return sendForm<T>(path, form);
}
