package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.service.ChunkParams;
import com.linkroa.deepdataagent.rag.domain.service.GeneralChunkStrategy;
import com.linkroa.deepdataagent.rag.domain.service.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalReadFallbackDocumentParser} 单元测试。
 * <p>覆盖：可解码文本产出真实 TEXT 块、图片签名命中产出视觉直通产物（IMAGE 块 + 自身字节载荷、
 * 载荷键与 img_path 对齐）、不可解码<b>非图片</b>按「引擎不支持该类型」抛错如实上报、
 * 空内容产出占位 TEXT 块、伪造图片后缀不误直通、对象缺失与读取 IO 异常分支、
 * 支持类型集合声明，以及占位块经通用策略切分后切片数 ≥ 1 的不变量。</p>
 */
@ExtendWith(MockitoExtension.class)
class LocalReadFallbackDocumentParserTest {

    /** 测试对象键（含目录与扩展名）。 */
    private static final String OBJECT_KEY = "rag/100/source/手册.pdf";

    /** 对象资产存储访问端口 Mock。 */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** 被测对象。 */
    private LocalReadFallbackDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new LocalReadFallbackDocumentParser(kbAssetStoragePort);
    }

    /**
     * 场景：源内容为可解码 UTF-8 文本（含空行分段）。
     * 预期：产出真实 TEXT 块（无占位）、page 元数据存在、图片映射为空。
     */
    @Test
    void should_returnTextBlocks_when_parse_given_decodableText() {
        // given
        byte[] content = "第一段\n\n第二段".getBytes(StandardCharsets.UTF_8);
        stubOpen(content);

        // when
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1"));

        // then
        assertEquals(2, parsed.blocks().size(), "应按空行切为两个 TEXT 块");
        assertTrue(parsed.blocks().stream().allMatch(b -> ContentBlockVO.TYPE_TEXT.equals(b.type())));
        assertEquals(0, parsed.blocks().get(0).meta().get("page"), "应携带 page 占位");
        assertTrue(parsed.images().isEmpty());
    }

    /**
     * 场景：源内容为不可解码的<b>非图片</b>二进制（不含任何受支持图片签名）。
     * 预期：抛出 {@link IllegalArgumentException}，消息含文件名与「不支持该文件类型」——
     * 该情形说明所选引擎不支持此类型，必须如实上报失败，不得以占位块伪装成解析成功。
     */
    @Test
    void should_throwException_when_parse_given_undecodableNonImageBytes() {
        // given：0xFF / 0xFE 为非法 UTF-8 字节且头部不命中 PNG / JPEG 签名
        byte[] binary = new byte[] {0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80, (byte) 0xC0, 0x41};
        stubOpen(binary);

        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1")));

        // then
        assertTrue(exception.getMessage().contains("不支持该文件类型"),
                "应明确提示引擎不支持该类型，实际: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("手册.pdf"),
                "提示应含文件名便于定位，实际: " + exception.getMessage());
    }

    /**
     * 场景：源字节命中 PNG 签名（89 50 4E 47）。
     * 预期：产出视觉直通产物——恰一个 IMAGE 块（img_path=基名、page 占位）+ 以基名为键的自身
     * 字节载荷；哈希为 32 位 hex（源字节 MD5 口径）。
     */
    @Test
    void should_produceImagePassthrough_when_parse_given_pngBytes() {
        // given：PNG 签名开头 + 任意后续字节（直通判定只看签名，不校验图像完整性）
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
        String key = "rag/100/source/截图.png";
        stubOpen(png, key);

        // when
        ParsedDocument parsed = parser.parse(new S3File(key), "auto", new MultiModelConfig("p1"));

        // then
        assertEquals(1, parsed.blocks().size(), "直通产物应恰含一个内容块");
        ContentBlockVO block = parsed.blocks().get(0);
        assertEquals(ContentBlockVO.TYPE_IMAGE, block.type(), "内容块类型应为 IMAGE");
        assertEquals("截图.png", block.meta().get("img_path"),
                "img_path 应为文件基名（与载荷键对齐，保证 Stage1a 引用绑定必命中）");
        assertEquals(0, block.meta().get("page"), "应携带 page=0 占位元数据");
        assertTrue(parsed.images().containsKey("截图.png"), "图片载荷应以基名为键");
        assertArrayEquals(png, parsed.images().get("截图.png"), "载荷值应为源图片字节");
        assertEquals(32, parsed.parsedTextHash().length(), "哈希应为源字节 MD5 的 32 位 hex");
    }

    /**
     * 场景：源字节命中 JPEG 签名（FF D8 FF）。
     * 预期：与 PNG 同口径产出 IMAGE 直通产物（JPG / JPEG 共享签名）。
     */
    @Test
    void should_produceImagePassthrough_when_parse_given_jpegBytes() {
        // given
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        stubOpen(jpeg);

        // when
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1"));

        // then
        assertEquals(1, parsed.blocks().size());
        assertEquals(ContentBlockVO.TYPE_IMAGE, parsed.blocks().get(0).type(), "JPEG 签名应同样直通");
        assertTrue(parsed.images().containsKey("手册.pdf"), "载荷键应为对象基名");
    }

    /**
     * 场景：对象键后缀声明为 .png，但源字节为可解码文本（伪造图片后缀）。
     * 预期：不产出 IMAGE 直通——识别以字节签名为准，按原路径产出真实 TEXT 块。
     */
    @Test
    void should_notPassthrough_when_parse_given_nonImageBytesDespiteImageSuffix() {
        // given
        byte[] text = "这根本不是图片".getBytes(StandardCharsets.UTF_8);
        String fakeKey = "rag/100/source/假图片.png";
        stubOpen(text, fakeKey);

        // when
        ParsedDocument parsed = parser.parse(new S3File(fakeKey), "auto", new MultiModelConfig("p1"));

        // then
        assertEquals(ContentBlockVO.TYPE_TEXT, parsed.blocks().get(0).type(),
                "字节非图片不得直通，按文本解码原路径处理");
        assertTrue(parsed.images().isEmpty(), "不应携带图片载荷");
    }

    /**
     * 场景：源内容为空字节（空文件）。
     * 预期：产出占位 TEXT 块（可解码但无有效文本，与「类型不支持」区分）。
     */
    @Test
    void should_returnPlaceholderBlock_when_parse_given_emptyContent() {
        // given
        stubOpen(new byte[0]);

        // when
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1"));

        // then
        assertEquals(1, parsed.blocks().size());
        assertEquals(ContentBlockVO.TYPE_TEXT, parsed.blocks().get(0).type());
    }

    /**
     * 场景：对象存储中源文件对象不存在（open 返回 empty）。
     * 预期：抛出 {@link IllegalArgumentException} 并携带「源文件对象不存在」提示。
     */
    @Test
    void should_throwException_when_parse_given_objectMissing() {
        // given
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.empty());

        // when // then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1")));
        assertTrue(exception.getMessage().contains("源文件对象不存在"));
    }

    /**
     * 场景：存储读取流抛出 IO 异常。
     * 预期：以 {@link RuntimeException} 传播并携带对象键上下文。
     */
    @Test
    void should_throwRuntimeException_when_parse_given_storageIoException() throws Exception {
        // given
        InputStream brokenStream = mock(InputStream.class);
        when(brokenStream.readAllBytes()).thenThrow(new IOException("s3 unavailable"));
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(brokenStream, 3L)));

        // when // then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1")));
        assertTrue(exception.getMessage().contains("读取源文件对象失败"));
    }

    /**
     * 场景：本地直读兜底声明的可读类型集合。
     * 预期：覆盖全部白名单类型（读字节而非引擎解析，故可读任意类型）。
     */
    @Test
    void should_coverAllWhitelistTypes_when_supportedTypes_given_fallback() {
        // when
        java.util.Set<FileType> supported = parser.supportedTypes();

        // then
        assertEquals(FileType.values().length, supported.size());
        assertTrue(supported.contains(FileType.PNG));
        assertTrue(supported.contains(FileType.CSV));
    }

    /**
     * 场景（≥1 切片不变量）：空文件产出的占位 TEXT 块交由通用策略切分。
     * 预期：切片数 ≥ 1（占位块正文非空白，通用策略不会丢弃）。
     */
    @Test
    void should_produceChunk_when_split_given_placeholderBlock() {
        // given
        stubOpen(new byte[0]);
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto", new MultiModelConfig("p1"));
        TokenCounter counter = mock(TokenCounter.class);
        when(counter.count(anyString())).thenReturn(3);

        // when
        List<ChunkVO> chunks = new GeneralChunkStrategy()
                .split(parsed.blocks().get(0), ChunkParams.defaults(), counter);

        // then
        assertFalse(chunks.isEmpty(), "占位块切分后切片数应 ≥ 1");
    }

    /**
     * stub 端口按默认对象键打开内容流。
     *
     * @param content 对象字节
     */
    private void stubOpen(byte[] content) {
        stubOpen(content, OBJECT_KEY);
    }

    /**
     * stub 端口按指定对象键打开内容流。
     *
     * @param content 对象字节
     * @param key     对象键
     */
    private void stubOpen(byte[] content, String key) {
        when(kbAssetStoragePort.open(key)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(content), content.length)));
    }
}