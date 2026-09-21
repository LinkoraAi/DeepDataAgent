package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LawsChunkStrategy#splitDocument} 文档级分层切分单元测试（
 * 的 laws 口径）。
 * <p>锁定四项语义：①整篇文本块汇聚投票建树（depth=2，取 distinct 标题层级第 2 小者即
 * 「条」级为目标，块文本携带「章」标题前缀），且 laws 口径不做短块吸收
 * （对照 book：同数据形态下短标题块不会被 absorb 合并）；②合并块按「首行回查」溯源到
 * 来源内容块（先登记者优先，本用例中「第二条」合并块首行为「第一章总则」故归属 l1）；
 * ③多模态块不参与投票建树、按原位置整体透传且全局序号连续；④投票失败时降级为固定
 * 256 token 预算 + 中文句界分隔符的合并切分（策略内 WARN 留痕），不吃 {@code params} 的预算与重叠。
 * TokenCounter 使用确定性 stub（{@code text.length()}），纯同包直调，不使用 Mockito。</p>
 * <p>投票口径说明：样本含 3 行「第X章」与 2 行「第X条」，组 0（中文法规）命中 5 大于
 * 组 2（中文数字）命中 3，实得组 0；组 0 内章为层级 2、条为层级 4，distinct 层级升序
 * {@code [2,4]}，depth=2 取第 2 小者即 4（条）。</p>
 */
class LawsChunkStrategyTest {

    /** 确定性 token 计数 stub：长度即 token 数。 */
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    /** 被测策略实例（无依赖，直接构造）。 */
    private final LawsChunkStrategy strategy = new LawsChunkStrategy();

    /**
     * 场景：四个文本块构成两部短「章」与两个「条」——l1 含章一标题与第一条，l2 仅第二条一行，
     * l3/l4 为两个仅含标题的短章块。
     * 预期：投票得组 0，distinct 层级 {2,4} 按 depth=2 取第 2 小者 4（条）聚合并携带章标题前缀；
     * 不做短块吸收故共 4 块；溯源：两个条块首行均回查命中 l1（先登记者优先），
     * 章二、章三分别命中 l3、l4；序号 1~4 连续。
     */
    @Test
    void should_splitByArticleWithoutAbsorb_when_splitDocument_given_multiChapterLawBlocks() {
        // given
        ContentBlockVO l1 = textBlock("第一章 总则\n第一条 本法调整平等主体间的人身关系。");
        ContentBlockVO l2 = textBlock("第二条 民事活动应当遵循自愿原则。");
        ContentBlockVO l3 = textBlock("第二章 附则");
        ContentBlockVO l4 = textBlock("第三章 施行");

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(l1, l2, l3, l4), ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(4, chunks.size(), "laws 口径不做短块吸收，两个短章标题块应独立成块（对照 book 会被 absorb）");
        assertEquals("第一章 总则\n第一条 本法调整平等主体间的人身关系。", chunks.get(0).text(),
                "条级块应携带章标题路径前缀");
        assertEquals("第一章 总则\n第二条 民事活动应当遵循自愿原则。", chunks.get(1).text(),
                "第二条应独立成块并按条级目标层级切分");
        assertEquals("第二章 附则", chunks.get(2).text());
        assertEquals("第三章 施行", chunks.get(3).text());
        assertSame(l1, chunks.get(0).block(), "首块首行「第一章 总则」应回查溯源到 l1");
        assertSame(l1, chunks.get(1).block(), "第二块首行同为「第一章 总则」，先登记者优先故溯源到 l1");
        assertSame(l3, chunks.get(2).block());
        assertSame(l4, chunks.get(3).block());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).sequence().intValue(), "全局序号应从 1 连续");
            assertEquals(chunks.get(i).text().length(), chunks.get(i).tokens().intValue(),
                    "长度 stub 口径下 tokens 应与文本等长");
        }
    }

    /**
     * 场景：全文档无任何可投票标题（两个块均为 50 字符句 ×6 / ×4 的长正文，无「第X章/条」行）。
     * 预期：组型投票失败走降级出口——固定 256 预算 + 「\n。；！？」分隔（每段 51 token），
     * 10 段合并为 2 块（5+5 各 255 token）；传入 512 预算/30% 重叠的 params 亦不生效；
     * 序号连续且降级逐块 buildUnits 保留来源块溯源。
     */
    @Test
    void should_splitSentencesWithFixedBudget_when_splitDocument_given_documentWithoutAnyHeading() {
        // given
        ContentBlockVO b1 = textBlock(("x".repeat(50) + "。").repeat(6));
        ContentBlockVO b2 = textBlock(("y".repeat(50) + "。").repeat(4));
        ChunkParams params = new ChunkParams(512, ChunkParams.FALLBACK_DELIMITER, 30);

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(b1, b2), params, tokenCounter);

        // then
        assertEquals(2, chunks.size(), "降级预算固定 256（512 预算只会合并为 1 块，故 2 块证明 params 未生效）");
        assertEquals(1, chunks.get(0).sequence().intValue());
        assertEquals(2, chunks.get(1).sequence().intValue());
        for (ChunkVO chunk : chunks) {
            assertTrue(chunk.tokens() <= ChunkParams.HIERARCHY_FALLBACK_TOKEN_NUM, "降级块不应超过 256 token 预算");
        }
        assertTrue(chunks.get(0).text().contains("x".repeat(50)), "首块应包含按「。」切开的正文段");
        assertSame(b1, chunks.get(0).block(), "降级逐块 buildUnits 应保留来源块溯源");
    }

    /**
     * 场景：法条文本块之间夹一个 IMAGE 多模态块（l1、图片、l2、l3、l4）。
     * 预期：图片块不参与投票建树、按原位置整体透传为独立 chunk（第 3 块），
     * 其余条级/章级块归属与纯文本场景一致，共 5 块且序号 1~5 连续。
     */
    @Test
    void should_passThroughImageBlockInPlace_when_splitDocument_given_imageBlockBetweenArticles() {
        // given
        ContentBlockVO l1 = textBlock("第一章 总则\n第一条 本法调整平等主体间的人身关系。");
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图片占位说明", null);
        ContentBlockVO l2 = textBlock("第二条 民事活动应当遵循自愿原则。");
        ContentBlockVO l3 = textBlock("第二章 附则");
        ContentBlockVO l4 = textBlock("第三章 施行");

        // when
        List<ChunkVO> chunks = strategy.splitDocument(List.of(l1, image, l2, l3, l4),
                ChunkParams.defaults(), tokenCounter);

        // then
        assertEquals(5, chunks.size());
        assertEquals("第一章 总则\n第一条 本法调整平等主体间的人身关系。", chunks.get(0).text());
        assertEquals("第一章 总则\n第二条 民事活动应当遵循自愿原则。", chunks.get(1).text());
        assertEquals("图片占位说明", chunks.get(2).text(), "IMAGE 块应原位置透传为独立 chunk");
        assertEquals(ContentBlockVO.TYPE_IMAGE, chunks.get(2).block().type());
        assertSame(image, chunks.get(2).block());
        assertEquals("第二章 附则", chunks.get(3).text());
        assertEquals("第三章 施行", chunks.get(4).text());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).sequence().intValue(), "全局序号应从 1 连续");
        }
        assertSame(l1, chunks.get(1).block(), "「第二条」合并块首行回查命中章一标题，应溯源到 l1 而非图片后块");
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
     * 构造文本内容块。
     *
     * @param text 块文本
     * @return TEXT 类型内容块
     */
    private static ContentBlockVO textBlock(String text) {
        return new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, null);
    }
}
