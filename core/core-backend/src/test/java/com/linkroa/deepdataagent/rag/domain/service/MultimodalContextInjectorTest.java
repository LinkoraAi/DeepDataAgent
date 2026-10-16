package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultimodalContextInjector} 单元测试。
 * <p>被测服务无外部依赖，直接 new 真实实例；覆盖：</p>
 * <ul>
 *   <li>MinerU 风格标题层级（{@code text_level}）驱动的章节路径（纯标题文本、无 {@code #} 前缀）与邻近文本注入；</li>
 *   <li>标题栈「同层替换、更深压栈、更浅弹栈」维护；</li>
 *   <li>邻近文本按位置窗口（下标 ±3）取项、窗口内多模态块跳过、内容内嵌空白归一、无省略号与截断；</li>
 *   <li>本地解析（无标题层级）→ 章节路径缺省；无邻近文本 → 邻近文本缺省；</li>
 *   <li>注入永不抛（null 透传、非法层级按正文处理）。</li>
 * </ul>
 */
class MultimodalContextInjectorTest {

    /** 被测注入器（无注入依赖，真实实例） */
    private final MultimodalContextInjector injector = new MultimodalContextInjector();

    /** 标题层级键（与注入器内部约定一致，用于构造解析产物） */
    private static final String TEXT_LEVEL = "text_level";

    @Test
    void should_injectSectionPathAndNeighborText_when_inject_given_mineruHeadingAndBodyBlocks() {
        // given：营收(h2) → 正文 → 季度趋势(h3) → 正文 → 图片 → 正文（图片位于两章节之下、前后均有正文）
        ContentBlockVO h2 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "营收", Map.of(TEXT_LEVEL, 2));
        ContentBlockVO body1 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "本节介绍营收情况。", Map.of());
        ContentBlockVO h3 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "季度趋势", Map.of(TEXT_LEVEL, 3));
        ContentBlockVO body2 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "下图为季度趋势。", Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ContentBlockVO body3 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "上图说明营收增长。", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1",
                List.of(h2, body1, h3, body2, image, body3));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：章节路径为纯标题文本（层级不进路径）；邻近文本为位置窗口内各文本块以单空格连接
        Map<String, Object> imageMeta = result.blocks().get(4).meta();
        assertEquals("营收 > 季度趋势", imageMeta.get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertEquals("本节介绍营收情况。 季度趋势 下图为季度趋势。 上图说明营收增长。",
                imageMeta.get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
        // then：文本块原样返回（不被注入任何键）
        assertFalse(result.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
    }

    @Test
    void should_popShallowerAndReplaceSameLevel_when_inject_given_headingLevelChanges() {
        // given：h1 → h2 →（图片）→ h2 替换 →（图片），验证标题栈弹栈与同层替换
        ContentBlockVO h1 = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "第一章", Map.of(TEXT_LEVEL, 1));
        ContentBlockVO h2a = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "1.1 甲", Map.of(TEXT_LEVEL, 2));
        ContentBlockVO image1 = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图1", Map.of());
        ContentBlockVO h2b = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "1.2 乙", Map.of(TEXT_LEVEL, 2));
        ContentBlockVO image2 = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图2", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(h1, h2a, image1, h2b, image2));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：章节路径无 # 前缀；图片1 位于 h1>h2a；图片2 的同层 h2 已替换 h2a（栈内不再含 1.1 甲）
        assertEquals("第一章 > 1.1 甲", result.blocks().get(2).meta().get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertEquals("第一章 > 1.2 乙", result.blocks().get(4).meta().get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
    }

    @Test
    void should_defaultSectionPathAbsent_when_inject_given_localParseWithoutHeadingLevel() {
        // given：本地解析产物无 text_level，正文块 + 表格块 + 正文块
        ContentBlockVO before = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "表格前的说明。", Map.of());
        ContentBlockVO table = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表", Map.of());
        ContentBlockVO after = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "表格后的说明。", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(before, table, after));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：无标题层级 → 章节路径缺省；有窗口内邻近文本 → 邻近文本仍注入
        Map<String, Object> tableMeta = result.blocks().get(1).meta();
        assertFalse(tableMeta.containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertEquals("表格前的说明。 表格后的说明。", tableMeta.get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
    }

    @Test
    void should_defaultBothKeysAbsent_when_inject_given_multimodalBlockWithoutAnyNeighbor() {
        // given：仅两个相邻多模态块，无文本块、无标题
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ContentBlockVO equation = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "E=mc^2", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(image, equation));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：无标题且窗口内无文本块 → 两键均缺省（模板 N/A 兜底）
        assertFalse(result.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertFalse(result.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
    }

    @Test
    void should_collectPositionWindowAndSkipMultimodal_when_inject_given_windowWithTextAndMediaBlocks() {
        // given：T T T T IMG IMG2 T T T T —— IMG 下标 4，位置窗口 [1,7]（不含 4），窗口内 IMG2 应被跳过
        ContentBlockVO a = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段A", Map.of());
        ContentBlockVO b = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段B", Map.of());
        ContentBlockVO c = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段C", Map.of());
        ContentBlockVO d = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段D", Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ContentBlockVO table = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表", Map.of());
        ContentBlockVO e = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段E", Map.of());
        ContentBlockVO f = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段F", Map.of());
        ContentBlockVO g = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段G", Map.of());
        ContentBlockVO h = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "段H", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1",
                List.of(a, b, c, d, image, table, e, f, g, h));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：仅窗口内 TEXT 块按序列序纳入；窗口外（段A / 段G / 段H）与窗口内多模态块（表）均不纳入
        String neighbor = (String) result.blocks().get(4).meta().get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        assertEquals("段B 段C 段D 段E 段F", neighbor);
        assertFalse(neighbor.contains("段A"));
        assertFalse(neighbor.contains("段G"));
        assertFalse(neighbor.contains("段H"));
    }

    @Test
    void should_normalizeEmbeddedWhitespace_when_inject_given_neighborTextWithNewlineAndTabs() {
        // given：前向文本块内含换行与连续制表符（版式化的「图1」行）
        ContentBlockVO body = new ContentBlockVO(ContentBlockVO.TYPE_TEXT,
                "今天天气不好，详见图1：\n\n\t\t\t\t\t\t\t\t图1", Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(body, image));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：内容内部的换行与连续制表符归一为单个空格，单行版式不被撑破
        String neighbor = (String) result.blocks().get(1).meta().get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        assertEquals("今天天气不好，详见图1： 图1", neighbor);
        assertFalse(neighbor.contains("\n"));
        assertFalse(neighbor.contains("\t"));
    }

    @Test
    void should_notTruncateNeighborText_when_inject_given_oversizedNeighborBlock() {
        // given：前向文本块远超旧单侧上限（200 字符）
        String longText = "长".repeat(300);
        ContentBlockVO body = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, longText, Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(body, image));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：不做单侧截断、不追加省略号
        String neighbor = (String) result.blocks().get(1).meta().get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        assertEquals(longText, neighbor);
        assertFalse(neighbor.endsWith("..."));
    }

    @Test
    void should_returnNull_when_inject_given_nullParsedDocument() {
        // when // then：null 解析产物原样透传，不抛
        assertNull(injector.inject(null));
    }

    @Test
    void should_treatInvalidLevelAsBody_when_inject_given_nonNumericTextLevel() {
        // given：标题层级键值为无法解析的字符串 → 按正文处理（不入栈），图片块章节路径缺省
        ContentBlockVO pseudoHeading = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "标题?", Map.of(TEXT_LEVEL, "abc"));
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(pseudoHeading, image));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：非法层级不入栈 → 章节路径缺省，但邻近文本仍取该文本块
        Map<String, Object> imageMeta = result.blocks().get(1).meta();
        assertFalse(imageMeta.containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertEquals("标题?", imageMeta.get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
    }

    @Test
    void should_keepOriginalParsedDocument_when_inject_given_onlyTextBlocks() {
        // given：纯文本块，无多模态块命中
        ContentBlockVO text = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "正文", Map.of(TEXT_LEVEL, 1));
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(text));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：重建的解析产物内容不变，文本块原样保留（同一实例引用）
        assertSame(text, result.blocks().get(0));
    }
}