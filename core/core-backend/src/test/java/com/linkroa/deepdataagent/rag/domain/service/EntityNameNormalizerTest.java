package com.linkroa.deepdataagent.rag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EntityNameNormalizer} 单元测试：按上游实体名清洗契约逐规则给正例与反例，
 * 并覆盖硬验收线——英文词内空格保留、引号变体归一同一值、{@code 3.14} 返回空串、
 * 幂等（normalize∘normalize = normalize）与控制字符清洗。
 *
 * @author DeepDataAgent
 */
class EntityNameNormalizerTest {

    // ------------------------------------------------------------------ 引号剥离

    /**
     * 场景：四种外层包裹形态的同一实体名。
     * 预期：英文双引号、英文单引号、中文弯双引号、书名号变体与裸名全部归一为 {@code 星辰科技}。
     */
    @Test
    void should_returnSameBareName_when_normalize_given_quotedVariants() {
        // given & when & then：五形态归一为同一值
        assertEquals("星辰科技", EntityNameNormalizer.normalize("\"星辰科技\""));
        assertEquals("星辰科技", EntityNameNormalizer.normalize("‘星辰科技’"));
        assertEquals("星辰科技", EntityNameNormalizer.normalize("“星辰科技”"));
        assertEquals("星辰科技", EntityNameNormalizer.normalize("《星辰科技》"));
        assertEquals("星辰科技", EntityNameNormalizer.normalize("星辰科技"));
    }

    /**
     * 场景：外层引号内部仍含同类引号。
     * 预期：不满足「内部无同类引号」条件，外层双引号不剥离；非中文语境英文引号亦不删除（反例）。
     */
    @Test
    void should_keepOuterQuotes_when_normalize_given_sameQuoteInsideOuterPair() {
        // given & when & then
        assertEquals("\"a\"b\"", EntityNameNormalizer.normalize("\"a\"b\""));
    }

    /**
     * 场景：中文语境邻接的英文/中文弯引号（内嵌形态）。
     * 预期：中文两侧与内部的引号全部去除。
     */
    @Test
    void should_removeQuotesAroundChinese_when_normalize_given_quotesAdjacentToCjk() {
        // given & when & then
        assertEquals("阿里巴巴", EntityNameNormalizer.normalize("阿里'巴'巴"));
        assertEquals("星辰科技", EntityNameNormalizer.normalize("“星辰”科技"));
        assertEquals("张三", EntityNameNormalizer.normalize("'张三'"));
    }

    // ------------------------------------------------------------------ 空白折叠

    /**
     * 场景：中文之间的空格（普通、全角 {@code \u3000}、NBSP {@code \u00A0}）。
     * 预期：全部删除；英文词内空格 MUST NOT 删除（硬验收线，反例）。
     */
    @Test
    void should_collapseCjkSpaces_when_normalize_given_spacesAroundChinese() {
        // given & when & then
        assertEquals("张三", EntityNameNormalizer.normalize("张 三"));
        assertEquals("张三", EntityNameNormalizer.normalize("张　三"));
        assertEquals("张三", EntityNameNormalizer.normalize("张 三"));
        assertEquals("张三", EntityNameNormalizer.normalize("张 三"));
        // 反例：英文词内空格保留（上游无西文间空格折叠规则，双空格形态原样保留）
        assertEquals("ACME Corp", EntityNameNormalizer.normalize("ACME Corp"));
        assertEquals("ACME  Corp", EntityNameNormalizer.normalize("ACME  Corp"));
    }

    /**
     * 场景：中西文（含数字与常见符号）之间的空格。
     * 预期：双向删除；纯西文间空格不受影响（反例）。
     */
    @Test
    void should_removeSpacesBetweenCjkAndWestern_when_normalize_given_mixedScriptName() {
        // given & when & then
        assertEquals("星辰AI", EntityNameNormalizer.normalize("星辰 AI"));
        assertEquals("星辰AI", EntityNameNormalizer.normalize("星辰  AI"));
        assertEquals("AI星辰", EntityNameNormalizer.normalize("AI 星辰"));
        assertEquals("星辰V2版本", EntityNameNormalizer.normalize("星辰 V2 版本"));
        // 反例：西文与数字间空格保留
        assertEquals("v2 1", EntityNameNormalizer.normalize("v2 1"));
    }

    // ------------------------------------------------------------------ 全角转换

    /**
     * 场景：全角字母、数字、符号与中文括号、破折号。
     * 预期：字母数字转半角，（）转()，—与－转-，全角空格转普通空格后参与折叠；半角输入不改变（反例）。
     */
    @Test
    void should_convertFullWidth_when_normalize_given_fullwidthCharacters() {
        // given & when & then
        assertEquals("ABC123", EntityNameNormalizer.normalize("ＡＢＣ１２３"));
        assertEquals("星辰(中国)", EntityNameNormalizer.normalize("星辰（中国）"));
        assertEquals("A-B", EntityNameNormalizer.normalize("A－B"));
        assertEquals("A-B", EntityNameNormalizer.normalize("A—B"));
        // 反例：半角原样
        assertEquals("ABC", EntityNameNormalizer.normalize("ABC"));
    }

    // ------------------------------------------------------------------ HTML 与控制字符

    /**
     * 场景：模型输出夹带 HTML 段落/换行标签。
     * 预期：标签剥离；非标签形态的尖括号文本不误伤（反例）。
     */
    @Test
    void should_stripHtmlTags_when_normalize_given_wrappedName() {
        // given & when & then
        assertEquals("ab", EntityNameNormalizer.normalize("<p>ab</p>"));
        assertEquals("星辰", EntityNameNormalizer.normalize("星<br/>辰"));
        assertEquals("星辰", EntityNameNormalizer.normalize("星</br>辰"));
        // 反例：不构成标签形态的尖括号保留
        assertEquals("A<B", EntityNameNormalizer.normalize("A<B"));
    }

    /**
     * 场景：名称中混入控制字符。
     * 预期：C0 控制符与 DEL 被清洗；西文之间的 {@code \t} 按正常空白保留（反例）。
     */
    @Test
    void should_removeControlChars_when_normalize_given_controlCharsInside() {
        // given & when & then
        assertEquals("星辰科技", EntityNameNormalizer.normalize("星辰\u0001科技"));
        assertEquals("AB", EntityNameNormalizer.normalize("A\u007FB"));
        // 反例：\t 属保留空白，西文之间原样
        assertEquals("A\tB", EntityNameNormalizer.normalize("A\tB"));
    }

    /**
     * 场景：首尾空白。
     * 预期：trim 生效。
     */
    @Test
    void should_trim_when_normalize_given_paddedName() {
        // given & when & then
        assertEquals("星辰", EntityNameNormalizer.normalize("  星辰  "));
        assertEquals("ACME Corp", EntityNameNormalizer.normalize(" ACME Corp \t"));
    }

    // ------------------------------------------------------------------ 无效名过滤

    /**
     * 场景：短纯数字与数字点组合名。
     * 预期：长度 < 3 纯数字、长度 < 6 且仅数字与点并至少含一个点判无效返回空串；
     * 长数字串与含点长数值不误杀（反例）。
     */
    @Test
    void should_returnEmpty_when_normalize_given_shortNumericFragments() {
        // given & when & then：命中过滤（3.14 为任务硬验收样本）
        assertEquals("", EntityNameNormalizer.normalize("3.14"));
        assertEquals("", EntityNameNormalizer.normalize("12"));
        assertEquals("", EntityNameNormalizer.normalize("1.2.3"));
        assertEquals("", EntityNameNormalizer.normalize(".12"));
        // 反例：有效数值形态名保留
        assertEquals("2024", EntityNameNormalizer.normalize("2024"));
        assertEquals("3.14159", EntityNameNormalizer.normalize("3.14159"));
        assertEquals("1234567", EntityNameNormalizer.normalize("1234567"));
    }

    /**
     * 场景：null 与各类空白输入。
     * 预期：统一返回空串。
     */
    @Test
    void should_returnEmpty_when_normalize_given_blankInput() {
        // given & when & then
        assertEquals("", EntityNameNormalizer.normalize(null));
        assertEquals("", EntityNameNormalizer.normalize("   "));
        assertEquals("", EntityNameNormalizer.normalize("\t \n "));
    }

    // ------------------------------------------------------------------ 幂等约束

    /**
     * 场景：对覆盖各规则的组合样本执行二次归一。
     * 预期：normalize(normalize(x)) == normalize(x)——合并段防御线重复执行无副作用的依据。
     */
    @Test
    void should_beIdempotent_when_normalize_given_normalizedTwice() {
        // given
        List<String> samples = List.of("\"星辰科技\"", "“星辰科技”", "《星辰科技》",
                "ACME Corp", "张 三", "3.14", "ＡＢＣ（测试）", "12 kg", "a<p>b</p>",
                "星辰 科技", "阿里'巴'巴", "A\u0001B", "  星辰  ", "2024", "1.2.3",
                "星辰 V2 版本", "\"a\"b\"");
        for (String sample : samples) {
            // when
            String once = EntityNameNormalizer.normalize(sample);
            String twice = EntityNameNormalizer.normalize(once);

            // then
            assertEquals(once, twice, "二次归一结果应与一次归一一致：" + sample);
        }
    }
}