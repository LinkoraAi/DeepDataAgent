package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DelimiterParser#mergeUnits} 贪心合并语义单元测试。
 * <p>锁定语义：①<b>唯一封口线</b>「并入后 {@code prev + unit > target} 才封块」——重叠比例不参与封口
 * （30% 重叠下 360+100 不封块、480+100 才封块），仅在开新块时做上一块的前缀字符截尾；
 * ②短标题强制并入<b>仅 Markdown 形</b>（{@code ^#{1,6}\s+}、token ≤ 50、行尾非句末标点），
 * 中文章条形（第三章 …）不再受保护；③超预算巨型单元独立成块、custom 分隔符逐段独立口径不变。
 * TokenCounter 使用确定性 stub（{@code text.length()}），使 token 与字符长度一一对应，便于逐字符断言；
 * 纯静态工具直测，无外部依赖，不使用 Mockito。</p>
 */
class DelimiterParserTest {

    /** 确定性 token 计数 stub：长度即 token 数。 */
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    /**
     * 场景：累加后判定封块（加入前预判）——4 段各 12 token、target=30、pct=0。
     * 预期：块 1=24（12+12 并入，段 3 使 24+12=36>30 触发封块），块 2=24；消除旧版 36/12 的后置溢出。
     */
    @Test
    void should_sealBlockBeforeOverflow_when_mergeUnits_given_uniformSmallUnits() {
        // given
        List<Unit> units = List.of(textUnit(line(12)), textUnit(line(12)), textUnit(line(12)), textUnit(line(12)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 30, 0, tokenCounter);

        // then
        assertEquals(2, merged.size(), "并入前预判超 target 即封块");
        assertEquals(24, merged.get(0).tokens, "段 1+2 running-sum 累加为 24，未溢出到 36");
        assertEquals(24, merged.get(1).tokens, "段 3 作新块种子再并入段 4，累加为 24");
    }

    /**
     * 场景：累加恰等于 target——seed 50 + unit 50 = 100 = target，未「超过」。
     * 预期：不封块，合并为单块 100（边界值 {@code >} 严格大于才封）。
     */
    @Test
    void should_notSeal_when_mergeUnits_given_sumEqualsTarget() {
        // given
        List<Unit> units = List.of(textUnit(line(50)), textUnit(line(50)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 100, 0, tokenCounter);

        // then
        assertEquals(1, merged.size(), "累加恰等于 target 不封块");
        assertEquals(100, merged.get(0).tokens);
    }

    /**
     * 场景：累加超过 target 一个 token——seed 50 + unit 51 = 101 > target=100。
     * 预期：封块，seed 独立成块 50，unit 作新块种子 51。
     */
    @Test
    void should_sealBlock_when_mergeUnits_given_sumExceedsTarget() {
        // given
        List<Unit> units = List.of(textUnit(line(50)), textUnit(line(51)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 100, 0, tokenCounter);
        // then
        assertEquals(2, merged.size(), "累加超过 target 立即封块");
        assertEquals(50, merged.get(0).tokens);
        assertEquals(51, merged.get(1).tokens);
    }

    /**
     * 场景：巨型单元独立成块——短标题块（seed）后紧跟超预算单元（{@code unit.tokens > target}）。
     * 预期：巨型单元先于短标题并入被处理，独立成块、不被粘连进短标题块（契约 2 保留）。
     */
    @Test
    void should_keepOversizedUnitSeparate_when_mergeUnits_given_shortHeaderThenOversized() {
        // given：短标题 "\n## A"（5 token，命中 Markdown 形且无句末标点）+ 100 token 巨型段
        List<Unit> units = List.of(textUnit("\n## A"), textUnit(line(100)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 30, 0, tokenCounter);

        // then
        assertEquals(2, merged.size(), "巨型单元独立成块，未被短标题强制并入粘连");
        assertEquals(5, merged.get(0).tokens, "短标题块仅含标题本身");
        assertTrue(merged.get(0).text.contains("## A"));
        assertEquals(100, merged.get(1).tokens, "超预算单元按真实长度独立成块");
        assertFalse(merged.get(1).text.contains("## A"), "巨型块不回吞短标题");
    }

    /**
     * 场景：Markdown 短标题强制并入——seed {@code "\n## 标题"} 即使并入后超 target 也不封块。
     * 预期：单块（6+20=26 > target 20），标题与正文同块。
     */
    @Test
    void should_forceMergeNextSegment_when_mergeUnits_given_markdownShortHeader() {
        // given
        List<Unit> units = List.of(textUnit("\n## 标题"), textUnit(line(20)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 20, 0, tokenCounter);

        // then
        assertEquals(1, merged.size(), "短标题强制并入下一段，即使累加超 target 也不封块");
        assertEquals(26, merged.get(0).tokens);
        assertTrue(merged.get(0).text.contains("## 标题"));
    }

    /**
     * 场景：中文章条形不再受短标题保护——seed {@code "\n第三章 系统设计"}（无 {@code #} 前缀）。
     * 预期：不走强制并入，按唯一封口线判定（6+20=26 > target 20）封成两块。
     */
    @Test
    void should_notForceMerge_when_mergeUnits_given_chineseSectionHeader() {
        // given
        List<Unit> units = List.of(textUnit("\n第三章 系统设计"), textUnit(line(20)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 20, 0, tokenCounter);

        // then
        assertEquals(2, merged.size(), "中文条形不属于短标题形态，按正常封口线处理");
        assertEquals(9, merged.get(0).tokens);
        assertEquals(20, merged.get(1).tokens);
    }

    /**
     * 场景：带句末标点的短 Markdown 标题行不算短标题——seed {@code "\n## 标题。"} 末字符为「。」。
     * 预期：不触发强制并入，走封口线判定，seed（7）与 unit（20）分成两块。
     */
    @Test
    void should_notForceMerge_when_mergeUnits_given_shortHeaderWithEndPunctuation() {
        // given
        List<Unit> units = List.of(textUnit("\n## 标题。"), textUnit(line(20)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 20, 0, tokenCounter);

        // then
        assertEquals(2, merged.size(), "末字符为句末标点则非短标题，不误并");
        assertEquals(7, merged.get(0).tokens);
        assertEquals(20, merged.get(1).tokens);
    }

    /**
     * 场景：重叠 30% 下「当前累计块未并入越界」——target=512、累计 360、下一单元 100（旧口径
     * 会因 {@code prev > threshold=358.4} 提前封块）。
     * 预期：460 ≤ 512 并入当前块，只有 1 块，重叠比例不压缩块尺寸。
     */
    @Test
    void should_notSeal_when_mergeUnits_given_overlapPercent30WithSumBelowTarget() {
        // given
        List<Unit> units = List.of(textUnit(line(360, 'a')), textUnit(line(100, 'b')));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 512, 30, tokenCounter);

        // then
        assertEquals(1, merged.size(), "重叠不参与封口：360+100 未越 512 应并入同一块");
        assertEquals(460, merged.get(0).tokens);
        assertTrue(merged.get(0).text.contains("a".repeat(359)));
        assertTrue(merged.get(0).text.contains("b".repeat(99)));
    }

    /**
     * 场景：重叠 30% 下「并入才越界」——target=512、累计 480、下一单元 100。
     * 预期：480 块封块输出，100 单元成为新块种子，且新块无条件前置上一块可见字符尾部 30%。
     */
    @Test
    void should_sealAndSeedNewBlockWithOverlapPrefix_when_mergeUnits_given_overlapPercent30WithSumOverTarget() {
        // given
        List<Unit> units = List.of(textUnit(line(480, 'a')), textUnit(line(100, 'b')));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 512, 30, tokenCounter);

        // then：块 1 恰为 480；块 2 = 480×30% 重叠前缀（144）+ 100 = 244
        assertEquals(2, merged.size(), "并入越界才封块");
        assertEquals(480, merged.get(0).tokens);
        assertTrue(merged.get(1).text.startsWith("a".repeat(144)), "新块应带上一块尾部重叠前缀");
        assertTrue(merged.get(1).text.contains("b".repeat(99)), "新块正文完整");
        assertEquals(244, merged.get(1).tokens);
    }

    /**
     * 场景：超过短标题 token 上限（50）的标题形行不受保护——seed 为 {@code "\n## "} 开头但累计 55 token。
     * 预期：token>50 判为非短标题，走累加预判封块分成两块（seed 55、unit 10）。
     */
    @Test
    void should_notForceMerge_when_mergeUnits_given_headerLineOverTokenLimit() {
        // given：55 token 的 Markdown 形长行（未超 target 60，故非巨型），后跟 10 token 段
        List<Unit> units = List.of(textUnit("\n## " + "b".repeat(51)), textUnit(line(10)));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 60, 0, tokenCounter);

        // then
        assertEquals(2, merged.size(), "token>50 的标题形长行不受短标题保护");
        assertEquals(55, merged.get(0).tokens);
        assertEquals(10, merged.get(1).tokens);
    }

    /**
     * 场景：尾部孤立短标题（其后无可并入单元）——序列仅一个短标题单元。
     * 预期：它自己成块，内容不丢失（token 5，正文完整保留）。
     */
    @Test
    void should_keepTrailingShortHeaderAsBlock_when_mergeUnits_given_onlyShortHeader() {
        // given
        List<Unit> units = List.of(textUnit("\n## 标题"));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 30, 0, tokenCounter);

        // then
        assertEquals(1, merged.size(), "孤立短标题独立成块，不丢失");
        assertEquals(6, merged.get(0).tokens);
        assertTrue(merged.get(0).text.contains("## 标题"));
    }

    /**
     * 场景：custom 分隔符逐段独立——反引号包裹的 {@code ##} 切段（custom 模式下 mergeUnits 不参与）。
     * 预期：{@link DelimiterParser#buildUnitsForBlock} 每段产出独立单元，各含前置换行前缀、绕过 token 预算。
     */
    @Test
    void should_buildIndependentUnitsPerSegment_when_buildUnitsForBlock_given_customDelimiter() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "aa##bb##cc", null);
        Pattern pattern = DelimiterParser.buildPattern(DelimiterParser.parseDelimiterField("`##`"));

        // when
        List<Unit> units = DelimiterParser.buildUnitsForBlock(block, pattern, true, 512, tokenCounter);

        // then
        assertEquals(3, units.size(), "custom 分隔符每段独立成单元");
        assertEquals("\naa", units.get(0).text);
        assertEquals("\nbb", units.get(1).text);
        assertEquals("\ncc", units.get(2).text);
        assertFalse(units.get(0).passthrough, "custom 段为普通文本单元");
    }

    /**
     * 场景：overlap 前缀口径不变——target=20、overlap=30，两段各 15 token（并入越界 30 &gt; 20 才封块）。
     * 预期：第二段封块时前置第一段可见字符尾部（{@code len×70%} 起点）形成的重叠前缀，新块以 "ccccc" 开头、
     * 共 20 token，与改动前口径一致。
     */
    @Test
    void should_prependOverlapPrefix_when_mergeUnits_given_overlapPercent() {
        // given
        List<Unit> units = List.of(textUnit(line(15, 'c')), textUnit(line(15, 'd')));

        // when
        List<Unit> merged = DelimiterParser.mergeUnits(units, 20, 30, tokenCounter);

        // then
        assertEquals(2, merged.size());
        assertEquals(15, merged.get(0).tokens);
        assertTrue(merged.get(1).text.startsWith("ccccc"), "新块以前一块可见字符尾部前缀开头");
        assertTrue(merged.get(1).text.contains("dddddddddddddd"), "新块正文保持完整");
        assertEquals(20, merged.get(1).tokens, "overlap 前缀计入 token");
    }

    // ===== buildPattern 缓存语义（converge-rag-hot-path-object-creation / 6.3） =====

    /**
     * 场景（6.3①）：同一分隔符集合（内容相等但不同 List 实例）多次调用 {@link DelimiterParser#buildPattern}。
     * 预期：命中缓存复用同一已编译 {@link Pattern} 实例（Pattern 不可变、线程安全，可共享）。
     */
    @Test
    void should_returnSamePatternInstance_when_buildPattern_given_repeatedIdenticalDelimiterSets() {
        // given：两次独立解析产出内容相同、实例不同的分隔符列表
        List<String> firstDelims = DelimiterParser.parseDelimiterField("`##`.");
        List<String> secondDelims = DelimiterParser.parseDelimiterField("`##`.");

        // when
        Pattern first = DelimiterParser.buildPattern(firstDelims);
        Pattern second = DelimiterParser.buildPattern(secondDelims);

        // then
        assertSame(first, second, "同内容分隔符集合应复用同一已编译 Pattern 实例");
    }

    /**
     * 场景（6.3②）：不同分隔符集合分别调用 {@link DelimiterParser#buildPattern}。
     * 预期：各得独立实例与独立正则文本，互不误命中。
     */
    @Test
    void should_returnDifferentPatternInstances_when_buildPattern_given_differentDelimiterSets() {
        // given / when
        Pattern patternA = DelimiterParser.buildPattern(List.of("。"));
        Pattern patternB = DelimiterParser.buildPattern(List.of("。", "；"));

        // then
        assertNotSame(patternA, patternB, "不同分隔符集合不得共享同一 Pattern 实例");
        assertNotEquals(patternA.pattern(), patternB.pattern(), "不同分隔符集合的正则文本必须不同");
    }

    /**
     * 场景（6.3③）：分隔符含正则元字符（{@code |}、{@code .}、{@code (}、{@code [}）。
     * 预期：经常量路径（每字符 {@code Pattern.quote} 后以 {@code |} 连接）产出的正则与手工构造的
     * 常量正则逐字一致，切分语义按字面量命中、无元字符逃逸。
     */
    @Test
    void should_matchConstantRegexSemantics_when_buildPattern_given_regexMetaCharacterDelimiters() {
        // given：元字符集分隔符（模拟 parseDelimiterField 的长度降序输出形态）
        List<String> metaDelims = List.of("##", "|", ".", "(", "[");
        Pattern constantBaseline = Pattern.compile(
                metaDelims.stream().map(Pattern::quote).collect(Collectors.joining("|")), Pattern.DOTALL);
        String text = "甲##乙|丙.丁(戊[己";

        // when
        Pattern cached = DelimiterParser.buildPattern(metaDelims);

        // then：正则文本与常量基准逐字一致；切分产出等价（字面量命中）
        assertEquals(constantBaseline.pattern(), cached.pattern(), "元字符必须全部经 Pattern.quote 字面化");
        assertEquals(DelimiterParser.splitDroppingDelim(text, constantBaseline),
                DelimiterParser.splitDroppingDelim(text, cached), "切分语义应与常量正则一致");
        assertEquals(6, DelimiterParser.splitDroppingDelim(text, cached).size(),
                "五个字面分隔符切出六段（首段甲、末段己）");
    }

    /**
     * 场景（6.3④）：以调用方可变 {@code List} 作入参，取得 Pattern 后再修改该 List。
     * 预期：已返回的 Pattern 切分行为不受影响；按原始内容再次请求仍命中同一缓存实例——
     * 缓存键取自入参的不可变快照，不被调用方后续修改污染。
     */
    @Test
    void should_keepCachedPatternUnaffected_when_buildPattern_given_callerMutatesInputListAfterwards() {
        // given：可变入参列表与首次解析产出
        List<String> mutableDelims = new ArrayList<>(List.of("。", "；"));
        Pattern cached = DelimiterParser.buildPattern(mutableDelims);
        List<String> splitBefore = DelimiterParser.splitDroppingDelim("甲。乙；丙", cached);

        // when：调用方修改入参列表内容（清空并注入无关分隔符）
        mutableDelims.clear();
        mutableDelims.add("X");

        // then①：已返回 Pattern 的切分行为逐字不变
        assertEquals(splitBefore, DelimiterParser.splitDroppingDelim("甲。乙；丙", cached),
                "已缓存 Pattern 的行为不得随调用方列表修改而漂移");
        // then②：按原始内容再请求，命中同一实例（键未被变异列表污染）
        assertSame(cached, DelimiterParser.buildPattern(List.of("。", "；")),
                "缓存键应为不可变快照，原内容再查必命中同一实例");
    }

    /**
     * 构造普通文本单元（passthrough=false，token 按 {@code text.length()} 口径）。
     *
     * @param text 单元文本
     * @return 文本单元
     */
    private static Unit textUnit(String text) {
        return new Unit(text, text.length(), false, null);
    }

    /**
     * 生成长度恰为 {@code tokens} 的普通段落文本（前置换行 +  个 {@code 'a'}），非标题形。
     *
     * @param tokens 目标 token 数（= 文本长度）
     * @return 单元文本
     */
    private static String line(int tokens) {
        return line(tokens, 'a');
    }

    /**
     * 生成长度恰为 {@code tokens} 的普通段落文本（前置换行 +  个指定字符），非标题形。
     *
     * @param tokens 目标 token 数（= 文本长度）
     * @param ch     填充字符
     * @return 单元文本
     */
    private static String line(int tokens, char ch) {
        return "\n" + String.valueOf(ch).repeat(Math.max(0, tokens - 1));
    }
}
