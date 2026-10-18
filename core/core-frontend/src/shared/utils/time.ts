/**
 * 时间展示工具（对齐 Cloud Agents 控制台口径）。
 * - {@link relativeTime}：列表页用相对时间（刚刚 / 12秒前 / 3分钟前 / 2小时前 / 昨天 09:20 / 10-14 22:31）
 * - {@link absoluteTime}：详情页用绝对时间（2026年10月14日 22:31:43）
 * - {@link compactTime}：紧凑绝对时间（2026-10-14 22:31）
 */

/** 相对时间的分档阈值（毫秒）。 */
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** 超过该跨度不再使用相对时间，回落为「10-14 22:31」。 */
const RELATIVE_MAX_DAYS = 7;

/** 解析 ISO 串为 Date；非法值返回 null。 */
function parse(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** 两位补零。 */
function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** HH:mm 文本。 */
function hhmm(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 相对时间文本；非法或空值返回 '-'。
 * @param iso ISO-8601 时间串
 * @param now 参照时间（默认当前，测试可注入）
 */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  const date = parse(iso);
  if (!date) {
    return '-';
  }
  const diff = now.getTime() - date.getTime();
  const abs = Math.abs(diff);
  const suffix = diff >= 0 ? '前' : '后';

  if (abs < 5 * 1000) {
    return '刚刚';
  }
  if (abs < MINUTE) {
    return `${Math.floor(abs / 1000)}秒${suffix}`;
  }
  if (abs < HOUR) {
    return `${Math.floor(abs / MINUTE)}分钟${suffix}`;
  }
  if (abs < DAY) {
    return `${Math.floor(abs / HOUR)}小时${suffix}`;
  }
  if (abs < 2 * DAY) {
    return diff >= 0 ? `昨天 ${hhmm(date)}` : `明天 ${hhmm(date)}`;
  }
  if (abs < RELATIVE_MAX_DAYS * DAY) {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hhmm(date)}`;
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hhmm(date)}`;
}

/** 绝对时间文本：2026年10月14日 22:31:43；非法或空值返回 '-'。 */
export function absoluteTime(iso: string | null | undefined): string {
  const date = parse(iso);
  if (!date) {
    return '-';
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${hhmm(date)}:${pad(date.getSeconds())}`;
}

/** 紧凑绝对时间：2026-10-14 22:31；非法或空值返回 '-'。 */
export function compactTime(iso: string | null | undefined): string {
  const date = parse(iso);
  if (!date) {
    return '-';
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hhmm(date)}`;
}

/** 时刻文本 HH:mm:ss（会话事件时间轴用）；非法或空值返回 '-'。 */
export function clockTime(iso: string | null | undefined): string {
  const date = parse(iso);
  if (!date) {
    return '-';
  }
  return `${hhmm(date)}:${pad(date.getSeconds())}`;
}

/** 时长文本：00:00:10（小时:分:秒，会话总时长用）。 */
export function durationText(seconds: number | null | undefined): string {
  const total = Number.isFinite(seconds) && (seconds ?? 0) > 0 ? Math.floor(seconds as number) : 0;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

/** token 数量紧凑文本：6.6k / 33（会话 In/Out 用）。 */
export function compactCount(value: number | null | undefined): string {
  const num = Number.isFinite(value) ? (value as number) : 0;
  if (num >= 1000) {
    const thousands = num / 1000;
    return `${thousands >= 100 ? Math.round(thousands) : thousands.toFixed(1)}k`;
  }
  return String(num);
}