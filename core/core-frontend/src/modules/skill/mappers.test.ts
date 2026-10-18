// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { MAX_SKILL_ZIP_BYTES, validateSkillZip } from './mappers';

describe('validateSkillZip', () => {
  it('should_returnOk_when_validateSkillZip_given_zipWithinLimit', () => {
    // given
    const file = { name: 'data-analysis.zip', size: 1024 };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.ok).toBe(true);
    expect(result.reason).toBeUndefined();
  });

  it('should_returnOk_when_validateSkillZip_given_uppercaseExtension', () => {
    // given
    const file = { name: 'REPORT.ZIP', size: MAX_SKILL_ZIP_BYTES };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.ok).toBe(true);
  });

  it('should_rejectWithZipReason_when_validateSkillZip_given_nonZipExtension', () => {
    // given
    const file = { name: 'skill.tar.gz', size: 1024 };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('.zip');
  });

  it('should_rejectWithSizeReason_when_validateSkillZip_given_over50MB', () => {
    // given
    const file = { name: 'big.zip', size: MAX_SKILL_ZIP_BYTES + 1 };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('50MB');
  });

  it('should_rejectWithZipReason_when_validateSkillZip_given_noExtension', () => {
    // given
    const file = { name: 'SKILL', size: 0 };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('.zip');
  });

  it('should_preferZipReason_when_validateSkillZip_given_nonZipAndOversize', () => {
    // given
    const file = { name: 'huge.rar', size: MAX_SKILL_ZIP_BYTES * 2 };

    // when
    const result = validateSkillZip(file);

    // then
    expect(result.reason).toContain('.zip');
  });
});
