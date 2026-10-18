// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { hexLower, sha256Hex } from './sha256';

describe('sha256', () => {
  it('should 输出小写十六进制摘要_when 计算 abc', async () => {
    const bytes = new TextEncoder().encode('abc');
    expect(await sha256Hex(bytes)).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });

  it('should 补齐前导零_when 字节值小于 16', () => {
    const buffer = new Uint8Array([1, 15, 255]).buffer;
    expect(hexLower(buffer)).toBe('010fff');
  });
});
