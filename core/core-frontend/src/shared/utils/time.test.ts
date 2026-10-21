import { describe, expect, it } from 'vitest';
import { absoluteTime, compactTime, relativeTime } from './time';

/**
 * 时间展示工具单测（UTC 偏移串归位回归）。
 * <p>核心防线：同一绝对时刻以不同偏移形态下发时，展示结果必须完全一致——
 * 「裸截字符串」实现会把 UTC 分钟数当本地时间显示（+08 环境错 8 小时），本组用例即钉死该缺陷。</p>
 */
describe('time utils', () => {
  /** 同一绝对时刻的两种合法 ISO 形态（UTC 偏移 / 东八区偏移）。 */
  const UTC_ISO = '2026-10-21T08:47:11.847530+00:00';
  const CST_ISO = '2026-10-21T16:47:11.847530+08:00';

  it('should_renderIdentically_when_compactTime_given_sameInstantInDifferentOffsets', () => {
    expect(compactTime(UTC_ISO)).toBe(compactTime(CST_ISO));
  });

  it('should_renderLocalWallClock_when_compactTime_given_utcIso', () => {
    // expected 由本地时区组件推导：与实现同源但独立书写，验证「按绝对时刻归位」而非「截原串」
    const date = new Date(UTC_ISO);
    const pad = (value: number): string => String(value).padStart(2, '0');
    const expected = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
      + `${pad(date.getHours())}:${pad(date.getMinutes())}`;

    expect(compactTime(UTC_ISO)).toBe(expected);
    expect(compactTime(UTC_ISO)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  it('should_showEightHoursLater_when_compactTime_given_utcIsoInAsiaShanghai', () => {
    // 东八区（本机/生产口径）下 UTC 08:47 必须显示 16:47：裸截串实现此处必失败
    if (new Date(UTC_ISO).getTimezoneOffset() !== -480) {
      return; // 非东八区运行环境：由上一条「本地组件推导」用例覆盖
    }
    expect(compactTime(UTC_ISO)).toBe('2026-10-21 16:47');
  });

  it('should_returnPlaceholder_when_compactTime_given_invalidOrEmpty', () => {
    expect(compactTime(null)).toBe('-');
    expect(compactTime(undefined)).toBe('-');
    expect(compactTime('')).toBe('-');
    expect(compactTime('not-a-time')).toBe('-');
  });

  it('should_includeSeconds_when_absoluteTime_given_iso', () => {
    expect(absoluteTime(UTC_ISO)).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}:11$/);
    expect(absoluteTime(null)).toBe('-');
  });

  it('should_keepRelativeBuckets_when_relativeTime_given_recentInstant', () => {
    const now = new Date('2026-10-21T10:00:00+08:00');
    expect(relativeTime('2026-10-21T09:59:30+08:00', now)).toBe('30秒前');
    expect(relativeTime('2026-10-21T08:00:00+08:00', now)).toBe('2小时前');
    expect(relativeTime('bad', now)).toBe('-');
  });
});
