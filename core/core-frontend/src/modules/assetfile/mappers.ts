/**
 * 会话文件域纯函数工具（前端拦截校验与展示格式化）。
 * 文本类扩展名白名单对齐后端领域校验口径；体积上限 50MB（超限后端 413）。
 */

/** 文件大小上限（50MB，对齐后端 multipart 限制）。 */
export const MAX_FILE_BYTES = 50 * 1024 * 1024;

/** 文本类文件扩展名白名单（小写，不含点；与后端"仅文本类文件"契约一致）。 */
export const TEXT_FILE_EXTENSIONS: readonly string[] = [
  'txt', 'md', 'csv', 'tsv', 'json', 'jsonl', 'xml', 'yaml', 'yml', 'log',
  'js', 'ts', 'jsx', 'tsx', 'py', 'java', 'go', 'rs', 'c', 'cpp', 'h',
  'sql', 'ini', 'conf', 'cfg', 'env', 'properties', 'html', 'css', 'scss',
  'svg', 'srt', 'vtt',
];

/** 文件最小结构约定（File 子集，node 测试环境无需 DOM File 构造）。 */
export interface FileLike {
  /** 文件名（含扩展名） */
  name: string;
  /** 字节大小 */
  size: number;
}

/** 上传前端校验结果。 */
export interface FileUploadValidation {
  /** 是否通过 */
  ok: boolean;
  /** 未通过原因（通过时为 undefined，供 UI 直显） */
  reason?: string;
}

/**
 * 判断文件名是否属于文本类扩展名白名单（大小写不敏感，取最后一段扩展名）。
 * @param name 文件名
 * @returns 白名单内返回 true
 */
export function isTextLikeFile(name: string): boolean {
  const dotIndex = name.lastIndexOf('.');
  if (dotIndex < 0 || dotIndex === name.length - 1) {
    return false;
  }
  return TEXT_FILE_EXTENSIONS.includes(name.slice(dotIndex + 1).toLowerCase());
}

/**
 * 字节数人性化展示（1024 进制，B/KB/MB/GB/TB，KB 以上保留一位小数并去尾零）。
 * @param bytes 字节数（负数或 NaN 等非法值按 0 处理）
 * @returns 形如 "1.5 MB" 的展示串
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  if (unitIndex === 0) {
    return `${value} B`;
  }
  const rounded = Math.round(value * 10) / 10;
  const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
  return `${text} ${units[unitIndex]}`;
}

/**
 * 校验待上传文件：文本类扩展名白名单优先，其次 ≤50MB 体积。
 * @param file 待校验文件（File 或结构等价对象）
 * @returns 校验结果；失败时 reason 为可直接展示的中文文案
 */
export function validateFileForUpload(file: FileLike): FileUploadValidation {
  if (!isTextLikeFile(file.name)) {
    return { ok: false, reason: '仅支持文本类文件（如 txt/md/csv/json/源码等），不支持图片、压缩包或 Office 文档' };
  }
  if (file.size > MAX_FILE_BYTES) {
    return { ok: false, reason: '文件大小超过 50MB 限制' };
  }
  return { ok: true };
}
