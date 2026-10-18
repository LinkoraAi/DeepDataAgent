// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { MAX_FILE_BYTES, formatBytes, isTextLikeFile, validateFileForUpload } from './mappers';

describe('isTextLikeFile', () => {
  it('should_returnTrue_when_isTextLikeFile_given_whitelistedExtension', () => {
    // given & when & then
    expect(isTextLikeFile('notes.txt')).toBe(true);
    expect(isTextLikeFile('data.jsonl')).toBe(true);
    expect(isTextLikeFile('STYLE.CSS')).toBe(true);
    expect(isTextLikeFile('a.b.md')).toBe(true);
  });

  it('should_returnFalse_when_isTextLikeFile_given_binaryOrUnknownExtension', () => {
    // given & when & then
    expect(isTextLikeFile('photo.png')).toBe(false);
    expect(isTextLikeFile('package.zip')).toBe(false);
    expect(isTextLikeFile('report.docx')).toBe(false);
  });

  it('should_returnFalse_when_isTextLikeFile_given_noOrTrailingDotName', () => {
    // given & when & then
    expect(isTextLikeFile('Makefile')).toBe(false);
    expect(isTextLikeFile('trailing.')).toBe(false);
    expect(isTextLikeFile('')).toBe(false);
  });
});

describe('formatBytes', () => {
  it('should_renderByteUnit_when_formatBytes_given_below1024', () => {
    // given & when & then
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(999)).toBe('999 B');
  });

  it('should_renderKBAndMB_when_formatBytes_given_largeSizes', () => {
    // given & when & then
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(1048576)).toBe('1 MB');
    expect(formatBytes(MAX_FILE_BYTES)).toBe('50 MB');
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3 GB');
  });

  it('should_renderZero_when_formatBytes_given_invalidNumber', () => {
    // given & when & then
    expect(formatBytes(-1)).toBe('0 B');
    expect(formatBytes(Number.NaN)).toBe('0 B');
  });
});

describe('validateFileForUpload', () => {
  it('should_returnOk_when_validateFileForUpload_given_textFileWithinLimit', () => {
    // given
    const file = { name: 'query-result.csv', size: 2048 };

    // when
    const result = validateFileForUpload(file);

    // then
    expect(result.ok).toBe(true);
    expect(result.reason).toBeUndefined();
  });

  it('should_rejectWithTextReason_when_validateFileForUpload_given_binaryFile', () => {
    // given
    const file = { name: 'diagram.png', size: 2048 };

    // when
    const result = validateFileForUpload(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('文本类');
  });

  it('should_rejectWithSizeReason_when_validateFileForUpload_given_over50MB', () => {
    // given
    const file = { name: 'huge.log', size: MAX_FILE_BYTES + 1 };

    // when
    const result = validateFileForUpload(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('50MB');
  });

  it('should_preferTextReason_when_validateFileForUpload_given_binaryAndOversize', () => {
    // given
    const file = { name: 'huge.xlsx', size: MAX_FILE_BYTES * 2 };

    // when
    const result = validateFileForUpload(file);

    // then
    expect(result.reason).toContain('文本类');
  });
});
