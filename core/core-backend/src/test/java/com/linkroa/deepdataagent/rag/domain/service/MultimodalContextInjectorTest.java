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
 *   <li>MinerU 风格标题层级（{@code text_level}）驱动的章节路径与邻近文本注入；</li>
 *   <li>标题栈「同层替换、更深压栈、更浅弹栈」维护；</li>
 *   <li>本地解析（无标题层级）→ 章节路径缺省；无邻近文本 → 邻近文本缺省；</li>
 *   <li>邻近文本按单侧上限截断；注入永不抛（null 透传、非法层级按正文处理）。</li>
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

        // then：图片块章节路径为「## 营收 > ### 季度趋势」（层级即 text_level），邻近文本为前后正文拼接
        Map<String, Object> imageMeta = result.blocks().get(4).meta();
        assertEquals("## 营收 > ### 季度趋势", imageMeta.get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        String neighbor = (String) imageMeta.get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        assertTrue(neighbor.startsWith("下图为季度趋势。"));
        assertTrue(neighbor.contains("上图说明营收增长。"));
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

        // then：图片1 位于 h1>h2a；图片2 的同层 h2 已替换 h2a（栈内不再含 1.1 甲）
        assertEquals("# 第一章 > ## 1.1 甲", result.blocks().get(2).meta().get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertEquals("# 第一章 > ## 1.2 乙", result.blocks().get(4).meta().get(MultimodalMetaKeys.META_KEY_SECTION_PATH));
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

        // then：无标题层级 → 章节路径缺省；有前后邻近文本 → 邻近文本仍注入
        Map<String, Object> tableMeta = result.blocks().get(1).meta();
        assertFalse(tableMeta.containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertTrue(tableMeta.containsKey(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
    }

    @Test
    void should_defaultBothKeysAbsent_when_inject_given_multimodalBlockWithoutAnyNeighbor() {
        // given：仅两个相邻多模态块，无文本块、无标题
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ContentBlockVO equation = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "E=mc^2", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(image, equation));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：无标题且无邻近文本 → 两键均缺省（模板 N/A 兜底）
        assertFalse(result.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_SECTION_PATH));
        assertFalse(result.blocks().get(0).meta().containsKey(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
    }

    @Test
    void should_truncateNeighborText_when_inject_given_oversizedNeighborBlock() {
        // given：前向文本块远超 200 字符，注入应将其截断至单侧上限
        String longText = "长".repeat(300);
        ContentBlockVO body = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, longText, Map.of());
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        ParsedDocument parsed = new ParsedDocument("hash-1", List.of(body, image));

        // when
        ParsedDocument result = injector.inject(parsed);

        // then：邻近文本被截断（abbreviate 含省略号，长度收敛在上限内）
        String neighbor = (String) result.blocks().get(1).meta().get(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        assertTrue(neighbor.length() <= 200);
        assertTrue(neighbor.endsWith("..."));
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
