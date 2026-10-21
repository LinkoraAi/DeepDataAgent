package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultParserCapabilityApi} 单元测试。
 * <p>覆盖：按知识库 RAG 引擎配置解析能力集合（LOCAL 含兜底可读类型 / MINERU 仅引擎自身集合 /
 * 空白 / 非法 JSON / 未知 provider）、上传准入校验（支持类型放行、不支持类型抛出含引擎与支持清单的
 * 可读异常）。</p>
 */
class DefaultParserCapabilityApiTest {

    /** MINERU 引擎配置 JSON（含必填 apiBaseUrl）。 */
    private static final String MINERU_CONFIG =
            "{\"engineType\":\"DOCUMENT_ENGINE\",\"parseEngine\":{\"provider\":\"MINERU\","
                    + "\"engineConfig\":{\"params\":{\"apiBaseUrl\":\"http://mineru.local\"}}}}";

    /** LOCAL 引擎配置 JSON。 */
    private static final String LOCAL_CONFIG =
            "{\"engineType\":\"DOCUMENT_ENGINE\",\"parseEngine\":{\"provider\":\"LOCAL\"}}";

    /** 被测对象（无外部依赖）。 */
    private DefaultParserCapabilityApi capabilityApi;

    @BeforeEach
    void setUp() {
        capabilityApi = new DefaultParserCapabilityApi();
    }

    /**
     * 场景：配置空白（库未配置解析引擎）。
     * 预期：按默认引擎 LOCAL 返回「内置解析 ∪ 兜底可读」集合，覆盖全部白名单类型（含图片）。
     */
    @Test
    void should_returnLocalTypes_when_supportedTypes_given_blankConfig() {
        // when
        Set<FileType> supported = capabilityApi.supportedTypes(null);

        // then
        assertEquals(FileType.values().length, supported.size(), "LOCAL 含兜底可读类型，应覆盖全部白名单类型");
        assertTrue(supported.contains(FileType.PDF));
        assertTrue(supported.contains(FileType.MD));
        assertTrue(supported.contains(FileType.PNG), "LOCAL 的图片能力由视觉直通兜底承载");
    }

    /**
     * 场景：MINERU 引擎配置。
     * 预期：返回 MinerU 能力集合（含表格与图片，不含 TXT/MD/CSV）。
     */
    @Test
    void should_returnMineruTypes_when_supportedTypes_given_mineruProvider() {
        // when
        Set<FileType> supported = capabilityApi.supportedTypes(MINERU_CONFIG);

        // then
        assertTrue(supported.contains(FileType.XLSX));
        assertTrue(supported.contains(FileType.JPEG));
        assertFalse(supported.contains(FileType.MD), "MinerU 不支持 MD");
    }

    /**
     * 场景：配置为非法 JSON。
     * 预期：不抛异常，按 LOCAL 能力集合返回（与摄入侧读取口径一致）。
     */
    @Test
    void should_returnLocalTypes_when_supportedTypes_given_invalidJson() {
        // when
        Set<FileType> supported = capabilityApi.supportedTypes("not-a-json");

        // then
        assertEquals(FileType.values().length, supported.size(), "非法 JSON 应按 LOCAL 能力集合处置");
        assertTrue(supported.contains(FileType.MD));
    }

    /**
     * 场景：parseEngine.provider 取值非法。
     * 预期：按 LOCAL 能力集合返回。
     */
    @Test
    void should_returnLocalTypes_when_supportedTypes_given_unknownProvider() {
        // when
        Set<FileType> supported = capabilityApi.supportedTypes(
                "{\"parseEngine\":{\"provider\":\"SOMETHING_ELSE\"}}");

        // then
        assertEquals(FileType.values().length, supported.size(), "非法 provider 应按 LOCAL 能力集合处置");
        assertTrue(supported.contains(FileType.MD));
    }

    /**
     * 场景：类型被当前引擎支持（含 LOCAL 的图片兜底路径）。
     * 预期：校验通过，不抛异常。
     */
    @Test
    void should_pass_when_ensureSupported_given_supportedType() {
        // when // then
        assertDoesNotThrow(() -> capabilityApi.ensureSupported(MINERU_CONFIG, FileType.XLSX));
        assertDoesNotThrow(() -> capabilityApi.ensureSupported(LOCAL_CONFIG, FileType.MD));
        assertDoesNotThrow(() -> capabilityApi.ensureSupported(LOCAL_CONFIG, FileType.PNG),
                "LOCAL 的图片由本地直读兜底承载，应允许上传");
    }

    /**
     * 场景：MINERU 引擎 + 纯文本类型（不在 MinerU 能力集合内）。
     * 预期：抛业务异常，提示含 MinerU 与支持清单。
     */
    @Test
    void should_throwException_when_ensureSupported_given_mineruUnsupportedText() {
        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> capabilityApi.ensureSupported(MINERU_CONFIG, FileType.MD));

        // then
        assertTrue(exception.getMessage().contains("MinerU"), "提示应含引擎名，实际: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("MD"), "提示应含文件类型，实际: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("XLSX"), "提示应含支持清单，实际: " + exception.getMessage());
    }
}