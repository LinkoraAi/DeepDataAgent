// @vitest-environment node
import { describe, expect, it } from 'vitest';
import {
  CHUNK_MODE_ALLOWED_FILE_TYPES,
  MAX_DOCUMENT_BYTES,
  allowedChunkModesForFileType,
  buildChunkStrategyJson,
  buildMeta,
  chunkModeLabel,
  describeDocChunkStrategy,
  describeMatchAxes,
  extractKbChunkStrategy,
  fileTypeFromName,
  groupPrecheckHits,
  isChunkModeCompatible,
  matchAxisLabel,
  parseChunkStrategy,
  validateChunkStrategyDraft,
  validateUploadFile,
} from './mappers.documents';

describe('fileTypeFromName', () => {
  it('should_returnPdf_when_fileTypeFromName_given_lowercasePdfName', () => {
    // given
    const name = 'report.pdf';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('PDF');
  });

  it('should_returnDocx_when_fileTypeFromName_given_uppercaseName', () => {
    // given
    const name = 'MANUAL.DOCX';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('DOCX');
  });

  it('should_returnEmpty_when_fileTypeFromName_given_extensionNotInWhitelist', () => {
    // given
    const name = 'archive.zip';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('');
  });

  it('should_returnEmpty_when_fileTypeFromName_given_noExtension', () => {
    // given
    const name = 'README';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('');
  });

  it('should_returnEmpty_when_fileTypeFromName_given_trailingDot', () => {
    // given
    const name = 'note.';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('');
  });

  it('should_returnCsv_when_fileTypeFromName_given_multiDotName', () => {
    // given
    const name = 'a.b.c.csv';

    // when
    const result = fileTypeFromName(name);

    // then
    expect(result).toBe('CSV');
  });
});

describe('validateUploadFile', () => {
  it('should_returnOk_when_validateUploadFile_given_validTypeWithinSize', () => {
    // given
    const file = { name: 'doc.pdf', size: 1024 };

    // when
    const result = validateUploadFile(file);

    // then
    expect(result.ok).toBe(true);
    expect(result.reason).toBeUndefined();
  });

  it('should_returnOk_when_validateUploadFile_given_exactSizeBoundary', () => {
    // given
    const file = { name: 'big.png', size: MAX_DOCUMENT_BYTES };

    // when
    const result = validateUploadFile(file);

    // then
    expect(result.ok).toBe(true);
  });

  it('should_rejectTypeReason_when_validateUploadFile_given_unknownExtension', () => {
    // given
    const file = { name: 'tool.exe', size: 1024 };

    // when
    const result = validateUploadFile(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('文件类型');
  });

  it('should_rejectSizeReason_when_validateUploadFile_given_over100MB', () => {
    // given
    const file = { name: 'huge.pdf', size: MAX_DOCUMENT_BYTES + 1 };

    // when
    const result = validateUploadFile(file);

    // then
    expect(result.ok).toBe(false);
    expect(result.reason).toContain('100MB');
  });

  it('should_preferTypeReason_when_validateUploadFile_given_badTypeAndOversize', () => {
    // given
    const file = { name: 'huge.iso', size: MAX_DOCUMENT_BYTES * 2 };

    // when
    const result = validateUploadFile(file);

    // then
    expect(result.reason).toContain('文件类型');
  });
});

describe('buildMeta', () => {
  it('should_buildThreeFields_when_buildMeta_given_pdfFile', () => {
    // given
    const file = { name: 'spec.md', size: 2048 };

    // when
    const meta = buildMeta(7, file);

    // then
    expect(meta).toEqual({ kbId: 7, fileName: 'spec.md', fileType: 'MD' });
  });

  it('should_keepOriginalName_when_buildMeta_given_uppercaseExtension', () => {
    // given
    const file = { name: 'Photo.JPG', size: 10 };

    // when
    const meta = buildMeta(1, file);

    // then
    expect(meta.fileName).toBe('Photo.JPG');
    expect(meta.fileType).toBe('JPG');
  });
});

describe('matchAxisLabel / describeMatchAxes', () => {
  it('should_returnChinese_when_matchAxisLabel_given_knownAxis', () => {
    // given / when / then
    expect(matchAxisLabel('fileName')).toBe('文件名');
    expect(matchAxisLabel('contentHash')).toBe('文件内容');
  });

  it('should_returnRaw_when_matchAxisLabel_given_unknownAxis', () => {
    // given / when / then
    expect(matchAxisLabel('mystery')).toBe('mystery');
  });

  it('should_joinWithSeparator_when_describeMatchAxes_given_twoAxes', () => {
    // given
    const axes = ['fileName', 'contentHash'];

    // when
    const text = describeMatchAxes(axes);

    // then
    expect(text).toBe('文件名、文件内容');
  });
});

describe('groupPrecheckHits', () => {
  it('should_returnEmpty_when_groupPrecheckHits_given_noHits', () => {
    // given
    const hits: Parameters<typeof groupPrecheckHits>[0] = [];

    // when
    const groups = groupPrecheckHits(hits);

    // then
    expect(groups).toEqual([]);
  });

  it('should_bucketSameAxis_when_groupPrecheckHits_given_twoSameNameAxis', () => {
    // given
    const hits = [
      { id: 1, fileName: 'a.pdf', matchAxes: ['fileName'] },
      { id: 2, fileName: 'b.pdf', matchAxes: ['fileName'] },
    ];

    // when
    const groups = groupPrecheckHits(hits);

    // then
    expect(groups).toHaveLength(1);
    expect(groups[0].axisLabel).toBe('文件名');
    expect(groups[0].hits).toEqual([
      { id: 1, fileName: 'a.pdf' },
      { id: 2, fileName: 'b.pdf' },
    ]);
  });

  it('should_separateAndSortAxes_when_groupPrecheckHits_given_mixedAxes', () => {
    // given
    const hits = [
      { id: 1, fileName: 'a.pdf', matchAxes: ['contentHash'] },
      { id: 2, fileName: 'b.pdf', matchAxes: ['fileName', 'contentHash'] },
    ];

    // when
    const groups = groupPrecheckHits(hits);

    // then
    expect(groups).toHaveLength(2);
    const both = groups.find((group) => group.axisLabel.includes('文件名'));
    // 分组按命中轴排序生成稳定键，展示文案随之为排序后顺序（contentHash < fileName）
    expect(both?.axisLabel).toBe('文件内容、文件名');
    expect(both?.hits[0].id).toBe(2);
  });
});

describe('parseChunkStrategy', () => {
  it('should_returnGeneralView_when_parseChunkStrategy_given_fullGeneralJson', () => {
    // given
    const raw = JSON.stringify({
      chunkMode: 'GENERAL',
      modeConfig: { params: { chunk_token_num: 300, overlapped_percent: 10, delimiter: '。' } },
    });

    // when
    const view = parseChunkStrategy(raw);

    // then
    expect(view.chunkMode).toBe('GENERAL');
    expect(view.general).toEqual({ chunkTokenNum: 300, overlappedPercent: 10, delimiter: '。' });
  });

  it('should_returnUnsetFallback_when_parseChunkStrategy_given_nullOrBlank', () => {
    // given / when / then（null、空串、空白串均按「未配置」回退）
    expect(parseChunkStrategy(null)).toEqual({
      chunkMode: '',
      general: { chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' },
    });
    expect(parseChunkStrategy('')).toEqual(parseChunkStrategy('   '));
  });

  it('should_unwrapDoubleEncoded_when_parseChunkStrategy_given_wrappedStringJson', () => {
    // given（存量形态：策略 JSON 被再包一层字符串）
    const inner = JSON.stringify({ chunkMode: 'QA' });
    const raw = JSON.stringify(inner);

    // when
    const view = parseChunkStrategy(raw);

    // then
    expect(view.chunkMode).toBe('QA');
  });

  it('should_returnUnsetFallback_when_parseChunkStrategy_given_invalidJson', () => {
    // given / when
    const view = parseChunkStrategy('{not-json');

    // then
    expect(view.chunkMode).toBe('');
  });

  it('should_treatAsUnset_when_parseChunkStrategy_given_modeOutsideWhitelist', () => {
    // given（后端会 400 拒绝的形态，展示层按未配置回退而非崩溃）
    const raw = JSON.stringify({ chunkMode: 'auto' });

    // when / then
    expect(parseChunkStrategy(raw).chunkMode).toBe('');
  });

  it('should_normalizeCase_when_parseChunkStrategy_given_lowercaseMode', () => {
    // given / when / then
    expect(parseChunkStrategy(JSON.stringify({ chunkMode: 'qa' })).chunkMode).toBe('QA');
  });

  it('should_fallbackDefault_when_parseChunkStrategy_given_outOfRangeParams', () => {
    // given
    const raw = JSON.stringify({
      chunkMode: 'GENERAL',
      modeConfig: { params: { chunk_token_num: 99999, overlapped_percent: 88, delimiter: '' } },
    });

    // when
    const view = parseChunkStrategy(raw);

    // then（逐字段回退默认值）
    expect(view.general).toEqual({ chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });
  });

  it('should_readCompatKey_when_parseChunkStrategy_given_legacyOverlapKey', () => {
    // given（历史键 overlap 与后端 validateOverlappedPercent 兼容口径一致）
    const raw = JSON.stringify({ chunkMode: 'GENERAL', modeConfig: { params: { overlap: 15 } } });

    // when / then
    expect(parseChunkStrategy(raw).general.overlappedPercent).toBe(15);
  });
});

describe('extractKbChunkStrategy', () => {
  it('should_returnStrategyJson_when_extractKbChunkStrategy_given_validRagConfig', () => {
    // given
    const rag = JSON.stringify({
      engineType: 'DOCUMENT_ENGINE',
      chunkStrategy: { chunkMode: 'BOOK', modeConfig: { params: { chunk_token_num: 256 } } },
    });

    // when
    const extracted = extractKbChunkStrategy(rag);

    // then
    expect(extracted).not.toBeNull();
    expect(JSON.parse(extracted as string)).toEqual({ chunkMode: 'BOOK', modeConfig: { params: { chunk_token_num: 256 } } });
  });

  it('should_unwrapAndNormalize_when_extractKbChunkStrategy_given_wrappedStringStrategy', () => {
    // given（库级配置里的 chunkStrategy 子节点为字符串再包层形态）
    const rag = JSON.stringify({ chunkStrategy: JSON.stringify({ chunkMode: 'QA' }) });

    // when
    const extracted = extractKbChunkStrategy(rag);

    // then（归一为对象序列化文本）
    expect(extracted).toBe(JSON.stringify({ chunkMode: 'QA' }));
  });

  it('should_returnNull_when_extractKbChunkStrategy_given_missingOrInvalid', () => {
    // given / when / then
    expect(extractKbChunkStrategy(null)).toBeNull();
    expect(extractKbChunkStrategy('not json')).toBeNull();
    expect(extractKbChunkStrategy(JSON.stringify({ engineType: 'DOCUMENT_ENGINE' }))).toBeNull();
    expect(extractKbChunkStrategy(JSON.stringify({ chunkStrategy: 'nonsense' }))).toBeNull();
  });
});

describe('chunkModeLabel / describeDocChunkStrategy', () => {
  it('should_returnGeneralLabel_when_chunkModeLabel_given_unsetMode', () => {
    // given / when / then（空白模式按后端 resolveMode 口径等同 GENERAL）
    expect(chunkModeLabel('')).toBe('通用');
    expect(chunkModeLabel(null)).toBe('通用');
  });

  it('should_returnRaw_when_chunkModeLabel_given_unknownOutsideWhitelist', () => {
    // given / when / then（白名单外原样返回，信息不丢失）
    expect(chunkModeLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('should_showDocModeName_when_describeDocChunkStrategy_given_docLevelStrategy', () => {
    // given / when / then
    expect(describeDocChunkStrategy(JSON.stringify({ chunkMode: 'QA' }), null)).toBe('问答');
  });

  it('should_showInheritLabel_when_describeDocChunkStrategy_given_nullDocAndKbBook', () => {
    // given / when / then
    const kb = JSON.stringify({ chunkMode: 'BOOK' });
    expect(describeDocChunkStrategy(null, kb)).toBe('继承库级（书籍）');
  });

  it('should_fallbackGeneral_when_describeDocChunkStrategy_given_bothUnset', () => {
    // given / when / then（库级脏数据回退「通用」）
    expect(describeDocChunkStrategy(null, null)).toBe('继承库级（通用）');
    expect(describeDocChunkStrategy(null, 'garbage')).toBe('继承库级（通用）');
  });
});

describe('allowedChunkModesForFileType / isChunkModeCompatible', () => {
  it('should_matchBackendMatrix_when_allowedChunkModesForFileType_given_eachWhitelistType', () => {
    // given / when / then（逐条镜像后端 ChunkStrategyFileTypeValidator.ALLOWED_TYPES_BY_MODE）
    expect(allowedChunkModesForFileType('PDF')).toEqual(['GENERAL', 'BOOK', 'LAWS', 'PRESENTATION', 'ONE']);
    expect(allowedChunkModesForFileType('DOC')).toEqual(['GENERAL', 'QA', 'BOOK', 'LAWS', 'ONE']);
    expect(allowedChunkModesForFileType('DOCX')).toEqual(['GENERAL', 'QA', 'BOOK', 'LAWS', 'ONE']);
    expect(allowedChunkModesForFileType('XLS')).toEqual(['GENERAL', 'QA', 'TABLE', 'ONE']);
    expect(allowedChunkModesForFileType('XLSX')).toEqual(['GENERAL', 'QA', 'TABLE', 'ONE']);
    expect(allowedChunkModesForFileType('PPT')).toEqual(['GENERAL', 'PRESENTATION', 'ONE']);
    expect(allowedChunkModesForFileType('PPTX')).toEqual(['GENERAL', 'PRESENTATION', 'ONE']);
    expect(allowedChunkModesForFileType('TXT')).toEqual(['GENERAL', 'QA', 'BOOK', 'LAWS', 'TABLE', 'ONE']);
    expect(allowedChunkModesForFileType('MD')).toEqual(['GENERAL', 'QA', 'BOOK', 'LAWS', 'ONE']);
    expect(allowedChunkModesForFileType('CSV')).toEqual(['GENERAL', 'QA', 'TABLE']);
    expect(allowedChunkModesForFileType('PNG')).toEqual(['GENERAL']);
    expect(allowedChunkModesForFileType('JPG')).toEqual(['GENERAL']);
    expect(allowedChunkModesForFileType('JPEG')).toEqual(['GENERAL']);
  });

  it('should_returnAllModes_when_allowedChunkModesForFileType_given_unknownOrEmptyType', () => {
    // given / when / then（对齐后端「无类型可校验直接通过」：不设限）
    const all = ['GENERAL', 'QA', 'BOOK', 'LAWS', 'TABLE', 'PRESENTATION', 'ONE'];
    expect(allowedChunkModesForFileType(null)).toEqual(all);
    expect(allowedChunkModesForFileType('ZIP')).toEqual(all);
  });

  it('should_mirrorConstantTable_when_matrixQueried_given_pdfAndCsv', () => {
    // given / when / then（常量表与后端矩阵逐项一致的关键抽样）
    expect(CHUNK_MODE_ALLOWED_FILE_TYPES.GENERAL).toHaveLength(13);
    expect(CHUNK_MODE_ALLOWED_FILE_TYPES.PRESENTATION).toEqual(['PDF', 'PPT', 'PPTX']);
    expect(CHUNK_MODE_ALLOWED_FILE_TYPES.TABLE).toEqual(['XLS', 'XLSX', 'TXT', 'CSV']);
  });

  it('should_answerCompatibility_when_isChunkModeCompatible_given_matrixEdges', () => {
    // given / when / then
    expect(isChunkModeCompatible('TABLE', 'PDF')).toBe(false);
    expect(isChunkModeCompatible('GENERAL', 'PDF')).toBe(true);
    expect(isChunkModeCompatible('QA', 'PNG')).toBe(false);
  });
});

describe('buildChunkStrategyJson', () => {
  it('should_includeThreeParams_when_buildChunkStrategyJson_given_generalMode', () => {
    // given / when
    const json = buildChunkStrategyJson('GENERAL', { chunkTokenNum: 256, overlappedPercent: 5, delimiter: '\n\n' });

    // then（仅三个规范键，无历史键泄漏）
    expect(JSON.parse(json)).toEqual({
      chunkMode: 'GENERAL',
      modeConfig: { params: { chunk_token_num: 256, overlapped_percent: 5, delimiter: '\n\n' } },
    });
  });

  it('should_omitModeConfig_when_buildChunkStrategyJson_given_nonGeneralMode', () => {
    // given / when
    const json = buildChunkStrategyJson('QA', { chunkTokenNum: 999, overlappedPercent: 30, delimiter: '|' });

    // then（后端「非 GENERAL 携带 params 即拒绝」，装配层 MUST NOT 写入）
    expect(JSON.parse(json)).toEqual({ chunkMode: 'QA' });
  });
});

describe('validateChunkStrategyDraft', () => {
  const generalOk = { chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' };

  it('should_pass_when_validateChunkStrategyDraft_given_validGeneral', () => {
    // given / when / then
    expect(validateChunkStrategyDraft('GENERAL', generalOk)).toEqual({});
  });

  it('should_passWithoutParams_when_validateChunkStrategyDraft_given_nonGeneralMode', () => {
    // given / when / then（非 GENERAL 参数不提交也校验）
    expect(validateChunkStrategyDraft('ONE', generalOk)).toEqual({});
  });

  it('should_rejectIllegalMode_when_validateChunkStrategyDraft_given_modeOutsideWhitelist', () => {
    // given / when
    const errors = validateChunkStrategyDraft('auto', generalOk);

    // then
    expect(errors.chunkMode).toBeDefined();
  });

  it('should_rejectOutOfRange_when_validateChunkStrategyDraft_given_boundaryViolations', () => {
    // given / when / then（token 开区间下界：0 非法；上界 2048 合法；overlap 31 非法；小数非法）
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, chunkTokenNum: 0 }).chunkTokenNum).toBeDefined();
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, chunkTokenNum: 2048 })).toEqual({});
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, chunkTokenNum: 2049 }).chunkTokenNum).toBeDefined();
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, chunkTokenNum: 1.5 }).chunkTokenNum).toBeDefined();
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, overlappedPercent: 31 }).overlappedPercent).toBeDefined();
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, delimiter: '' }).delimiter).toBeDefined();
  });

  it('should_acceptWhitespaceDelimiter_when_validateChunkStrategyDraft_given_spaceDelimiter', () => {
    // given / when / then（空白字符本身是合法分隔符，仅拒绝空串）
    expect(validateChunkStrategyDraft('GENERAL', { ...generalOk, delimiter: ' ' })).toEqual({});
  });
});

describe('buildMeta with chunkStrategy', () => {
  it('should_omitStrategyKey_when_buildMeta_given_undefinedOrBlankStrategy', () => {
    // given
    const file = { name: 'a.pdf', size: 10 };

    // when
    const absent = buildMeta(1, file);
    const blank = buildMeta(1, file, '  ');

    // then（缺省与空白均不写键：后端收到 null = 继承库级）
    expect(absent).toEqual({ kbId: 1, fileName: 'a.pdf', fileType: 'PDF' });
    expect(blank.chunkStrategy).toBeUndefined();
  });

  it('should_carryStrategy_when_buildMeta_given_validStrategyJson', () => {
    // given
    const file = { name: 'a.pdf', size: 10 };
    const strategy = buildChunkStrategyJson('BOOK', { chunkTokenNum: 512, overlappedPercent: 0, delimiter: '\n' });

    // when
    const meta = buildMeta(1, file, strategy);

    // then
    expect(meta.chunkStrategy).toBe(strategy);
  });
});
