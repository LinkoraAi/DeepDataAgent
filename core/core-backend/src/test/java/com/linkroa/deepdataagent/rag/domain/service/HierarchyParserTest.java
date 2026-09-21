package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.service.HierarchyParser.LevelLine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HierarchyParser} 组型投票与层级标注单元测试。
 * <p>覆盖：① {@code BULLET_PATTERN} 五组各至少一个代表行投票命中正确组号；② {@code NOT_BULLET}
 * 行（"0 开头"、"12  个"、"1.2.3中"）被排除不参与投票；③ 全不命中 / null / 空入参返回 -1；
 * ④ 并列最多时取组号较小者（ragflow {@code max(range, key)} 语义）；⑤ {@code mostLevel}
 * 频率统计与并列取输入序先出现者；⑥ {@code detectLevelsInGroup} 的 1-based 组内序号、
 * {@code bull=-1} 全正文；⑦ {@code treeMerge} 目标层级候选落正文桶时降一级，且既有建树 /
 * DFS 标题路径前缀行为不回归；⑧ {@code absorbShortBlocks} 的 218 阈值吸收不回归。</p>
 * <p>纯静态算法直测，无外部依赖，不使用 Mockito；{@link TokenCounter} 使用确定性 stub
 * （{@code text.length()}），使 token 与字符长度一一对应，便于逐字符断言。</p>
 */
class HierarchyParserTest {

    /** 确定性 token 计数 stub：剥标签后长度即 token 数。 */
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    // ==================== bulletsCategory：五组代表行投票 ====================

    /**
     * 场景：中文法规类行（第一编 / 第一章 / 第三节 / 第二条）各一行。
     * 预期：组 0 命中 4 次为最多，返回组号 0（组 2 同时命中「第一章 / 第三节」共 2 次但不敌）。
     */
    @Test
    void should_returnGroupZero_when_bulletsCategory_given_chineseLawLines() {
        // given
        List<String> sections = List.of("第一编 总则", "第一章 基本规定", "第三节 法律责任", "第二条 适用范围");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(0, bull, "中文法规类应投给组 0");
    }

    /**
     * 场景：数字层级类行（{@code 1.} / {@code 1.2} / {@code 1.2.3} / {@code 2、}）各一行。
     * 预期：仅组 1 命中，返回组号 1。
     */
    @Test
    void should_returnGroupOne_when_bulletsCategory_given_digitHierarchyLines() {
        // given
        List<String> sections = List.of("1. 概述", "1.2 方法", "1.2.3 细节", "2、数据分析");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(1, bull, "数字层级类应投给组 1");
    }

    /**
     * 场景：中文数字类行（{@code 一、} / {@code 二、} / {@code （一）}）各一行。
     * 预期：组 2 命中 3 次多于组 0 的 1 次（仅 {@code （一）} 同行命中组 0），返回组号 2。
     */
    @Test
    void should_returnGroupTwo_when_bulletsCategory_given_chineseDigitLines() {
        // given
        List<String> sections = List.of("一、研究背景", "二、研究方法", "（一）文献综述");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(2, bull, "中文数字类应投给组 2");
    }

    /**
     * 场景：英文法规类行（PART / Chapter / Section / Article）各一行。
     * 预期：仅组 3 命中，返回组号 3。
     */
    @Test
    void should_returnGroupThree_when_bulletsCategory_given_englishLawLines() {
        // given
        List<String> sections = List.of("PART TWO Overview", "Chapter IV Liability",
                "Section 3 Notice", "Article 1 Scope");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(3, bull, "英文法规类应投给组 3");
    }

    /**
     * 场景：Markdown 行（{@code #} / {@code ##} / {@code ###}）各一行。
     * 预期：仅组 4 命中，返回组号 4。
     */
    @Test
    void should_returnGroupFour_when_bulletsCategory_given_markdownLines() {
        // given
        List<String> sections = List.of("# 一级标题", "## 二级标题", "### 三级标题");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(4, bull, "Markdown 应投给组 4");
    }

    /**
     * 场景：sections 元素内含坐标标签且一段多行（两行标题被 {@code \n} 拼在一个元素里）。
     * 预期：标签对判定不可见、多行被拆开逐行投票，仍返回组 0。
     */
    @Test
    void should_stripTagsAndSplitLines_when_bulletsCategory_given_taggedMultiLineSection() {
        // given
        List<String> sections = List.of("@@1\t10\t20\t30\t40##第一章 总则",
                "第二条 主体资格\n第三条 民事责任");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(0, bull, "剥标签拆行后中文法规组应命中最多");
    }

    // ==================== bulletsCategory：NOT_BULLET 与零命中 ====================

    /**
     * 场景：三行均为 {@code NOT_BULLET} 守卫行（"0 开头"、"12  个案例分析"、"1.2.3中的规定"）。
     * 预期：三行本可命中组 1，被守卫排除后全组零命中，返回 -1（调用方走降级出口）。
     */
    @Test
    void should_returnMinusOne_when_bulletsCategory_given_notBulletLinesOnly() {
        // given
        List<String> sections = List.of("0 开头", "12  个案例分析", "1.2.3中的规定");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(-1, bull, "NOT_BULLET 行不得参与投票");
    }

    /**
     * 场景：纯叙述文本（不含任何编号形态）与空行 / 空白元素混排。
     * 预期：全组零命中返回 -1。
     */
    @Test
    void should_returnMinusOne_when_bulletsCategory_given_plainNarrativeText() {
        // given
        List<String> sections = List.of("本公司本年度营业收入较上年增长明显。", "", "   ", "\n");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(-1, bull, "无标题形态文本应返回 -1");
    }

    /**
     * 场景：入参分别为 null、空列表。
     * 预期：均返回 -1，不抛异常。
     */
    @Test
    void should_returnMinusOne_when_bulletsCategory_given_nullOrEmptySections() {
        // given / when / then
        assertEquals(-1, HierarchyParser.bulletsCategory(null), "null 入参应返回 -1");
        assertEquals(-1, HierarchyParser.bulletsCategory(List.of()), "空列表应返回 -1");
    }

    /**
     * 场景：单行「第一章总则」同时命中组 0 与组 2，两组各 1 次并列。
     * 预期：并列时取组号较小者（等价 Python {@code max(range, key=hits)}），返回 0。
     */
    @Test
    void should_returnSmallerGroupIndex_when_bulletsCategory_given_tieHits() {
        // given
        List<String> sections = List.of("第一章 总则");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(0, bull, "并列最多时返回组号较小者");
    }

    /**
     * 场景：组 0 命中 1 次、组 2 命中 3 次（一行可同时被两组各加一次）。
     * 预期：返回命中数最大的组号 2，不因组号较大而被小值组盖过。
     */
    @Test
    void should_returnMajorityGroup_when_bulletsCategory_given_groupTwoOutnumberingGroupZero() {
        // given
        List<String> sections = List.of("第一章 总则", "二、适用范围", "三、法律责任");

        // when
        int bull = HierarchyParser.bulletsCategory(sections);

        // then
        assertEquals(2, bull, "组 2 命中次数最多应胜出");
    }

    // ==================== mostLevel ====================

    /**
     * 场景：层级序列 {@code 1,2,2,3,3,3}，上限 5。
     * 预期：出现次数最多的层级 3 被返回。
     */
    @Test
    void should_returnMostFrequentLevel_when_mostLevel_given_repeatedLevels() {
        // given
        List<Integer> levels = List.of(1, 2, 2, 3, 3, 3);

        // when
        int most = HierarchyParser.mostLevel(levels, 5);

        // then
        assertEquals(3, most, "层级 3 出现三次最多");
    }

    /**
     * 场景：层级序列 {@code 1,1,4,4,4}，上限 2。
     * 预期：超限层级 4 不参与统计（含 {@link HierarchyParser#BODY_LEVEL} 同理），返回层级 1。
     */
    @Test
    void should_ignoreOversizedLevels_when_mostLevel_given_levelsBeyondBulletsSize() {
        // given
        List<Integer> levels = List.of(1, 1, 4, 4, 4, HierarchyParser.BODY_LEVEL);

        // when
        int most = HierarchyParser.mostLevel(levels, 2);

        // then
        assertEquals(1, most, "大于上限的层级与正文桶不参与频率统计");
    }

    /**
     * 场景：全部层级都超过上限 / 入参为 null / 空列表。
     * 预期：无候选时统一返回 0。
     */
    @Test
    void should_returnZero_when_mostLevel_given_noCandidateLevels() {
        // given / when / then
        assertEquals(0, HierarchyParser.mostLevel(List.of(5, 6), 3), "全部超限返回 0");
        assertEquals(0, HierarchyParser.mostLevel(null, 5), "null 入参返回 0");
        assertEquals(0, HierarchyParser.mostLevel(List.of(), 5), "空列表返回 0");
    }

    /**
     * 场景：层级 2 与层级 3 各出现两次并列，输入序中 2 先出现。
     * 预期：并列时取输入序先遇到者，返回 2（序列含 null 元素时按无效值跳过）。
     */
    @Test
    void should_returnFirstAppearedLevel_when_mostLevel_given_tieCounts() {
        // given
        List<Integer> levels = Arrays.asList(2, 2, null, 3, 3);

        // when
        int most = HierarchyParser.mostLevel(levels, 5);

        // then
        assertEquals(2, most, "并列时返回输入序先出现的层级");
    }

    // ==================== detectLevelsInGroup ====================

    /**
     * 场景：组 0（中文法规）下标注「第一编 / 第一章 / 第二节 / 第三条」加一行正文。
     * 预期：层级为组内首中模式的 1-based 序号（1/2/3/4），未命中行为 {@code BODY_LEVEL}。
     */
    @Test
    void should_labelOneBasedLevelsInGroup_when_detectLevelsInGroup_given_lawGroup() {
        // given
        List<String> texts = List.of("第一编 总则", "第一章 基本规定", "第二节 适用范围",
                "第三条 主体资格", "本条所称法人是指依法成立的组织。");

        // when
        List<LevelLine> lines = HierarchyParser.detectLevelsInGroup(texts, 0);

        // then
        assertEquals(5, lines.size(), "非空行逐行输出，顺序与输入一致");
        assertEquals(1, lines.get(0).level());
        assertEquals(2, lines.get(1).level());
        assertEquals(3, lines.get(2).level());
        assertEquals(4, lines.get(3).level());
        assertEquals(HierarchyParser.BODY_LEVEL, lines.get(4).level(), "组内全未命中应为正文层级");
        assertTrue(HierarchyParser.hasHeading(lines), "存在标题行");
    }

    /**
     * 场景：组 4（Markdown）下标注 {@code #} / {@code ##} 与一行正文。
     * 预期：层级依次为 1、2、{@code BODY_LEVEL}（同一行为按组内序号取层级，不做绝对层级映射）。
     */
    @Test
    void should_labelMarkdownLevels_when_detectLevelsInGroup_given_markdownGroup() {
        // given
        List<String> texts = List.of("# 标题", "## 子标题", "这里是一段正文说明。");

        // when
        List<LevelLine> lines = HierarchyParser.detectLevelsInGroup(texts, 4);

        // then
        assertEquals(3, lines.size());
        assertEquals(1, lines.get(0).level());
        assertEquals(2, lines.get(1).level());
        assertEquals(HierarchyParser.BODY_LEVEL, lines.get(2).level());
    }

    /**
     * 场景：组号为 -1（{@code bulletsCategory} 未识别出组型）与组号越界（99）。
     * 预期：两种情况下所有行均返回 {@code BODY_LEVEL}，不抛数组越界异常。
     */
    @Test
    void should_returnAllBodyLevel_when_detectLevelsInGroup_given_invalidBulletIndex() {
        // given
        List<String> texts = List.of("第一章 总则", "第二条 内容说明");

        // when
        List<LevelLine> negative = HierarchyParser.detectLevelsInGroup(texts, -1);
        List<LevelLine> outOfRange = HierarchyParser.detectLevelsInGroup(texts, 99);

        // then
        assertEquals(HierarchyParser.BODY_LEVEL, negative.get(0).level(), "bull<0 应全为正文");
        assertEquals(HierarchyParser.BODY_LEVEL, negative.get(1).level());
        assertEquals(HierarchyParser.BODY_LEVEL, outOfRange.get(0).level(), "bull 越界应全为正文");
        assertFalse(HierarchyParser.hasHeading(outOfRange), "全正文时不存在标题");
    }

    /**
     * 场景：带坐标标签的标题行与正文混排（标签在行首）。
     * 预期：判定基于剥标签后的可见文本，但 {@link LevelLine#text()} 保留含标签的原始行。
     */
    @Test
    void should_keepOriginalTaggedText_when_detectLevelsInGroup_given_taggedHeading() {
        // given
        String tagged = "@@12\t100\t200\t300\t400##第一章 总则";

        // when
        List<LevelLine> lines = HierarchyParser.detectLevelsInGroup(List.of(tagged), 0);

        // then
        assertEquals(1, lines.size());
        assertEquals(2, lines.get(0).level(), "剥标签后应命中组 0 的第 2 条模式");
        assertEquals(tagged, lines.get(0).text(), "原始行文本（含坐标标签）须保留");
    }

    /**
     * 场景：组 1 待标注序列混有纯数字页码、超长空白行与 {@code NOT_BULLET} 守卫行。
     * 预期：页码与空白行被过滤丢弃，守卫行按 {@code BODY_LEVEL} 输出，合法标题正常标注。
     */
    @Test
    void should_filterPageNumberAndGuardedLines_when_detectLevelsInGroup_given_mixedNoise() {
        // given
        List<String> texts = List.of("第1章 概述", "123", "  ", "1.2.3中的规定");

        // when
        List<LevelLine> lines = HierarchyParser.detectLevelsInGroup(texts, 1);

        // then
        assertEquals(2, lines.size(), "纯数字页码与空白行不入结果");
        assertEquals(1, lines.get(0).level(), "第N章 命中组 1 首条模式");
        assertEquals(HierarchyParser.BODY_LEVEL, lines.get(1).level(), "NOT_BULLET 行按正文处理");
    }

    /**
     * 场景：入参为 null 与空列表。
     * 预期：返回空结果列表，不抛异常。
     */
    @Test
    void should_returnEmptyList_when_detectLevelsInGroup_given_nullOrEmptyTexts() {
        // given / when / then
        assertTrue(HierarchyParser.detectLevelsInGroup(null, 0).isEmpty(), "null 入参返回空列表");
        assertTrue(HierarchyParser.detectLevelsInGroup(List.of(), 0).isEmpty(), "空列表返回空列表");
    }

    // ==================== treeMerge：目标层级与降级 ====================

    /**
     * 场景：仅存在层级 2 的标题，{@code depth=5}（distinct 标题层数 < depth，候选落到末位正文桶）。
     * 预期：降一级取最大真实标题层级 2 建树，每块为「标题 + 其下正文」共两块。
     */
    @Test
    void should_downgradeToMaxHeadingLevel_when_treeMerge_given_depthExceedingDistinctLevels() {
        // given
        List<LevelLine> lines = List.of(
                new LevelLine(2, "第二章 概述"), new LevelLine(HierarchyParser.BODY_LEVEL, "正文甲"),
                new LevelLine(2, "第三章 详述"), new LevelLine(HierarchyParser.BODY_LEVEL, "正文乙"));

        // when
        List<String> blocks = HierarchyParser.treeMerge(lines, HierarchyParser.BOOK_DEPTH);

        // then
        assertEquals(2, blocks.size(), "降级到层级 2 后两个章标题各成一块");
        assertEquals("第二章 概述\n正文甲", blocks.get(0));
        assertEquals("第三章 详述\n正文乙", blocks.get(1));
    }

    /**
     * 场景：层级 1/2/3 各一条标题加正文，{@code depth=5}（候选第 5 小为正文桶哨兵）。
     * 预期：降一级取层级 3 建树，DFS 输出带完整标题路径前缀的单块。
     */
    @Test
    void should_downgradeToHeadingLevel_when_treeMerge_given_distinctLevelsFewerThanDepth() {
        // given
        List<LevelLine> lines = List.of(
                new LevelLine(1, "第一篇 总论"), new LevelLine(2, "第一章 背景"),
                new LevelLine(3, "第三条 规定"), new LevelLine(HierarchyParser.BODY_LEVEL, "内容若干"));

        // when
        List<String> blocks = HierarchyParser.treeMerge(lines, HierarchyParser.BOOK_DEPTH);

        // then
        assertEquals(1, blocks.size(), "叶子标题下正文并入后仅产出一块");
        assertEquals("第一篇 总论\n第一章 背景\n第三条 规定\n内容若干", blocks.get(0),
                "DFS 须携带标题路径前缀");
    }

    /**
     * 场景：层级 1/2/3 均存在且 {@code depth=2}（laws 口径，候选充足不触发降级）。
     * 预期：目标层级取第 2 小的 2，条（level 3）与正文并入所属章，输出两块。
     */
    @Test
    void should_useSecondSmallestLevel_when_treeMerge_given_lawsDepthWithEnoughLevels() {
        // given
        List<LevelLine> lines = List.of(
                new LevelLine(1, "第一章 总则"), new LevelLine(2, "第一节 一般规定"),
                new LevelLine(3, "第一条 甲"), new LevelLine(HierarchyParser.BODY_LEVEL, "内容A"),
                new LevelLine(2, "第二节 特别规定"), new LevelLine(3, "第二条 乙"),
                new LevelLine(HierarchyParser.BODY_LEVEL, "内容B"));

        // when
        List<String> blocks = HierarchyParser.treeMerge(lines, HierarchyParser.LAWS_DEPTH);

        // then
        assertEquals(2, blocks.size(), "depth=2 时按节切块");
        assertEquals("第一章 总则\n第一节 一般规定\n第一条 甲\n内容A", blocks.get(0));
        assertEquals("第一章 总则\n第二节 特别规定\n第二条 乙\n内容B", blocks.get(1));
    }

    /**
     * 场景：所有行均为正文（不存在任何标题层级）。
     * 预期：整篇退化为单块（兜底分支），不抛取层级越界异常。
     */
    @Test
    void should_returnSingleWholeDocumentBlock_when_treeMerge_given_noHeadingLines() {
        // given
        List<LevelLine> lines = List.of(
                new LevelLine(HierarchyParser.BODY_LEVEL, "段落一"),
                new LevelLine(HierarchyParser.BODY_LEVEL, "段落二"));

        // when
        List<String> blocks = HierarchyParser.treeMerge(lines, HierarchyParser.LAWS_DEPTH);

        // then
        assertEquals(1, blocks.size(), "无标题时整篇一个块");
        assertEquals("段落一\n段落二", blocks.get(0));
    }

    // ==================== absorbShortBlocks：218 阈值不回归 ====================

    /**
     * 场景：两个单行短块（2 token）后接一个多行块。
     * 预期：短块在 218 预算内并入上一块，多行块独立成块，最终两块。
     */
    @Test
    void should_absorbShortSingleLineBlocks_when_absorbShortBlocks_given_underThreshold() {
        // given
        List<String> blocks = List.of("aa", "bb", "cc\nbb");

        // when
        List<String> absorbed = HierarchyParser.absorbShortBlocks(blocks, tokenCounter);

        // then
        assertEquals(2, absorbed.size());
        assertEquals("aa\nbb", absorbed.get(0));
        assertEquals("cc\nbb", absorbed.get(1));
    }

    /**
     * 场景：首个单行块自身即超 218 预算（220 字符），后接两个单行小块。
     * 预期：超预算块独立成块，随后的小块重新计预算并互相吸收。
     */
    @Test
    void should_keepOversizedBlockSeparate_when_absorbShortBlocks_given_budgetExceeded() {
        // given
        String oversized = "x".repeat(220);
        List<String> blocks = List.of(oversized, "y", "z");

        // when
        List<String> absorbed = HierarchyParser.absorbShortBlocks(blocks, tokenCounter);

        // then
        assertEquals(2, absorbed.size(), "超预算块独立，y/z 归入新组");
        assertEquals(oversized, absorbed.get(0));
        assertEquals("y\nz", absorbed.get(1));
    }

    /**
     * 场景：多行块（预算记满 218）后接单行小块。
     * 预期：多行块逼后续单元素块独立成块，开头的空组被丢弃。
     */
    @Test
    void should_forceNextSingleLineBlock_when_absorbShortBlocks_given_multiLinePreceding() {
        // given
        List<String> blocks = List.of("a\nb", "c");

        // when
        List<String> absorbed = HierarchyParser.absorbShortBlocks(blocks, tokenCounter);

        // then
        assertEquals(2, absorbed.size(), "首个空组不入结果，c 独立成块");
        assertEquals("a\nb", absorbed.get(0));
        assertEquals("c", absorbed.get(1));
    }

    /**
     * 场景：单行块含坐标标签（剥标签后 2 token，累计 216 &lt; 218）。
     * 预期：token 计数以剥标签后的可见文本为准，该块被吸收进上一块。
     */
    @Test
    void should_countTokensAfterStrippingTags_when_absorbShortBlocks_given_taggedTailBlock() {
        // given
        String tagged = "@@1\t22\t33\t44\t55##ab";
        List<String> blocks = List.of("k".repeat(214), tagged);

        // when
        List<String> absorbed = HierarchyParser.absorbShortBlocks(blocks, tokenCounter);

        // then
        assertEquals(1, absorbed.size(), "标签不计入 token，216 < 218 应吸收");
        assertEquals("k".repeat(214) + "\n" + tagged, absorbed.get(0));
    }

    // ==================== 既有 detectLevels 行为不回归 ====================

    /**
     * 场景：既有单行直判入口（非组内标注）处理裸标题、正文与纯数字页码。
     * 预期：绝对层级口径不变（章→2、{@code 1.2.3}→3、正文→{@code BODY_LEVEL}），页码行被过滤。
     */
    @Test
    void should_preserveAbsoluteLevelDetection_when_detectLevels_given_bareHeadingsAndPageNumber() {
        // given
        String text = "第一章\n正文内容若干\n1.2.3\n123";

        // when
        List<LevelLine> lines = HierarchyParser.detectLevels(text);

        // then
        assertEquals(3, lines.size(), "纯数字页码行不入结果");
        assertEquals(2, lines.get(0).level(), "章为绝对层级 2");
        assertEquals(HierarchyParser.BODY_LEVEL, lines.get(1).level());
        assertEquals(3, lines.get(2).level(), "1.2.3 为绝对层级 3");
        assertEquals("第一章", lines.get(0).text());
        assertTrue(HierarchyParser.hasHeading(lines));
    }

    // ==================== removeContentsTable：目录前置清洗 ====================

    /**
     * 场景：以「目录」标记行起始、其后连续三段「章标题＋点线页码」条目，再进正文。
     * 预期：标记行与目录条目区整区剔除；正文真实标题「第一章 总则」与长正文逐字不受影响。
     */
    @Test
    void should_removeMarkedRegionAndKeepBody_when_removeContentsTable_given_markerThenTocEntries() {
        // given
        String body = "本章为正文内容说明，长度需要超过六十个可见字符以确保其不被误判为目录候选短行，继续补充内容。";
        List<String> sections = List.of("目录",
                "第一章 总则 ……… 3\n第二章 要点 ……… 12\n第三章 实践 ……… 25",
                "第一章 总则", body);

        // when
        List<String> cleaned = HierarchyParser.removeContentsTable(sections);

        // then
        assertEquals(List.of("第一章 总则", body), cleaned, "目录标记与条目区应被剔除，正文原样保留");
    }

    /**
     * 场景：无目录文档（章标题＋长正文交错，无任何点线页码行）。
     * 预期：清洗零影响——返回与入参逐元素相同（同引用）的新列表，不改动任何一行文本。
     */
    @Test
    void should_keepEveryLine_when_removeContentsTable_given_noTocDocument() {
        // given
        String body1 = "第一段正文。".repeat(10);
        String body2 = "第二段正文。".repeat(10);
        List<String> sections = List.of("第一章 总则", body1, "第二章 要点", body2, "第三节 责任");

        // when
        List<String> cleaned = HierarchyParser.removeContentsTable(sections);

        // then
        assertEquals(sections.size(), cleaned.size(), "无目录文档不得丢行");
        for (int i = 0; i < sections.size(); i++) {
            assertSame(sections.get(i), cleaned.get(i), "未命中的行必须原引用透传（逐字节零影响）");
        }
    }

    /**
     * 场景：十段长正文中偶发一段孤立「章标题＋点线页码」引用行（另有短标题但目录命中段数为 1）。
     * 预期：命中段数低于最小成段阈值（3），该「……页码」行不被误杀，全部行保留。
     */
    @Test
    void should_keepSporadicLeaderPageLine_when_removeContentsTable_given_belowRunThreshold() {
        // given
        String body = "叙述性正文段落。".repeat(10);
        List<String> sections = List.of(body, body, body, body, body, body, body, body,
                "第四章 修订说明 …… 42", "第五章 附则");

        // when
        List<String> cleaned = HierarchyParser.removeContentsTable(sections);

        // then
        assertEquals(sections.size(), cleaned.size(), "成段数不足的偶发点线页码行不得触发剔除");
        assertTrue(cleaned.contains("第四章 修订说明 …… 42"));
    }

    /**
     * 场景：「目录」标记行之后紧跟长正文、无任何目录条目形态行。
     * 预期：区内在无条目时不动手（避免误杀恰好含「目录」二字的文档），全部行保留。
     */
    @Test
    void should_notRemoveAnything_when_removeContentsTable_given_markerWithoutEntries() {
        // given
        String body = "正文长段落，紧随目录二字之后且无点线页码形态。".repeat(3);
        List<String> sections = List.of("目录", body);

        // when
        List<String> cleaned = HierarchyParser.removeContentsTable(sections);

        // then
        assertEquals(sections, cleaned, "标记区内无目录条目时不得剔除任何行");
    }

    /**
     * 场景：入参为 null 与空列表（防御边界）。
     * 预期：均返回空列表，不抛异常。
     */
    @Test
    void should_returnEmptyList_when_removeContentsTable_given_nullOrEmptySections() {
        // given / when / then
        assertTrue(HierarchyParser.removeContentsTable(null).isEmpty(), "null 入参应返回空列表");
        assertTrue(HierarchyParser.removeContentsTable(List.of()).isEmpty(), "空入参应返回空列表");
    }
}
