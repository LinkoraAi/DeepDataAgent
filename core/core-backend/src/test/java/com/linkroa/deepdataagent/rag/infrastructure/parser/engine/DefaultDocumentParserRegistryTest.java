package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultDocumentParserRegistry} 单元测试。
 * <p>覆盖文档类型按引擎分治的路由：LOCAL 引擎遇不支持类型（含 null）→ 返回本地直读兜底解析器
 * （图片视觉直通 / 纯文本直读）；MINERU 引擎遇不支持类型 → 抛 {@link IllegalArgumentException}
 * 并列出支持类型（严格模式，无兜底）；另验证 LOCAL 单例复用与表格类型（XLS/XLSX）已纳入 MinerU 能力集合。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultDocumentParserRegistryTest {

    /** 对象资产存储访问端口 Mock（解析器读源依赖，路由判定不触达）。 */
    @Mock
    private KbAssetStoragePort kbAssetStoragePort;

    /** 被测注册表。 */
    private DefaultDocumentParserRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultDocumentParserRegistry(kbAssetStoragePort);
    }

    /**
     * 场景：LOCAL 引擎 + 支持类型（PDF）。
     * 预期：返回 LOCAL 引擎解析器（非兜底）。
     */
    @Test
    void should_returnLocalEngine_when_resolve_given_localSupportingType() {
        // when
        DocumentParser parser = registry.resolve(localConfig(), FileType.PDF);

        // then
        assertInstanceOf(LocalTikaDocumentParser.class, parser);
    }

    /**
     * 场景：LOCAL 引擎 + 不支持类型（PNG）。
     * 预期：跳过 Tika、返回本地直读兜底解析器（LOCAL 的图片能力由视觉直通承载）。
     */
    @Test
    void should_returnFallback_when_resolve_given_localUnsupportedType() {
        // when
        DocumentParser parser = registry.resolve(localConfig(), FileType.PNG);

        // then
        assertInstanceOf(LocalReadFallbackDocumentParser.class, parser);
    }

    /**
     * 场景：LOCAL 引擎 + 未知类型（null）。
     * 预期：返回本地直读兜底解析器。
     */
    @Test
    void should_returnFallback_when_resolve_given_nullType() {
        // when
        DocumentParser parser = registry.resolve(localConfig(), null);

        // then
        assertInstanceOf(LocalReadFallbackDocumentParser.class, parser);
    }

    /**
     * 场景：MINERU 引擎 + 支持类型（PDF）。
     * 预期：返回 MinerU 引擎解析器。
     */
    @Test
    void should_returnMineruEngine_when_resolve_given_mineruSupportingType() {
        // when
        DocumentParser parser = registry.resolve(mineruConfig(), FileType.PDF);

        // then
        assertInstanceOf(MineruDocumentParser.class, parser);
    }

    /**
     * 场景：MINERU 引擎 + 表格类型（XLSX / XLS）。
     * 预期：返回 MinerU 引擎解析器（表格已纳入声明集合，不再回落本地直读）。
     */
    @Test
    void should_returnMineruEngine_when_resolve_given_mineruSpreadsheetType() {
        // when
        DocumentParser xlsxParser = registry.resolve(mineruConfig(), FileType.XLSX);
        DocumentParser xlsParser = registry.resolve(mineruConfig(), FileType.XLS);

        // then
        assertInstanceOf(MineruDocumentParser.class, xlsxParser);
        assertInstanceOf(MineruDocumentParser.class, xlsParser);
    }

    /**
     * 场景：MINERU 引擎 + 不支持类型（TXT）。
     * 预期：抛 {@link IllegalArgumentException}（严格路由，不回落本地直读），提示含支持清单。
     */
    @Test
    void should_throwException_when_resolve_given_mineruUnsupportedType() {
        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registry.resolve(mineruConfig(), FileType.TXT));

        // then
        assertTrue(exception.getMessage().contains("TXT"), "提示应含文件类型，实际: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("XLSX"), "提示应含支持类型清单，实际: " + exception.getMessage());
    }

    /**
     * 场景：解析配置为 null。
     * 预期：类型路由入口快速失败（与单参 resolve 一致）。
     */
    @Test
    void should_throwException_when_resolve_given_nullConfig() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(null, FileType.PDF));
    }

    /**
     * 构造 LOCAL 解析配置。
     *
     * @return LOCAL 解析配置
     */
    private static DocumentParseConfig localConfig() {
        return new DocumentParseConfig(ParseEngineProvider.LOCAL, null);
    }

    /**
     * 构造 MINERU 解析配置（含必填 apiBaseUrl）。
     *
     * @return MINERU 解析配置
     */
    private static DocumentParseConfig mineruConfig() {
        return new DocumentParseConfig(ParseEngineProvider.MINERU,
                new EngineConfig(Map.of("apiBaseUrl", "http://mineru.local")));
    }
}