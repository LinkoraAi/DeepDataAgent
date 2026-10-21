// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { PARSE_PROVIDER_FILE_TYPES, describeParseProviderTypes } from './parseCapability';

/**
 * 解析引擎能力提示文案单测。
 * <p>守住四条口径：LOCAL 列内置解析类型并补充图片兜底说明（LOCAL 保留兜底）、
 * MINERU 只列引擎自身类型（严格模式无兜底）、未知提供方不产出文案。</p>
 */

describe('describeParseProviderTypes', () => {
  it('should_listLocalTypesWithImageHint_when_describeParseProviderTypes_given_localProvider', () => {
    // given
    const provider = 'LOCAL';

    // when
    const hint = describeParseProviderTypes(provider);

    // then
    expect(hint).toBe('支持格式：PDF、DOC、DOCX、XLS、XLSX、PPT、PPTX、TXT、MD、CSV；'
      + '图片（PNG/JPG/JPEG）以视觉直通处理');
  });

  it('should_listMineruTypesWithoutFallbackHint_when_describeParseProviderTypes_given_mineruProvider', () => {
    // given
    const provider = 'MINERU';

    // when
    const hint = describeParseProviderTypes(provider);

    // then
    expect(hint).toBe('支持格式：PDF、DOC、DOCX、XLS、XLSX、PPT、PPTX、PNG、JPG、JPEG');
  });

  it('should_returnEmptyString_when_describeParseProviderTypes_given_unknownProvider', () => {
    // given
    const provider = 'UNKNOWN';

    // when
    const hint = describeParseProviderTypes(provider);

    // then
    expect(hint).toBe('');
  });

  it('should_coverBothProviders_when_readingTypeMapping_given_currentEngineSet', () => {
    // when
    const providers = Object.keys(PARSE_PROVIDER_FILE_TYPES).sort();

    // then
    expect(providers).toEqual(['LOCAL', 'MINERU']);
    expect(PARSE_PROVIDER_FILE_TYPES.LOCAL).toContain('XLSX');
    expect(PARSE_PROVIDER_FILE_TYPES.MINERU).toContain('XLSX');
    expect(PARSE_PROVIDER_FILE_TYPES.MINERU).toContain('JPEG');
  });
});