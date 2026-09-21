package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MediaChunkTemplateService} 单元测试。
 * <p>渲染已切换为静态 {@code PromptCatalog}，被测服务无注入
 * 依赖，本测试直接 new 真实实例；「模板路由与变量装配」断言改为对渲染产物全文的特征句断言
 * （语言传 Chinese 全名命中中文模板套，标签如「图片内容分析：」「视觉分析：」等为各模板独有）。
 * 覆盖：四类 chunk 模板路由与变量装配（image/table/equation/generic）、
 * enhanced_caption 三级退化（详描→摘要→N/A 占位）、
 * 注入键 _section_path/_neighbor_text 透传与缺失归一、
 * 表格结构体/公式原文的 meta 回退链、入参非法分支及 {@code buildChunk} 的
 * ChunkVO 装配（真实 token 计数口径）。全程离线。</p>
 */
class MediaChunkTemplateServiceTest {

    /** 测试语言全名（PromptCatalog 仅 Chinese 命中中文模板套） */
    private static final String LANGUAGE = "Chinese";

    /** 被测服务（无注入依赖，真实实例） */
    private final MediaChunkTemplateService service = new MediaChunkTemplateService();

    /**
     * 场景：图片块携带完整注入键与原始键（无脚注）。
     * 预期：路由 image_chunk，enhanced_caption 取详描，section_path/neighbor_text/image_path/captions
     * 均按 meta 注入，缺失的 footnotes 归一为 N/A。
     */
    @Test
    void should_renderImageChunkWithFullVars_when_buildChunkContent_given_imageBlockWithMeta() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图占位", Map.of(
                MultimodalMetaKeys.META_KEY_SECTION_PATH, "第一章>1.1 销售",
                MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT, "上文与下文",
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/sales.png",
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, "季度销售额柱状图"));
        MediaDescriptionVO description =
                new MediaDescriptionVO("销售图", "image", "摘要", "柱状图显示销售额上升", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then：image_chunk 路由（独有标签）+ 各变量按 meta 注入 + 缺失脚注归一 N/A
        assertTrue(content.contains("图片内容分析："));
        assertTrue(content.contains("- 章节路径：第一章>1.1 销售"));
        assertTrue(content.contains("- 邻近文本：上文与下文"));
        assertTrue(content.contains("图片路径：images/sales.png"));
        assertTrue(content.contains("标注：季度销售额柱状图"));
        assertTrue(content.contains("脚注：N/A"));
        assertTrue(content.contains("视觉分析：柱状图显示销售额上升"));
    }

    /**
     * 场景：媒体描述详描为空、摘要非空，且注入键缺失。
     * 预期：enhanced_caption 退化为摘要（三级退化链第二级），section_path/neighbor_text 归一 N/A。
     */
    @Test
    void should_degradeToSummary_when_buildChunkContent_given_blankDetailedDescription() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图占位", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("销售图", "image", "一句话摘要", "", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then
        assertTrue(content.contains("视觉分析：一句话摘要"));
        assertTrue(content.contains("- 章节路径：N/A"));
        assertTrue(content.contains("- 邻近文本：N/A"));
    }

    /**
     * 场景：公式块详描与摘要均为空白。
     * 预期：enhanced_caption 归一为 N/A 占位，装配不抛异常。
     */
    @Test
    void should_fillPlaceholder_when_buildChunkContent_given_blankCaptionFields() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "E=mc^2", Map.of());
        MediaDescriptionVO description = new MediaDescriptionVO("质能方程", "equation", "", "  ", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then：equation_chunk 路由 + N/A 占位
        assertTrue(content.contains("数学公式分析："));
        assertTrue(content.contains("数学分析：N/A"));
    }

    /**
     * 场景：表格块携带 table_body/table_caption/img_path。
     * 预期：路由 table_chunk，表格结构体与题注按 meta 注入，缺失脚注归一 N/A。
     */
    @Test
    void should_renderTableChunkWithBody_when_buildChunkContent_given_tableBlockWithMeta() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "表格占位", Map.of(
                MultimodalMetaKeys.META_KEY_TABLE_BODY, "姓名|年龄\n张三|18",
                MultimodalMetaKeys.META_KEY_TABLE_CAPTION, "员工表",
                MultimodalMetaKeys.META_KEY_IMG_PATH, "images/table1.png"));
        MediaDescriptionVO description =
                new MediaDescriptionVO("员工表", "table", "摘要", "三列员工信息表", false);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then：table_chunk 路由（独有标签）+ 结构体/题注/图片路径注入 + 脚注归一 N/A
        assertTrue(content.contains("表格分析："));
        assertTrue(content.contains("图片路径：images/table1.png"));
        assertTrue(content.contains("标题：员工表"));
        assertTrue(content.contains("结构：姓名|年龄\n张三|18"));
        assertTrue(content.contains("脚注：N/A"));
        assertTrue(content.contains("分析：三列员工信息表"));
    }

    /**
     * 场景：表格块 meta 无 table_body。
     * 预期：table_body 回退为块级 text（meta 回退链）。
     */
    @Test
    void should_fallbackTableBodyToBlockText_when_buildChunkContent_given_tableBlockWithoutBody() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TABLE, "块级表格文本", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("表", "table", "摘要", "详描", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then
        assertTrue(content.contains("结构：块级表格文本"));
    }

    /**
     * 场景：公式块 meta 无 format 且 text 键携带原文。
     * 预期：路由 equation_chunk，equation_text 取 meta text，format 默认 latex。
     */
    @Test
    void should_defaultLatexFormat_when_buildChunkContent_given_equationBlockWithoutFormat() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "块文本", Map.of(
                MultimodalMetaKeys.META_KEY_TEXT, "E=mc^2"));
        MediaDescriptionVO description =
                new MediaDescriptionVO("质能方程", "equation", "摘要", "详描", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then
        assertTrue(content.contains("公式：E=mc^2"));
        assertTrue(content.contains("格式：latex"));
    }

    /**
     * 场景：公式块 meta 仅携带 latex 别名与显式 format。
     * 预期：equation_text 按别名链回退到 latex，format 原值透传，块级文本被忽略。
     */
    @Test
    void should_useMetaFormatAndLatexAlias_when_buildChunkContent_given_equationBlockWithFormat() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_EQUATION, "被忽略的块文本", Map.of(
                MultimodalMetaKeys.META_KEY_LATEX, "\\alpha + \\beta",
                MultimodalMetaKeys.META_KEY_FORMAT, "ctex"));
        MediaDescriptionVO description =
                new MediaDescriptionVO("公式", "equation", "摘要", "详描", true);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then
        assertTrue(content.contains("公式：\\alpha + \\beta"));
        assertTrue(content.contains("格式：ctex"));
        assertFalse(content.contains("被忽略的块文本"));
    }

    /**
     * 场景：泛型块携带 content_type 与 text 键。
     * 预期：路由 generic_chunk，content_type/content 均按 meta 注入。
     */
    @Test
    void should_renderGenericChunkWithMetaValues_when_buildChunkContent_given_genericBlockWithMeta() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "块文本", Map.of(
                MultimodalMetaKeys.META_KEY_CONTENT_TYPE, "Chart",
                MultimodalMetaKeys.META_KEY_TEXT, "meta 内容"));
        MediaDescriptionVO description =
                new MediaDescriptionVO("图表", "generic", "摘要", "详描", false);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then：generic_chunk 路由（{content_type} 前缀标签）
        assertTrue(content.contains("Chart内容分析："));
        assertTrue(content.contains("内容：meta 内容"));
    }

    /**
     * 场景：泛型块无任何 meta（含注入键缺失）。
     * 预期：content_type 取块类型小写、content 回退块 text，装配不抛异常。
     */
    @Test
    void should_fillPlaceholders_when_buildChunkContent_given_sparseGenericBlock() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_GENERIC, "块级内容", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("块", "generic", "摘要", "详描", false);

        // when
        String content = service.buildChunkContent(block, description, LANGUAGE);

        // then
        assertTrue(content.contains("generic内容分析："));
        assertTrue(content.contains("内容：块级内容"));
    }

    /**
     * 场景：文本块与未知类型块。
     * 预期：均抛 IllegalArgumentException（文本块无需媒体模板、未知类型无模板）。
     */
    @Test
    void should_throwIae_when_buildChunkContent_given_textOrUnknownBlock() {
        // given
        MediaDescriptionVO description =
                new MediaDescriptionVO("名", "image", "摘要", "详描", true);
        ContentBlockVO textBlock = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "文本", Map.of());
        ContentBlockVO unknownBlock = new ContentBlockVO("AUDIO", "音频", Map.of());

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunkContent(textBlock, description, LANGUAGE));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunkContent(unknownBlock, description, LANGUAGE));
    }

    /**
     * 场景：内容块为空 / 媒体描述为空 / 语言码空白。
     * 预期：均抛 IllegalArgumentException。
     */
    @Test
    void should_throwIae_when_buildChunkContent_given_invalidArguments() {
        // given
        ContentBlockVO imageBlock = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("名", "image", "摘要", "详描", true);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunkContent(null, description, LANGUAGE));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunkContent(imageBlock, null, LANGUAGE));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunkContent(imageBlock, description, "  "));
    }

    /**
     * 场景：图片块装配 ChunkVO（真实渲染产物，token 按字符数计数）。
     * 预期：sequence/text/tokens/block 四字段齐备，tokens 为真实计数口径。
     */
    @Test
    void should_buildChunkVoWithTokens_when_buildChunk_given_imageBlockAndCounter() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("名", "image", "摘要", "详描", true);
        TokenCounter counter = text -> text.length();
        String expectedContent = service.buildChunkContent(block, description, LANGUAGE);

        // when
        ChunkVO chunk = service.buildChunk(3, block, description, LANGUAGE, counter);

        // then
        assertEquals(3, chunk.sequence());
        assertEquals(expectedContent, chunk.text());
        assertEquals(expectedContent.length(), chunk.tokens());
        assertSame(block, chunk.block());
    }

    /**
     * 场景：装配 ChunkVO 时 token 计数器为空。
     * 预期：抛 IllegalArgumentException 且不触达模板渲染。
     */
    @Test
    void should_throwIae_when_buildChunk_given_nullTokenCounter() {
        // given
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图", Map.of());
        MediaDescriptionVO description =
                new MediaDescriptionVO("名", "image", "摘要", "详描", true);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> service.buildChunk(1, block, description, LANGUAGE, null));
    }
}
