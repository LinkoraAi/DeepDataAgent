package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeneralChunkingService} 单元测试。
 * <p>覆盖四条全局契约（delimiter 即边界 / 不原子切分 / OVER_CAP 单一封口线合并 / overlap 无条件前缀）、
 * 默认 512 cap 合并、边界空值（全白块 → 空列表）、null 模式等同 GENERAL 的一致性与多模态块透传；
 * 另覆盖<b>纯直通</b>语义：文件后缀不改道（pptx 仍按 GENERAL）、密集「第N章」标题不投票改道、
 * 六种显式模式各自直通、纯媒体 / 图文混排文档的归并序号回归（1..N 连续）。
 * TokenCounter 使用确定性 stub（{@code text.length()}），使 token 与文本长度一一对应，
 * 便于对切分边界逐字符断言；不使用 Mockito（纯算法、无外部依赖）。</p>
 */
class ChunkingServiceTest {

    /** 确定性 token 计数 stub：长度即 token 数。 */
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    /** 被测对象：真实注册表（仅 general 参与本批契约测试）+ stub 计数端口。 */
    private final GeneralChunkingService service = new GeneralChunkingService(tokenCounter,
            new ChunkStrategyRegistry(List.of(new GeneralChunkStrategy())));

    /** 直通被测对象：七策略全量注册（贴近生产装配），用于纯直通用例。 */
    private final GeneralChunkingService dispatchService = new GeneralChunkingService(tokenCounter,
            new ChunkStrategyRegistry(List.of(new GeneralChunkStrategy(), new QaChunkStrategy(),
                    new BookChunkStrategy(), new LawsChunkStrategy(), new TableChunkStrategy(),
                    new PresentationChunkStrategy(), new OneChunkStrategy())));

    /**
     * 场景：契约 1（delimiter 即边界）——反引号包裹的自定义分隔符 {@code ##} 切分三段，
     * 且分隔符文本绝不进入任何切片（custom 模式：每 segment 独立一块，绕过 token 预算）。
     * 预期：3 个切片，正文均不含 {@code ##}，且各自保留前置换行前缀。
     */
    @Test
    void should_splitByDelimiterWithoutResidue_when_chunk_given_customDelimiter() {
        // given
        ParsedDocument parsed = parsed("aa##bb##cc");
        ChunkParams params = new ChunkParams(512, "`##`", 0);

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(3, chunks.size(), "## 应切出 exactly 3 段");
        assertFalse(chunks.get(0).text().contains("##"), "分隔符不得残留");
        assertFalse(chunks.get(1).text().contains("##"));
        assertFalse(chunks.get(2).text().contains("##"));
        assertEquals("\naa", chunks.get(0).text());
        assertEquals("\nbb", chunks.get(1).text());
        assertEquals("\ncc", chunks.get(2).text());
        assertEquals(1, chunks.get(0).sequence());
        assertEquals(3, chunks.get(2).sequence(), "全局序号应连续递增");
    }

    /**
     * 场景：契约 2（不原子切分）——无分隔符可用的段落各自 token 超过软目标 cap。
     * 预期：每个超预算段独立成块（不被切碎，也不互相合并），段内容逐字符完整保留。
     */
    @Test
    void should_keepOverCapSegmentsAsWholeBlocks_when_chunk_given_overSizedSegments() {
        // given（默认分隔符含 \n，两段各 20 字符 + 前置换行 = 21 tokens > cap 15）
        ParsedDocument parsed = parsed("aaaaaaaaaaaaaaaaaaaa\nbbbbbbbbbbbbbbbbbbbb");
        ChunkParams params = new ChunkParams(15, ChunkParams.FALLBACK_DELIMITER, 0);

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(2, chunks.size(), "超预算段应各自独立成块");
        assertEquals("\naaaaaaaaaaaaaaaaaaaa", chunks.get(0).text());
        assertEquals("\nbbbbbbbbbbbbbbbbbbbb", chunks.get(1).text());
        assertEquals(21, chunks.get(0).tokens(), "独立块 token 为真实编码数");
        assertEquals(21, chunks.get(1).tokens());
    }

    /**
     * 场景：契约 3（OVER_CAP 合并）——4 段各 12 tokens、cap=30（threshold=30）。
     * <p>新语义「累加后判定封块」（加入前预判）：并入下一单元前，若并入后将超 {@code target}
     * （{@code prev.tokens + unit.tokens > target}）即封当前块、当前单元作新块种子（不进入已封块）。
     * 段 1、2 并入（12+12=24≤30），段 3 使 24+12=36>30 触发封块（块 1=24），段 3 作新种子再并入段 4
     * （12+12=24，块 2=24）。预期：2 个块，各 running-sum 累加 24，消除旧版 36/12 的后置溢出。</p>
     */
    @Test
    void should_mergeWithRunningSumAndBreakAtThreshold_when_chunk_given_smallSegments() {
        // given
        ParsedDocument parsed = parsed("aaaaaaaaaaa\nbbbbbbbbbbb\nccccccccccc\nddddddddddd");
        ChunkParams params = new ChunkParams(30, ChunkParams.FALLBACK_DELIMITER, 0);

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(2, chunks.size(), "并入前预判超 target 即封块");
        assertEquals(24, chunks.get(0).tokens(), "段 1+2 running-sum 累加（12×2），不重新 tokenize");
        assertTrue(chunks.get(0).text().contains("aaaaaaaaaaa"));
        assertTrue(chunks.get(0).text().contains("bbbbbbbbbbb"));
        assertFalse(chunks.get(0).text().contains("ccccccccccc"), "段 3 作为新块种子，不进入已封块");
        assertEquals(24, chunks.get(1).tokens(), "段 3+4 running-sum 累加（12×2）");
        assertTrue(chunks.get(1).text().contains("ccccccccccc"));
        assertTrue(chunks.get(1).text().contains("ddddddddddd"));
    }

    /**
     * 场景：契约 4（overlap 无条件前缀）——cap=20、overlap=30（threshold=14）。
     * 段 1（15 tokens）独立后，段 2 开新块时无条件前置段 1 可见字符尾部
     * {@code len×70%} 起点截取的 30% 前缀。
     * 预期：块 2 以块 1 尾部 5 个可见字符开头。
     */
    @Test
    void should_prependOverlapPrefix_when_chunk_given_overlapPercent() {
        // given
        ParsedDocument parsed = parsed("cccccccccccccc\ndddddddddddddd");
        ChunkParams params = new ChunkParams(20, ChunkParams.FALLBACK_DELIMITER, 30);

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(2, chunks.size());
        assertEquals(15, chunks.get(0).tokens());
        assertTrue(chunks.get(1).text().startsWith("ccccc"), "新块应以块 1 可见字符尾部前缀开头");
        assertTrue(chunks.get(1).text().contains("dddddddddddddd"), "块 2 正文保持完整");
        assertEquals(20, chunks.get(1).tokens());
    }

    /**
     * 场景：默认 512 cap 下多段合并不碎裂。
     * 预期：5 段（各 4 tokens）累计 20 tokens ≤ threshold 512，合并为单块且正文完整。
     */
    @Test
    void should_mergeAllSegmentsWhen_belowDefaultCap_when_chunk_given_defaultParams() {
        // given
        ParsedDocument parsed = parsed("aaa\nbbb\nccc\nddd\neee");
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(1, chunks.size(), "默认 512 cap 下小段应全部合并");
        assertEquals(20, chunks.get(0).tokens());
        assertTrue(chunks.get(0).text().contains("aaa"));
        assertTrue(chunks.get(0).text().contains("eee"));
        assertEquals(1, chunks.get(0).sequence());
    }

    /**
     * 场景：边界空值——全部内容块剥标签后无可见字符。
     * 预期：分块结果为空列表（无任何伪块产出）。
     */
    @Test
    void should_returnEmptyList_when_chunk_given_blankBlocks() {
        // given
        ParsedDocument parsed = new ParsedDocument("h", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "   \n  ", null)));
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertTrue(chunks.isEmpty(), "全白块不得产出任何分块");
    }

    /**
     * 场景：null 模式（等同未配置）按 GENERAL 直通执行。
     * 预期：null 模式与无模式重载结果逐字段一致（GENERAL 直通口径）。
     */
    @Test
    void should_fallbackToGeneral_when_chunk_given_nullMode() {
        // given
        ParsedDocument parsed = parsed("hello world");
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> defaultChunks = service.chunk(parsed, params);
        List<ChunkVO> nullModeChunks = service.chunk(parsed, params, null);

        // then
        assertEquals(defaultChunks.size(), nullModeChunks.size());
        for (int i = 0; i < defaultChunks.size(); i++) {
            assertEquals(defaultChunks.get(i).text(), nullModeChunks.get(i).text());
            assertEquals(defaultChunks.get(i).tokens(), nullModeChunks.get(i).tokens());
        }
    }

    /**
     * 场景：多模态块（图片）在 general 主管线下透传为独立块并中断合并链。
     * 预期：文本块与图片块各自成块，图片块保留原类型与原文（无前置换行）。
     */
    @Test
    void should_passthroughMultimodalBlock_when_chunk_given_imageBlock() {
        // given
        ParsedDocument parsed = new ParsedDocument("h", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "first paragraph", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1))));
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = service.chunk(parsed, params);

        // then
        assertEquals(2, chunks.size(), "多模态块独立成块并中断合并链");
        assertEquals(ContentBlockVO.TYPE_TEXT, chunks.get(0).block().type());
        assertEquals(ContentBlockVO.TYPE_IMAGE, chunks.get(1).block().type());
        assertEquals("<image>img1.jpeg</image>", chunks.get(1).text(), "透传块保留原文");
    }

    /**
     * 场景：纯直通规则 a——pptx 文件选择 GENERAL 模式（旧版扩展名路由会改道 PRESENTATION）。
     * 预期：生效模式仍为 GENERAL，切片与 GENERAL 主管线逐字段一致，不改道。
     */
    @Test
    void should_keepGeneralMode_when_chunkWithDispatch_given_pptxFileName() {
        // given
        ParsedDocument parsed = parsedWithFileName("slides.pptx", "第一章 标题\n幻灯片正文");
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, DocumentChunkMode.GENERAL);
        List<ChunkVO> generalChunks = service.chunk(parsed, params);

        // then
        assertEquals(DocumentChunkMode.GENERAL, outcome.resolvedMode(), "扩展名不得改道，生效模式仍为 GENERAL");
        assertEquals(generalChunks.size(), outcome.chunks().size(), "产物应与 GENERAL 主管线一致");
        for (int i = 0; i < generalChunks.size(); i++) {
            assertEquals(generalChunks.get(i).text(), outcome.chunks().get(i).text());
        }
    }

    /**
     * 场景：纯直通规则 b——GENERAL 模式文档含密集「第N章」标题（旧版组型投票会改道 BOOK）。
     * 预期：不投票改道，生效模式 GENERAL，产物为 GENERAL 分隔符合并块（不带层级标题路径前缀）。
     */
    @Test
    void should_keepGeneralMode_when_chunkWithDispatch_given_denseChapterHeadings() {
        // given
        ParsedDocument parsed = parsed("第一章 总则\n本章说明基础概念与适用范围。\n"
                + "第二章 要点\n本章深入讲解核心要点内容。");
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, DocumentChunkMode.GENERAL);

        // then
        assertEquals(DocumentChunkMode.GENERAL, outcome.resolvedMode(), "密集章标题不得投票改道 BOOK");
        assertFalse(outcome.chunks().isEmpty(), "分派产物不得为空");
        assertEquals(1, outcome.chunks().size(), "默认 512 预算下四段应合并为单块");
        assertTrue(outcome.chunks().get(0).text().contains("第一章 总则\n本章说明基础概念与适用范围。"),
                "GENERAL 保持原文顺序拼接，不做层级标题路径重排");
    }

    /**
     * 场景：纯直通规则 c——六种显式模式逐一传入。
     * 预期：显式模式恒为生效模式（含无标题文档的 BOOK/LAWS，模式不变、改由策略内部降级并 WARN）。
     */
    @Test
    void should_keepEachExplicitMode_when_chunkWithDispatch_given_sixModes() {
        // given
        ParsedDocument parsed = parsed("这是一段没有任何可识别标题结构的普通说明文字");
        ChunkParams params = ChunkParams.defaults();
        List<DocumentChunkMode> modes = List.of(DocumentChunkMode.GENERAL, DocumentChunkMode.QA,
                DocumentChunkMode.BOOK, DocumentChunkMode.LAWS, DocumentChunkMode.TABLE,
                DocumentChunkMode.PRESENTATION, DocumentChunkMode.ONE);

        // when / then：逐个模式直通，生效模式恒等于入参
        for (DocumentChunkMode mode : modes) {
            ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, mode);
            assertEquals(mode, outcome.resolvedMode(), mode + " 模式不得被改写");
            assertFalse(outcome.chunks().isEmpty(), mode + " 模式产物不得为空");
        }
    }

    /**
     * 场景：纯直通规则 d——含标题文本显式选择 QA。
     * 预期：标题存在也不参与投票改派，生效 QA。
     */
    @Test
    void should_notVote_when_chunkWithDispatch_given_explicitQaModeWithHeadings() {
        // given
        ParsedDocument parsed = parsed("第一章 问答集\n问题：什么是分块\t回答：把长文本切成切片");
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, DocumentChunkMode.QA);

        // then
        assertEquals(DocumentChunkMode.QA, outcome.resolvedMode(), "显式 QA 不得被投票改派为 BOOK");
        assertFalse(outcome.chunks().isEmpty(), "分派产物不得为空");
    }

    /**
     * 场景：ONE 模式——文档含 5 个 TEXT 块与
     * 2 个 IMAGE 块，图块穿插于文本块之间。预期：仅产出 1 个整篇文本 chunk + 2 个独立图块 chunk
     * （共 3 个），全局序号 1/2/3 连续递增，图块保持其在文档中的原相对次序，且图块文本不被吞并进整篇块。
     */
    @Test
    void should_splitWholeTextAndPassthroughImages_when_chunk_given_oneModeMixedDoc() {
        // given（TEXT 拼接锚点为首个 TEXT 块 index 0；两图块分别位于原 index 2、5）
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para1", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para2", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para3", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para4", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img2.jpeg</image>", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para5", Map.of("page", 1))));
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = dispatchService.chunk(parsed, params, DocumentChunkMode.ONE);

        // then
        assertEquals(3, chunks.size(), "ONE 模式应产出 1 整篇文本 chunk + 2 独立图块 chunk");
        assertEquals(1, chunks.get(0).sequence());
        assertEquals(2, chunks.get(1).sequence(), "全局序号应跨整篇连续递增");
        assertEquals(3, chunks.get(2).sequence());
        ChunkVO whole = chunks.get(0);
        assertEquals(ContentBlockVO.TYPE_TEXT, whole.block().type(), "整篇块应为 TEXT 类型");
        assertTrue(whole.text().contains("para1"));
        assertTrue(whole.text().contains("para5"), "全部 TEXT 块应并入整篇块");
        assertFalse(whole.text().contains("<image>"), "多模态块文本不得被吞并进整篇块");
        assertEquals("<image>img1.jpeg</image>", chunks.get(1).text(), "图块应按原相对次序归位");
        assertEquals("<image>img2.jpeg</image>", chunks.get(2).text());
    }

    /**
     * 场景：ONE 模式独立透传的图块 chunk 应保留其内容块引用与类型标识，
     * 使摄入 Stage 2 多模态增强管线的 {@code isMultimodal()} 判定能够识别（不丧失增强资格）。
     * 预期：图块 chunk 的 block().type() 为 IMAGE 且 block().isMultimodal() 为 true。
     */
    @Test
    void should_keepImageChunkEligibleForStage2_when_chunk_given_oneModeImageBlock() {
        // given（TEXT-IMAGE-TEXT，图块位于中间原 index 1）
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para1", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para2", Map.of("page", 1))));
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = dispatchService.chunk(parsed, params, DocumentChunkMode.ONE);

        // then
        assertEquals(2, chunks.size(), "应产出 1 整篇文本 chunk + 1 独立图块 chunk");
        ChunkVO imageChunk = chunks.get(1);
        assertEquals(ContentBlockVO.TYPE_IMAGE, imageChunk.block().type(), "图块 chunk 应保留 IMAGE 类型标识");
        assertTrue(imageChunk.block().isMultimodal(), "图块应被 Stage 2 多模态增强管线识别");
    }

    /**
     * 场景：ONE 模式对纯 TEXT 文档的行为不变（回归基线）。
     * 预期：仍产出整篇 1 个 chunk，序号 1，块类型为 TEXT。
     */
    @Test
    void should_keepSingleWholeChunk_when_chunk_given_oneModePureText() {
        // given
        ParsedDocument parsed = parsed("para1\npara2\npara3");
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = dispatchService.chunk(parsed, params, DocumentChunkMode.ONE);

        // then
        assertEquals(1, chunks.size(), "纯文本 ONE 模式应与基线一致产出单 chunk");
        assertEquals(1, chunks.get(0).sequence());
        assertEquals(ContentBlockVO.TYPE_TEXT, chunks.get(0).block().type());
    }

    /**
     * 场景：图文混排前置分流——TEXT 与 IMAGE / TABLE 穿插，
     * 媒体项摘出独立成块不进切分器，文本序列整体走策略分派后两路按源顺序归并。
     * 预期：块数 = 文本片数 + 媒体项数，序号连续且媒体块落在源位置（TEXT、IMAGE、TABLE 依源序）。
     */
    @Test
    void should_mergeMediaIntoSourceOrder_when_chunkWithDispatch_given_mixedDoc() {
        // given（源块序：TEXT[0]、IMAGE[1]、TEXT[2]、TABLE[3]、TEXT[4]）
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para1", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para2", Map.of("page", 2)),
                new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "<table>t1</table>", Map.of("page", 2)),
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para3", Map.of("page", 3))));
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, DocumentChunkMode.GENERAL);

        // then：3 段文本合并为 1 片（锚定源下标 0）+ 2 个媒体独立块，共 3 块按源序归并
        List<ChunkVO> chunks = outcome.chunks();
        assertEquals(3, chunks.size(), "块数应 = 文本片数 + 媒体项数");
        assertEquals(1, chunks.get(0).sequence());
        assertEquals(2, chunks.get(1).sequence());
        assertEquals(3, chunks.get(2).sequence());
        assertEquals(ContentBlockVO.TYPE_TEXT, chunks.get(0).block().type());
        assertTrue(chunks.get(0).text().contains("para1"));
        assertTrue(chunks.get(0).text().contains("para3"));
        assertEquals(ContentBlockVO.TYPE_IMAGE, chunks.get(1).block().type(), "图块应归位于源下标 1");
        assertEquals(ContentBlockVO.TYPE_TABLE, chunks.get(2).block().type(), "表块应归位于源下标 3");
    }

    /**
     * 场景：纯媒体文档（无任何文本块）——文本序列为空，不产出文本切片，模式仅留痕。
     * 预期：块数 = 媒体项数、序号 1..N 连续；未配置模式留痕 GENERAL。
     */
    @Test
    void should_onlyProduceMediaChunks_when_chunkWithDispatch_given_mediaOnlyDoc() {
        // given
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "<equation>e1</equation>", Map.of("page", 2))));
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, null);

        // then
        assertEquals(2, outcome.chunks().size());
        assertEquals(1, outcome.chunks().get(0).sequence());
        assertEquals(2, outcome.chunks().get(1).sequence());
        assertEquals(ContentBlockVO.TYPE_IMAGE, outcome.chunks().get(0).block().type());
        assertEquals(ContentBlockVO.TYPE_EQUATION, outcome.chunks().get(1).block().type());
        assertEquals(DocumentChunkMode.GENERAL, outcome.resolvedMode(), "未配置模式时纯媒体文档留痕 GENERAL");
    }

    /**
     * 场景：纯媒体文档携带显式模式——显式模式原样记录（仅留痕语义）。
     * 预期：resolvedMode 等于显式传入的 ONE，切片全为媒体块。
     */
    @Test
    void should_keepExplicitModeAsTrace_when_chunkWithDispatch_given_mediaOnlyWithExplicitMode() {
        // given
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", Map.of("page", 1))));
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome outcome = dispatchService.chunkWithDispatch(parsed, params, DocumentChunkMode.ONE);

        // then
        assertEquals(1, outcome.chunks().size());
        assertEquals(DocumentChunkMode.ONE, outcome.resolvedMode(), "显式模式应原样留痕");
    }

    /**
     * 场景：媒体块元数据零丢失（meta 原样携带）——含媒体引用键（mediaObjectKey，
     * 桶概念已退役）的图块经分流独立成块后，其内容块引用与 meta 逐项完整保留。
     * 预期：透传块的 block 与 meta 与源块同实例口径，下游 s3_file 提取链路输入不变。
     */
    @Test
    void should_keepMediaMetaIntact_when_chunkWithDispatch_given_mediaBlockWithReferenceKeys() {
        // given
        Map<String, Object> mediaMeta = Map.of(
                "page", 2, "mediaObjectKey", "rag/1/img-1.png");
        ParsedDocument parsed = new ParsedDocument("hash", List.of(
                new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "para1", Map.of("page", 1)),
                new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "<image>img1.jpeg</image>", mediaMeta)));
        ChunkParams params = ChunkParams.defaults();

        // when
        List<ChunkVO> chunks = dispatchService.chunkWithDispatch(parsed, params, null).chunks();

        // then：媒体块独立成块且 meta 原样携带
        assertEquals(2, chunks.size());
        ChunkVO mediaChunk = chunks.get(1);
        assertEquals(mediaMeta, mediaChunk.block().meta(), "媒体引用键必须零丢失");
        assertEquals("rag/1/img-1.png", mediaChunk.block().meta().get("mediaObjectKey"));
    }

    /**
     * 场景：纯文本文档快速路径回归——无媒体块时直通主干与 GENERAL 门面产出完全一致
     * （文件后缀不参与任何判断）。
     * 预期：生效模式 GENERAL，切片与 {@code chunk(parsed, params)} 逐字段相同。
     */
    @Test
    void should_behaveIdenticallyToGeneralPipeline_when_chunkWithDispatch_given_pureTextDoc() {
        // given：pptx 文件名（旧版会据此改道 PRESENTATION）的纯文本文档
        ParsedDocument parsed = parsedWithFileName("slides.pptx", "第一章 标题\n幻灯片正文");
        ChunkParams params = ChunkParams.defaults();

        // when
        ChunkingOutcome dispatched = dispatchService.chunkWithDispatch(parsed, params, null);
        List<ChunkVO> generalChunks = service.chunk(parsed, params);

        // then
        assertEquals(DocumentChunkMode.GENERAL, dispatched.resolvedMode(), "未配置模式等同 GENERAL");
        assertEquals(generalChunks.size(), dispatched.chunks().size());
        for (int i = 0; i < generalChunks.size(); i++) {
            assertEquals(generalChunks.get(i).text(), dispatched.chunks().get(i).text());
        }
        assertFalse(dispatched.chunks().isEmpty());
    }

    /**
     * 构造包含单个文本块的解析结果。
     *
     * @param text 文本块正文
     * @return 解析结果
     */
    private static ParsedDocument parsed(String text) {
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, Map.of("page", 1));
        return new ParsedDocument("hash", List.of(block));
    }

    /**
     * 构造携带文件名的单文本块解析结果（文件名不改道用例输入）。
     *
     * @param fileName 文件名（含扩展名）
     * @param text     文本块正文
     * @return 解析结果
     */
    private static ParsedDocument parsedWithFileName(String fileName, String text) {
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, Map.of("page", 1));
        return new ParsedDocument("hash", List.of(block), fileName);
    }
}