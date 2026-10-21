package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalReadParseSupport} 图片签名嗅探与直通产物构造单元测试。
 * <p>纯静态工具直测，无外部依赖，不使用 Mockito。覆盖：PNG / JPEG 签名命中、
 * null / 空 / 短字节的防越界读、非图片二进制不误命中，以及直通产物的
 * 「载荷键 == img_path == 基名」对齐契约与哈希口径。</p>
 */
class LocalReadParseSupportTest {

    /**
     * 场景：源字节以 PNG 签名（89 50 4E 47）开头。
     * 预期：命中图片嗅探。
     */
    @Test
    void should_returnTrue_when_looksLikeImage_given_pngSignature() {
        // given
        byte[] content = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};

        // when
        boolean image = LocalReadParseSupport.looksLikeImage(content);

        // then
        assertTrue(image, "PNG 签名应命中");
    }

    /**
     * 场景：源字节以 JPEG 签名（FF D8 FF）开头。
     * 预期：命中图片嗅探。
     */
    @Test
    void should_returnTrue_when_looksLikeImage_given_jpegSignature() {
        // given
        byte[] content = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

        // when
        boolean image = LocalReadParseSupport.looksLikeImage(content);

        // then
        assertTrue(image, "JPEG 签名应命中");
    }

    /**
     * 场景：null / 空数组 / 短于签名长度的字节（含"半个 PNG 签名"）。
     * 预期：一律判不命中且不抛越界异常（防越界读护栏）。
     */
    @Test
    void should_returnFalse_when_looksLikeImage_given_nullOrTooShortBytes() {
        // when // then：三类退化输入全部安全判否
        assertFalse(LocalReadParseSupport.looksLikeImage(null), "null 应判不命中");
        assertFalse(LocalReadParseSupport.looksLikeImage(new byte[0]), "空字节应判不命中");
        assertFalse(LocalReadParseSupport.looksLikeImage(new byte[] {0x00}), "长度不足应判不命中");
        assertFalse(LocalReadParseSupport.looksLikeImage(new byte[] {(byte) 0x89, 0x50}),
                "仅半个 PNG 签名（短于 4 字节）应判不命中");
    }

    /**
     * 场景：可解码文本与不可解码非图片二进制。
     * 预期：均判不命中（嗅探只认签名头部，不被内容形态干扰）。
     */
    @Test
    void should_returnFalse_when_looksLikeImage_given_nonImageBytes() {
        // given
        byte[] text = "普通文本内容".getBytes(StandardCharsets.UTF_8);
        byte[] binary = new byte[] {0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80, (byte) 0xC0};

        // when // then
        assertFalse(LocalReadParseSupport.looksLikeImage(text), "文本字节应判不命中");
        assertFalse(LocalReadParseSupport.looksLikeImage(binary), "非图片二进制应判不命中");
    }

    /**
     * 场景：以源字节与文件基名构造图片直通产物。
     * 预期：恰一个 IMAGE 块（空正文、meta 携 img_path=基名与 page=0）；
     * 载荷键与 img_path 恒等对齐；哈希为 32 位小写 hex（源字节 MD5）。
     */
    @Test
    void should_buildAlignedDocument_when_imagePassthrough_given_bytesAndName() {
        // given
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x01, 0x02, 0x03};
        String baseName = "photo.png";

        // when
        ParsedDocument parsed = LocalReadParseSupport.imagePassthrough(png, baseName);

        // then：块形态
        assertEquals(1, parsed.blocks().size(), "直通产物应恰含一个内容块");
        ContentBlockVO block = parsed.blocks().get(0);
        assertEquals(ContentBlockVO.TYPE_IMAGE, block.type());
        assertEquals("", block.text(), "图片块正文应为空（描述由 Stage2 模板重建）");
        assertEquals(baseName, block.meta().get("img_path"), "img_path 应为文件基名");
        // then：载荷键与 img_path 对齐（绑定必命中的前提）
        assertEquals(block.meta().get("img_path"), parsed.images().keySet().iterator().next(),
                "载荷键必须与 img_path 恒等");
        assertArrayEquals(png, parsed.images().get(baseName), "载荷值应为源字节");
        // then：哈希口径（32 位小写 hex）
        assertEquals(32, parsed.parsedTextHash().length());
        assertTrue(parsed.parsedTextHash().chars().allMatch(c -> "0123456789abcdef".indexOf(c) >= 0),
                "哈希应为小写 hex");
    }
}