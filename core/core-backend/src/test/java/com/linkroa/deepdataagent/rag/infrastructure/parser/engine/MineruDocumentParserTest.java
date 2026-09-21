package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.infrastructure.client.MineruClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MineruDocumentParser} 单元测试。
 * <p>Mock MinerU 客户端与对象资产存储端口，验证 content_list 五类块到 ContentBlockVO 的类型映射、
 * caption 文本取值、page 与原始字段入 meta、按文件名 stem 定位，以及缺失内容 / 客户端拒绝 /
 * 存储 IO 异常 / 对象缺失等异常分支。口径说明：桶概念已退役，源文件读取统一经
 * {@link KbAssetStoragePort#open(String)} 按对象键定位，「对象不存在」语义由 Optional.empty 承载
 * （替代旧「存储服务返回 null 流」场景）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MineruDocumentParserTest {

    /** 测试对象键（含目录与扩展名）。 */
    private static final String OBJECT_KEY = "rag/100/source/report.pdf";

    /** 对象资产存储端口 Mock。 */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** MinerU 客户端 Mock。 */
    @Mock
    private MineruClient mineruClient;

    /** 被测对象（每个用例前新建，注入两个 Mock 依赖）。 */
    private MineruDocumentParser parser;

    /**
     * 每个用例前构造被测对象。
     */
    @BeforeEach
    void setUp() {
        parser = new MineruDocumentParser(mineruClient, kbAssetStoragePort);
    }

    /**
     * 场景：MinerU 返回含 text / image / table / equation / 未知类型的 content_list。
     * 预期：五类映射正确（未知 → GENERIC），image/table 取 caption、其余取 text，
     * meta 携带 page 与全部原始字段，parsedTextHash 为 32 位 hex，且以「report.pdf」与文件字节提交客户端。
     */
    @Test
    void should_mapAllBlockTypes_when_parse_given_contentListOfFiveTypes() {
        // given
        byte[] content = "dummy file bytes".getBytes(StandardCharsets.UTF_8);
        stubOpen(content);
        Map<String, List<MineruClient.Block>> results = new HashMap<>();
        results.put("report", List.of(
                new MineruClient.Block("text", 1, Map.of("text", "hello")),
                new MineruClient.Block("image", 1, Map.of("image_caption", "图1", "positions", "[[1,2]]")),
                new MineruClient.Block("table", 2, Map.of("table_caption", "表1", "table_body", "1|2")),
                new MineruClient.Block("equation", 2, Map.of("text", "x=1")),
                new MineruClient.Block("footer", 3, Map.of("text", "tail"))
        ));
        when(mineruClient.parse(any(byte[].class), eq("report.pdf"))).thenReturn(new MineruClient.ParsedResult(results));
        S3File s3File = new S3File(OBJECT_KEY);

        // when
        ParsedDocument parsed = parser.parse(s3File, "auto", new MultiModelConfig("profile-1"));

        // then
        List<ContentBlockVO> blocks = parsed.blocks();
        assertEquals(5, blocks.size(), "五类块应逐条映射、保持顺序");
        assertEquals(ContentBlockVO.TYPE_TEXT, blocks.get(0).type());
        assertEquals("hello", blocks.get(0).text());
        assertEquals(ContentBlockVO.TYPE_IMAGE, blocks.get(1).type());
        assertEquals("图1", blocks.get(1).text(), "image 块应取 image_caption 作为文本");
        assertEquals("[[1,2]]", blocks.get(1).meta().get("positions"), "原始字段应原样保留进 meta");
        assertEquals(1, blocks.get(1).meta().get("page"), "meta 应携带块页码");
        assertEquals(ContentBlockVO.TYPE_TABLE, blocks.get(2).type());
        assertEquals("表1", blocks.get(2).text(), "table 块应取 table_caption 作为文本");
        assertEquals(ContentBlockVO.TYPE_EQUATION, blocks.get(3).type());
        assertEquals("x=1", blocks.get(3).text());
        assertEquals(ContentBlockVO.TYPE_GENERIC, blocks.get(4).type(), "未知类型应兜底 GENERIC");
        assertEquals("tail", blocks.get(4).text());
        assertEquals(32, parsed.parsedTextHash().length(), "块拼接哈希 MD5 应为 32 位 hex");
        verify(mineruClient).parse(content, "report.pdf");
    }

    /**
     * 场景：MinerU 结果同 stem 下携带 images 载荷（图片名 → base64）。
     * 预期：解析产物 images 为解码后的图片字节，图片名原样保留。
     */
    @Test
    void should_attachDecodedImages_when_parse_given_resultsWithImagePayload() {
        // given
        byte[] content = "dummy".getBytes(StandardCharsets.UTF_8);
        stubOpen(content);
        Map<String, List<MineruClient.Block>> results = new HashMap<>();
        results.put("report", List.of(new MineruClient.Block("image", 1,
                Map.of("img_path", "images/a.png", "image_caption", "图1"))));
        Map<String, Map<String, String>> images = new HashMap<>();
        images.put("report", Map.of("a.png", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                "b.gif", Base64.getEncoder().encodeToString(new byte[]{9})));
        when(mineruClient.parse(any(byte[].class), eq("report.pdf")))
                .thenReturn(new MineruClient.ParsedResult(results, images));

        // when
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto",
                new MultiModelConfig("profile-1"));

        // then
        assertEquals(2, parsed.images().size());
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.images().get("a.png"));
        assertArrayEquals(new byte[]{9}, parsed.images().get("b.gif"));
    }

    /**
     * 场景：图片载荷中混有非法 base64（依赖数据异常）。
     * 预期：解码失败的单图被跳过并告警，其余图片与整篇解析产物不受影响。
     */
    @Test
    void should_skipUndecodableImage_when_parse_given_invalidBase64Payload() {
        // given
        byte[] content = "dummy".getBytes(StandardCharsets.UTF_8);
        stubOpen(content);
        Map<String, List<MineruClient.Block>> results = new HashMap<>();
        results.put("report", List.of(new MineruClient.Block("text", 0, Map.of("text", "x"))));
        Map<String, Map<String, String>> images = new HashMap<>();
        images.put("report", new LinkedHashMap<>(Map.of("good.png",
                Base64.getEncoder().encodeToString(new byte[]{7}))));
        images.get("report").put("broken.png", "!!!not-base64!!!");
        when(mineruClient.parse(any(byte[].class), eq("report.pdf")))
                .thenReturn(new MineruClient.ParsedResult(results, images));

        // when
        ParsedDocument parsed = parser.parse(new S3File(OBJECT_KEY), "auto",
                new MultiModelConfig("profile-1"));

        // then：仅坏图缺席，好图与内容块完整
        assertEquals(1, parsed.images().size());
        assertArrayEquals(new byte[]{7}, parsed.images().get("good.png"));
        assertEquals(1, parsed.blocks().size());
    }

    /**
     * 场景：对象键无扩展名（也作为 stem 定位键）。
     * 预期：提交客户端文件名与结果定位 stem 均为全名。
     */
    @Test
    void should_locateByFullName_when_parse_given_fileNameWithoutExtension() {
        // given
        String keyWithoutExtension = "rag/100/source/noext";
        byte[] content = "plain".getBytes(StandardCharsets.UTF_8);
        when(kbAssetStoragePort.open(keyWithoutExtension)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(content), content.length)));
        Map<String, List<MineruClient.Block>> results = new HashMap<>();
        results.put("noext", List.of(new MineruClient.Block("text", 0, Map.of("text", "v"))));
        when(mineruClient.parse(any(byte[].class), eq("noext"))).thenReturn(new MineruClient.ParsedResult(results));
        S3File s3File = new S3File(keyWithoutExtension);

        // when
        ParsedDocument parsed = parser.parse(s3File, "auto", new MultiModelConfig("profile-1"));

        // then
        assertEquals(1, parsed.blocks().size());
        verify(mineruClient).parse(content, "noext");
    }

    /**
     * 场景：MinerU 结果中缺少对应文件 stem 的 content_list（或为空）。
     * 预期：抛出 {@link IllegalArgumentException} 提示「解析结果缺少对应文件的内容」。
     */
    @Test
    void should_throwException_when_parse_given_missingStemContentList() {
        // given
        stubOpen(new byte[]{1, 2, 3});
        when(mineruClient.parse(any(byte[].class), anyString()))
                .thenReturn(new MineruClient.ParsedResult(Map.of("other", List.of())));
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertTrue(exception.getMessage().contains("缺少对应文件的内容"),
                "应提示缺少对应文件内容，实际提示: " + exception.getMessage());
    }

    /**
     * 场景：客户端因不支持的文件类型等直接拒绝（依赖失败）。
     * 预期：异常原样传播，不予本级兜底处理。
     */
    @Test
    void should_propagateClientException_when_parse_given_clientRejectsFile() {
        // given
        stubOpen(new byte[]{1, 2, 3});
        when(mineruClient.parse(any(byte[].class), anyString()))
                .thenThrow(new IllegalArgumentException("不支持的文件类型"));
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertEquals("不支持的文件类型", exception.getMessage(), "客户端异常应原样传播");
    }

    /**
     * 场景：存储服务返回流读取时抛出 IO 异常（依赖失败）。
     * 预期：以 {@link RuntimeException} 传播并携带对象键上下文，客户端不被调用。
     */
    @Test
    void should_throwRuntimeException_when_readObjectBytes_given_storageIoException() throws Exception {
        // given
        InputStream brokenStream = mock(InputStream.class);
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(brokenStream, 3L)));
        doThrow(new IOException("s3 unavailable")).when(brokenStream).readAllBytes();
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertTrue(exception.getMessage().contains("读取源文件对象失败"),
                "IO 异常应转换为读取失败提示，实际提示: " + exception.getMessage());
    }

    /**
     * 场景：源文件对象不存在（open 返回 empty）。
     * 预期：抛出 {@link IllegalArgumentException} 提示对象不存在。
     */
    @Test
    void should_throwException_when_readObjectBytes_given_objectMissing() {
        // given
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.empty());
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertTrue(exception.getMessage().contains("源文件对象不存在"),
                "对象缺失应提示源文件对象不存在，实际提示: " + exception.getMessage());
    }

    /**
     * stub 端口按对象键打开内容流。
     *
     * @param content 对象字节
     */
    private void stubOpen(byte[] content) {
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(new ByteArrayInputStream(content), content.length)));
    }
}
