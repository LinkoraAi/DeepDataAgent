package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalTikaDocumentParser} 单元测试。
 * <p>使用真实 Apache Tika（AutoDetectParser）解析内存文本流，验证空行分段为 TEXT 块、
 * page=0 占位 meta 与全文 MD5 哈希；并覆盖空白内容（解析失败）、源文件对象缺失
 * （{@link KbAssetStoragePort#open} 返回 empty）与存储读取 IO 异常三个异常分支。
 * 口径说明：桶概念已退役，源文件读取统一经 {@link KbAssetStoragePort#open(String)}
 * 按对象键定位，「对象不存在」语义由 Optional.empty 承载。</p>
 */
@ExtendWith(MockitoExtension.class)
class LocalTikaDocumentParserTest {

    /** 测试对象键。 */
    private static final String OBJECT_KEY = "rag/100/source/sample.txt";

    /** 测试对象键前缀（用于拼接失败提示断言）。 */
    private static final String OBJECT_KEY_MESSAGE_PREFIX = "读取源文件对象失败: ";

    /** 并发用例文档数（各篇内容互不相同，兼作并发线程数）。 */
    private static final int CONCURRENT_DOC_COUNT = 6;

    /** 并发用例单任务等待超时（秒），防线程池异常时测试挂死。 */
    private static final long CONCURRENT_TIMEOUT_SECONDS = 60L;

    /** 对象资产存储访问端口 Mock。 */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** 被测对象（每个用例前新建，注入 Mock 存储端口）。 */
    private LocalTikaDocumentParser parser;

    /**
     * 每个用例前构造被测对象：MockitoExtension 已在实例化后完成 Mock 注入。
     */
    @BeforeEach
    void setUp() {
        parser = new LocalTikaDocumentParser(kbAssetStoragePort);
    }

    /**
     * 场景：源文件为 UTF-8 纯文本，包含两个以空行分隔的段落。
     * 预期：解析产出非空 TEXT 块列表（段落内容完整保留、携带 page=0 占位），
     * 全文 MD5 为 32 位小写 hex。
     */
    @Test
    void should_returnTextBlocksAndMd5Hash_when_parse_given_textWithBlankLineParagraphs() {
        // given
        byte[] content = "第一段\n\n第二段内容".getBytes(StandardCharsets.UTF_8);
        stubOpen(content);
        S3File s3File = new S3File(OBJECT_KEY);

        // when
        ParsedDocument parsed = parser.parse(s3File, "auto", new MultiModelConfig("profile-1"));

        // then
        assertFalse(parsed.blocks().isEmpty(), "文本应按空行切分为非空块列表");
        assertTrue(parsed.blocks().stream().allMatch(block -> ContentBlockVO.TYPE_TEXT.equals(block.type())),
                "本地解析应只产出 TEXT 块");
        String joinedText = parsed.blocks().stream().map(ContentBlockVO::text)
                .reduce("", (left, right) -> left + "|" + right);
        assertTrue(joinedText.contains("第一段"), "应保留首段文本内容");
        assertTrue(joinedText.contains("第二段内容"), "应保留第二段文本内容");
        assertEquals(0, parsed.blocks().get(0).meta().get("page"), "本地解析 meta 应携带 page=0 占位");
        assertEquals(32, parsed.parsedTextHash().length(), "全文 MD5 应为 32 位 hex");
    }

    /**
     * 场景：源文件内容为空（0 字节，Tika 无法提取任何文本）。
     * 预期：切分后无任何内容块，抛出 {@link IllegalArgumentException} 提示解析失败。
     */
    @Test
    void should_throwException_when_parse_given_blankContent() {
        // given
        stubOpen(new byte[0]);
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertTrue(exception.getMessage().contains("未提取到任何文本内容"),
                "空白内容应提示解析失败，实际提示: " + exception.getMessage());
    }

    /**
     * 场景：对象存储中源文件对象不存在（open 返回 empty）。
     * 预期：抛出 {@link IllegalArgumentException} 并携带「源文件对象不存在」提示。
     */
    @Test
    void should_throwException_when_parse_given_objectMissing() {
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
     * 场景：存储服务返回的流读取时抛出 IO 异常（依赖失败）。
     * 预期：以 {@link RuntimeException} 传播并携带对象键上下文。
     */
    @Test
    void should_throwRuntimeException_when_parse_given_storageIoException() throws Exception {
        // given
        InputStream brokenStream = mock(InputStream.class);
        doThrow(new IOException("s3 unavailable"))
                .when(brokenStream).read(any(byte[].class), anyInt(), anyInt());
        when(kbAssetStoragePort.open(OBJECT_KEY)).thenReturn(Optional.of(
                new KbAssetStoragePort.OpenedObject(brokenStream, 10L)));
        S3File s3File = new S3File(OBJECT_KEY);

        // when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> parser.parse(s3File, "auto", new MultiModelConfig("profile-1")));
        assertEquals(OBJECT_KEY_MESSAGE_PREFIX + OBJECT_KEY, exception.getMessage(),
                "IO 异常应转换为带对象键上下文的运行时异常");
    }

    /**
     * 场景（D3 实证·前置任务 1.1）：同一 {@link LocalTikaDocumentParser} 实例被多线程并发调用、
     * 分别解析不同内容的源文件。
     * <p>预期：每个对象的并发产出与其串行产出逐条一致（块数、块文本、全文哈希均相等），
     * 跨文档无内容串扰。当前实现每次解析自建 {@link AutoDetectParser}，本用例即通过；
     * 若改为实例字段复用（组 4 落地），复跑本用例作为并发安全门禁。</p>
     */
    @Test
    void should_matchSerialResults_when_parseConcurrently_given_sharedParserInstance() throws Exception {
        // given：6 篇各含独有标记的多段文本，端口按对象键每次返回全新内容流（流不可重复消费）
        Map<String, byte[]> contents = buildDistinctContents();
        stubOpenPerKey(contents);
        // 串行基准：逐篇解析记录产出（块文本 + 全文哈希）
        Map<String, ParsedDocument> baseline = new LinkedHashMap<>();
        for (String objectKey : contents.keySet()) {
            baseline.put(objectKey, parser.parse(new S3File(objectKey), "auto", new MultiModelConfig("profile-1")));
        }

        // when：同一解析器实例、多线程并发重复解析全部对象键
        Map<String, ParsedDocument> concurrent = parseConcurrently(contents);

        // then：每篇并发产出与其串行基准逐条一致，无跨文档污染
        for (String objectKey : contents.keySet()) {
            assertSameParsed(baseline.get(objectKey), concurrent.get(objectKey), objectKey);
        }
    }

    /**
     * 场景（D3 实证·前置任务 1.1 的直接依据）：单个 {@link AutoDetectParser} 实例被多线程并发复用，
     * 各自以独立 handler / Metadata / ParseContext 解析不同文本。
     * <p>预期：共享解析器实例下的并发提取文本与其串行提取逐篇一致、无交叉污染。
     * 本用例直接证伪/证实「实例字段复用」形态的线程安全性，是组 4 落地形态裁决的核心证据；
     * 不通过则组 4 回退 {@code ThreadLocal<AutoDetectParser>}。</p>
     */
    @Test
    void should_matchSerialText_when_extractConcurrently_given_sharedAutoDetectParser() throws Exception {
        // given：6 段互不相同、含独有标记的文本
        Map<String, byte[]> contents = buildDistinctContents();
        AutoDetectParser sharedParser = new AutoDetectParser();
        // 串行基准：每次新建独立解析器提取全文
        Map<String, String> baseline = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            baseline.put(entry.getKey(), extractTextWith(new AutoDetectParser(), entry.getValue()));
        }

        // when：单一共享解析器实例并发提取各篇全文（每次新建承载单次调用状态的对象）
        ExecutorService pool = Executors.newFixedThreadPool(contents.size());
        Map<String, String> concurrent = new LinkedHashMap<>();
        try {
            Map<String, Future<String>> futures = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                futures.put(entry.getKey(), pool.submit(() -> extractTextWith(sharedParser, entry.getValue())));
            }
            for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
                concurrent.put(entry.getKey(), entry.getValue().get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // then：共享实例并发提取结果与串行逐篇一致，证明 AutoDetectParser 可并发复用
        for (String objectKey : contents.keySet()) {
            assertEquals(baseline.get(objectKey), concurrent.get(objectKey),
                    "共享 AutoDetectParser 并发提取应与串行一致：" + objectKey);
        }
    }

    /**
     * 构造并发用例内容集：6 篇对象键 → 各含独有标记的多段 UTF-8 文本。
     *
     * @return 有序对象键到内容的映射
     */
    private Map<String, byte[]> buildDistinctContents() {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (int i = 0; i < CONCURRENT_DOC_COUNT; i++) {
            String text = "文档" + i + "标题\n\n文档" + i + "段落甲内容\n\n文档" + i + "段落乙内容";
            contents.put("rag/100/source/doc-" + i + ".txt", text.getBytes(StandardCharsets.UTF_8));
        }
        return contents;
    }

    /**
     * stub 端口按任意对象键打开内容流：每次调用返回该键内容的全新流（流不可重复消费）。
     *
     * @param contents 对象键 → 字节内容
     */
    private void stubOpenPerKey(Map<String, byte[]> contents) {
        when(kbAssetStoragePort.open(anyString())).thenAnswer(invocation -> {
            String objectKey = invocation.getArgument(0);
            byte[] content = contents.get(objectKey);
            return Optional.of(new KbAssetStoragePort.OpenedObject(
                    new ByteArrayInputStream(content), content.length));
        });
    }

    /**
     * 以固定线程池并发解析各对象键（同一解析器实例），返回对象键 → 产出映射。
     *
     * @param contents 对象键 → 字节内容
     * @return 对象键 → 并发解析产出
     * @throws Exception 线程池提交或结果获取异常
     */
    private Map<String, ParsedDocument> parseConcurrently(Map<String, byte[]> contents) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contents.size());
        Map<String, ParsedDocument> results = new LinkedHashMap<>();
        try {
            Map<String, Future<ParsedDocument>> futures = new LinkedHashMap<>();
            for (String objectKey : contents.keySet()) {
                futures.put(objectKey, pool.submit(
                        () -> parser.parse(new S3File(objectKey), "auto", new MultiModelConfig("profile-1"))));
            }
            for (Map.Entry<String, Future<ParsedDocument>> entry : futures.entrySet()) {
                results.put(entry.getKey(), entry.getValue().get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /**
     * 断言两份解析产出逐条一致（全文哈希 + 块数量 + 块类型 + 块文本）。
     *
     * @param expected  串行基准产出
     * @param actual    并发产出
     * @param objectKey 对象键（仅用于失败信息定位）
     */
    private void assertSameParsed(ParsedDocument expected, ParsedDocument actual, String objectKey) {
        assertEquals(expected.parsedTextHash(), actual.parsedTextHash(), "全文哈希应一致：" + objectKey);
        assertEquals(expected.blocks().size(), actual.blocks().size(), "块数量应一致：" + objectKey);
        for (int i = 0; i < expected.blocks().size(); i++) {
            ContentBlockVO expectedBlock = expected.blocks().get(i);
            ContentBlockVO actualBlock = actual.blocks().get(i);
            assertEquals(expectedBlock.type(), actualBlock.type(), "块类型应一致：" + objectKey + "#" + i);
            assertEquals(expectedBlock.text(), actualBlock.text(), "块文本应一致：" + objectKey + "#" + i);
        }
    }

    /**
     * 用指定解析器提取全文文本：每次新建承载单次调用状态的 handler / Metadata / ParseContext。
     *
     * @param autoDetectParser 复用的解析器实例
     * @param content          待解析字节内容
     * @return 提取的全文文本
     * @throws Exception 解析失败
     */
    private String extractTextWith(AutoDetectParser autoDetectParser, byte[] content) throws Exception {
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(content))) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            autoDetectParser.parse(in, handler, new Metadata(), new ParseContext());
            return handler.toString();
        }
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
