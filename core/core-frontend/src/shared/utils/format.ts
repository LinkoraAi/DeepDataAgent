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
 * Format number with thousands separator
 */
export function formatNumber(num: number | undefined | null): string {
  if (num === undefined || num === null) return '';
  return num.toLocaleString('zh-CN');
}
