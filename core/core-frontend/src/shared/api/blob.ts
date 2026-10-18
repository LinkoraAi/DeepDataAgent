import { ApiError } from '@/shared/types/api';
import type { ErrorEnvelope } from '@/shared/types/api';
import { authHeaders, createTimeoutError, DEFAULT_BLOB_TIMEOUT_MS, withTimeoutSignal } from './http';

/**
 * 鉴权二进制读取（文档原文/下载、切片媒体、技能包下载）。
 * 后端错误时返回 JSON 信封而非流，这里按 content-type 分流处理。
 * 所有二进制请求均带超时（默认 {@link DEFAULT_BLOB_TIMEOUT_MS}），保证失败/超时后必然结束。
 */

/**
 * GET 二进制为 Blob（携带 Bearer）。
 * @param timeoutMs 超时毫秒数，默认 120s（≤0 关闭超时）
 */
export async function fetchBlob(path: string, timeoutMs: number = DEFAULT_BLOB_TIMEOUT_MS): Promise<Blob> {
  const timeout = withTimeoutSignal(undefined, timeoutMs);
  try {
    const response = await fetch(path, { headers: authHeaders({ Accept: '*/*' }), signal: timeout.signal });
    if (!response.ok) {
      const contentType = response.headers.get('content-type') ?? '';
      if (contentType.includes('application/json')) {
        const envelope = (await response.json().catch(() => null)) as ErrorEnvelope | null;
        if (envelope?.error?.message) {
          throw new ApiError(response.status, envelope.error.type ?? '', envelope.error.message, envelope.request_id ?? '');
        }
      }
      throw new ApiError(response.status, '', `资源读取失败(${response.status})`);
    }
    return await response.blob();
  } catch (error) {
    // 超时归一为可读超时错误；其余（后端错误信封/网络错误）原样抛出
    if (timeout.isTimedOut()) {
      throw createTimeoutError(timeoutMs, '资源读取');
    }
    throw error;
  } finally {
    timeout.cleanup();
  }
}

/**
 * 触发浏览器下载（object URL + a[download]，用完即 revoke）。
 * @param blob 内容
 * @param filename 落盘文件名
 */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

/**
 * 从 Content-Disposition 提取文件名（优先 RFC5987 filename*=UTF-8''，回落 filename=）。
 * 解析失败返回兜底名。
 */
export function filenameFromDisposition(disposition: string | null, fallback: string): string {
  if (!disposition) {
    return fallback;
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8Match) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return fallback;
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch ? plainMatch[1] : fallback;
}

/**
 * GET 二进制并直接触发下载（下载端点组合便捷方法）。
 * @param timeoutMs 超时毫秒数，默认 120s（≤0 关闭超时）；超时抛出可读超时错误
 */
export async function downloadFile(
  path: string,
  fallbackName: string,
  timeoutMs: number = DEFAULT_BLOB_TIMEOUT_MS,
): Promise<void> {
  const timeout = withTimeoutSignal(undefined, timeoutMs);
  try {
    const response = await fetch(path, { headers: authHeaders({ Accept: '*/*' }), signal: timeout.signal });
    if (!response.ok) {
      throw new ApiError(response.status, '', `下载失败(${response.status})`);
    }
    const blob = await response.blob();
    triggerDownload(blob, filenameFromDisposition(response.headers.get('content-disposition'), fallbackName));
  } catch (error) {
    if (timeout.isTimedOut()) {
      throw createTimeoutError(timeoutMs, '文件下载');
    }
    throw error;
  } finally {
    timeout.cleanup();
  }
}
