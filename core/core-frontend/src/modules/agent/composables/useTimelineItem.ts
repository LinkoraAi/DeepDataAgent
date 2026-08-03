/**
 * 生成唯一 ID
 */
export function generateTimelineId(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 11)}`;
}

/**
 * 格式化时间戳为可读时间
 */
export function formatTimelineTime(timestamp: number): string {
  const date = new Date(timestamp);
  const hours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');
  const seconds = date.getSeconds().toString().padStart(2, '0');
  return `${hours}:${minutes}:${seconds}`;
}

/**
 * 计算耗时（毫秒转秒，保留一位小数）
 */
export function calculateDuration(startTime: number, endTime?: number): string {
  if (!endTime) return '-';
  const duration = (endTime - startTime) / 1000;
  return `${duration.toFixed(1)}s`;
}

/**
 * 安全解析 JSON
 */
export function safeJsonParse(str: string): any {
  try {
    return JSON.parse(str);
  } catch {
    return null;
  }
}

/**
 * 格式化 JSON 为可读字符串
 */
export function formatJson(obj: any): string {
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return String(obj);
  }
}
