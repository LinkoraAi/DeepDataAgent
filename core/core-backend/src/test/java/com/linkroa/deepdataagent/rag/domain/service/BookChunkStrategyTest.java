package com.linkroa.deepdataagent.rag.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookChunkStrategy#splitDocument} 文档级分层切分单元测试。
 * <p>锁定四项语义：①整篇文本块汇聚投票建树（depth=5）按章聚合，短标题经
 * {@link HierarchyParser#absorbShortBlocks}（阈值 218）吸收合并；②合并块按「首行回查」
 * 溯源到来源内容块（先登记者优先）；③多模态块按原位置整体透传且全局序号连续；
 * ④投票失败时降级为固定 256 token 预算 + 中文句界分隔符的合并切分（策略内 WARN 留痕），不吃
 * {@code params} 的预算与重叠。另附 {@link ChunkStrategy#splitDocument} 接口默认实现
 * 行为验证（general 策略逐块对拍 + 部分支持匿名策略透传），因限定仅新建
 * Book/Laws 两个测试类，故默认实现测试一并落在此处。
 * TokenCounter 使用确定性 stub（{@code text.length()}），纯同包直调，不使用 Mockito。</p>
 * <p>投票口径说明：仅含「第X章」标题的样本在组 0（中文法规）与组 2（中文数字）命中数并列，
 * 按「并列取组号小者」实得组 0，章在组 0 内层级为 2；depth=5 候选越界落正文桶后降级为
 * 最大真实标题层级（章），按章聚合结论不变。</p>
 */
class BookChunkStrategyTest {

    /** 确定性 token 计数 stub：长度即 token 数。 */
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    /** 被测策略实例（无依赖，直接构造）。 */
    private final BookChunkStrategy strategy = new BookChunkStrategy();

    /**
     * 场景：四个文本块跨块分布多章标题——b1 含章一起始，b2 含前章正文与章二标题，
     * b3/b4 为两个仅含标题的短章块（长度均远低于吸收阈值 218）。
     * 预期：depth=5 降级按章聚合；b1 章带两句正文（跨块吸收 b2 的首行正文），
     * 三个无正文短章块被 absorb 合并为一块，共 2 块；溯源分别命中 b1、b2；序号 1、2 连续。
     */
    @Test
    void should_mergeByChapterAndAbsorbShortBlocks_when_splitDocument_given_multiChapterBlocks() {
        // given
        ContentBlockVO b1 = textBlock("第一章 起源\n远古的内容甲。");
        ContentBlockVO b2 = textBlock("中古的内容乙。\n第二章 发展");
        ContentBlockVO b3 = textBlock("第三章 独立");
        ContentBlockVO b4 = textBlock("第四章 尾章");

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(b1, b2, b3, b4), ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(2, chunks.size(), "按章聚合后短章标题块应被 absorb 合并为 2 块");
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertEquals(2, chunks.get(1).sequence().intValue());
        assertEquals("第一章 起源\n远古的内容甲。\n中古的内容乙。", chunks.get(0).text(),
                "第一章应跨块吸收 b2 的正文行");
        assertEquals("第二章 发展\n第三章 独立\n第四章 尾章", chunks.get(1).text(),
                "三个单行短章标题应被吸收合并");
        assertSame(b1, chunks.get(0).block(), "首块首行「第一章 起源」应回查溯源到 b1");
        assertSame(b2, chunks.get(1).block(), "第二块首行「第二章 发展」应回查溯源到 b2");
        assertEquals(chunks.get(0).text().length(), chunks.get(0).tokens().intValue(), "长度 stub 口径下 tokens 与文本等长");
    }

    /**
     * 场景：全文档无任何可投票标题（两个块均为 50 字符句 ×6 / ×4 的长正文）。
     * 预期：组型投票失败走降级出口——固定 256 预算 + 「\n。；！？」分隔（每段 51 token），
     * 10 段合并为 2 块（5+5 各 255 token）；传入 512 预算/30% 重叠的 params 亦不生效；序号连续；
     * 降级发生同时输出策略内 WARN 留痕（纯直通下不再有 dispatch 级降级依据，信息归属策略内部）。
     */
    @Test
    void should_splitSentencesWithFixedBudget_when_splitDocument_given_documentWithoutAnyHeading() {
        // given
        ContentBlockVO b1 = textBlock(("a".repeat(50) + "。").repeat(6));
        ContentBlockVO b2 = textBlock(("b".repeat(50) + "。").repeat(4));
        ChunkParams params = new ChunkParams(512, ChunkParams.FALLBACK_DELIMITER, 30);
        Logger strategyLogger = (Logger) LoggerFactory.getLogger(BookChunkStrategy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        strategyLogger.addAppender(appender);
        List<ChunkVO> chunks;
        try {
            // when
            chunks = strategy.splitDocument(List.of(b1, b2), params, tokenCounter);
        } finally {
            strategyLogger.detachAppender(appender);
        }

        // then
        assertEquals(2, chunks.size(), "降级预算固定 256（512 预算应合并为 1 块，故 2 块证明 params 未生效）");
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertEquals(2, chunks.get(1).sequence().intValue());
        for (ChunkVO chunk : chunks) {
            assertTrue(chunk.tokens() <= ChunkParams.HIERARCHY_FALLBACK_TOKEN_NUM, "降级块不应超过 256 token 预算");
        }
        assertTrue(chunks.get(0).text().contains("a".repeat(50)), "首块应包含按「。」切开的正文段");
        assertSame(b1, chunks.get(0).block(), "降级逐块 buildUnits 应保留来源块溯源");
        List<ILoggingEvent> warns = appender.list.stream()
                .filter(event -> Level.WARN.equals(event.getLevel()))
                .toList();
        assertEquals(1, warns.size(), "降级出口必须输出且仅输出一条策略内 WARN 留痕");
        assertTrue(warns.get(0).getFormattedMessage().contains("降级"), "WARN 文案需说明降级动作");
    }

    /**
     * 场景：两个分章文本块之间夹一个 IMAGE 多模态块。
     * 预期：IMAGE 块不参与投票建树、按原位置整体透传为独立 chunk，共 3 块且序号连续。
     */
    @Test
    void should_passThroughImageBlockInPlace_when_splitDocument_given_imageBlockBetweenChapters() {
        // given
        ContentBlockVO t1 = textBlock("第一章 起源\n远古的内容甲。");
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图片占位说明", null);
        ContentBlockVO t2 = textBlock("第二章 发展\n中古的内容乙。");

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(t1, image, t2), ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(3, chunks.size());
        assertEquals("第一章 起源\n远古的内容甲。", chunks.get(0).text());
        assertEquals("图片占位说明", chunks.get(1).text(), "IMAGE 块应原位置透传为独立 chunk");
        assertEquals(ContentBlockVO.TYPE_IMAGE, chunks.get(1).block().type());
        assertSame(image, chunks.get(1).block());
        assertEquals("第二章 发展\n中古的内容乙。", chunks.get(2).text());
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertEquals(2, chunks.get(1).sequence().intValue());
        assertEquals(3, chunks.get(2).sequence().intValue());
    }

    /**
     * 场景：内容块列表为 null 或空。
     * 预期：覆写实现守卫返回空列表（不返回 null、不抛异常）。
     */
    @Test
    void should_returnEmptyList_when_splitDocument_given_noBlocks() {
        // given & when
        List<ChunkVO> fromNull = strategy.splitDocument(null, ChunkParams.defaults(), tokenCounter);
        List<ChunkVO> fromEmpty = strategy.splitDocument(List.of(), ChunkParams.defaults(), tokenCounter);

        // then
        assertTrue(fromNull.isEmpty());
        assertTrue(fromEmpty.isEmpty());
    }

    /**
     * 场景：接口默认 {@code splitDocument} 行为——general 策略 + 纯文本块。
     * 预期：默认实现与「逐块 split 结果拼接 + 全局重排序号」完全等价（数量、序号、文本、tokens、溯源一致）。
     */
    @Test
    void should_matchPerBlockSplit_when_splitDocument_given_generalStrategyTextBlocks() {
        // given
        GeneralChunkStrategy general = new GeneralChunkStrategy();
        ContentBlockVO b1 = textBlock("第一段内容。\n第二段内容。");
        ContentBlockVO b2 = textBlock("第三段内容。");
        ChunkParams params = ChunkParams.defaults();
        List<ChunkVO> expected = new ArrayList<>();
        for (ContentBlockVO block : List.of(b1, b2)) {
            expected.addAll(general.split(block, params, tokenCounter));
        }

        // when
        List<ChunkVO> actual = general.splitDocument(List.of(b1, b2), params, tokenCounter);

        // then
        assertEquals(expected.size(), actual.size(), "默认文档级实现应与逐块 split 数量一致");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(i + 1, actual.get(i).sequence().intValue(), "全局序号应从 1 连续重排");
            assertEquals(expected.get(i).text(), actual.get(i).text());
            assertEquals(expected.get(i).tokens(), actual.get(i).tokens());
            assertSame(expected.get(i).block(), actual.get(i).block());
        }
    }

    /**
     * 场景：接口默认 {@code splitDocument} 行为——匿名「部分支持」策略（supports 仅 TEXT、split 恒返回单块）
     * 处理「文本-图片-文本」三块，且 params 传 null（策略不消费参数）。
     * 预期：支持块走 split、不支持块整体透传，序号 1~3 连续、溯源保留；null/空列表守卫返回空列表。
     */
    @Test
    void should_passThroughUnsupportedBlocks_when_splitDocument_given_partialSupportAnonymousStrategy() {
        // given
        ChunkStrategy partial = new ChunkStrategy() {
            @Override
            public String method() {
                return "partial-fake";
            }

            @Override
            public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
                return List.of(new ChunkVO(1, block.text(), counter.count(block.text()), block));
            }

            @Override
            public boolean supports(ContentBlockVO block) {
                return ContentBlockVO.TYPE_TEXT.equals(block.type());
            }
        };
        ContentBlockVO t1 = textBlock("文本块甲");
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图片占位乙", null);
        ContentBlockVO t2 = textBlock("文本块丙");

        // when
        List<ChunkVO> chunks = partial.splitDocument(List.of(t1, image, t2), null, tokenCounter);

        // then
        assertEquals(3, chunks.size());
        assertEquals(List.of(1, 2, 3),
                chunks.stream().map(ChunkVO::sequence).toList());
        assertEquals(List.of("文本块甲", "图片占位乙", "文本块丙"),
                chunks.stream().map(ChunkVO::text).toList());
        assertSame(t1, chunks.get(0).block());
        assertSame(image, chunks.get(1).block(), "不支持块应整块透传且保持原位置");
        assertSame(t2, chunks.get(2).block());
        assertNull(image.meta(), "透传不应改写来源块");
        assertTrue(partial.splitDocument(null, null, tokenCounter).isEmpty(), "null 入参守卫应返回空列表");
        assertTrue(partial.splitDocument(List.of(), null, tokenCounter).isEmpty(), "空列表守卫应返回空列表");
    }

    /**
     * 场景：文档首部为「目录」标记块＋三条「章标题＋点线页码」目录条目块，随后才是正文两章（各带一句正文）。
     * 预期：投票与建树前目录区被 {@link HierarchyParser#removeContentsTable} 剔除——层级块仅由正文
     * 两章构成（2 块），任何输出块不得含点线页码目录行或「目录」标记行。
     */
    @Test
    void should_ignoreTocRegion_when_splitDocument_given_bookWithFrontToc() {
        // given
        ContentBlockVO b1 = textBlock("目录");
        ContentBlockVO b2 = textBlock("第一章 起源 ……… 1\n第二章 发展 ……… 9\n第三章 遗产 ……… 20");
        ContentBlockVO b3 = textBlock("第一章 起源\n远古的内容甲。");
        ContentBlockVO b4 = textBlock("第二章 发展\n中古的内容乙。");

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(b1, b2, b3, b4), ChunkParams.defaults(),
                tokenCounter);

        // then
        assertEquals(2, chunks.size(), "目录条目不得作为假标题参与层级树构建");
        assertEquals("第一章 起源\n远古的内容甲。", chunks.get(0).text());
        assertEquals("第二章 发展\n中古的内容乙。", chunks.get(1).text());
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.text().contains("……")
                        || chunk.text().contains("目录")),
                "剔除的目录行不得出现在任何层级块中");
    }

    /**
     * 构造文本内容块。
     *
     * @param text 块文本
     * @return TEXT 类型内容块
     */
    private static ContentBlockVO textBlock(String text) {
        return new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, null);
    }
}
