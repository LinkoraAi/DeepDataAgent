import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

/**
 * Format datetime string
 */
export function formatDateTime(date: string | Date | undefined): string {
  if (!date) return '';
  return dayjs(date).format('YYYY-MM-DD HH:mm:ss');
}

/**
 * Format date string
 */
export function formatDate(date: string | Date | undefined): string {
  if (!date) return '';
  return dayjs(date).format('YYYY-MM-DD');
}

/**
 * Format relative time (e.g., "2 hours ago")
 */
export function formatRelativeTime(date: string | Date | undefined): string {
  if (!date) return '';
  return dayjs(date).fromNow();
}

/**
 * Format session list time
 * <p>今天显示时分（如 12:30），今天之前显示日期+时分（如 8月6号 12:30）。</p>
 *
 * @param date 时间字符串或 Date
 * @returns 格式化后的会话时间
 */
export function formatSessionTime(date: string | Date | undefined): string {
  if (!date) return '';
  const d = dayjs(date);
  if (!d.isValid()) return '';
  if (d.isSame(dayjs(), 'day')) {
    return d.format('HH:mm');
  }
  return d.format('M月D号 HH:mm');
}

/**
 * Format number with thousands separator
 */
export function formatNumber(num: number | undefined | null): string {
  if (num === undefined || num === null) return '';
  return num.toLocaleString('zh-CN');
}
