package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagEngineConfigValidator} 单元测试：引擎类型白名单校验、语言键退役口径与库级分块策略委托校验。
 * <p>语言真相源收敛回 {@code knowledge_base.language}
 * 列，值域校验已迁移至 {@link KnowledgeBaseValidator#validateLanguage(String)}；
 * {@code rag_engine_config} JSONB 内存量 language 键一律忽略不再拦截（存量不清理）。
 * 覆盖：退役语言键任意取值放行、缺省与空白按未配置通过、非法 JSON 写入口前置拒绝；
 * 分块策略子节点（对象 / 字符串两种形态）经 {@link ChunkStrategyConfigValidator} 拒绝式校验。</p>
 */
class RagEngineConfigValidatorTest {

    @Test
    void should_ignoreLegacyLanguageKey_when_validate_given_anyLanguageValueInJson() {
        // given & then：language 键已退役——合法全名、值域外语言、历史裸码、非文本类型一律不拦截
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate(
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"language\":\"Japanese\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":\"Portuguese\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":\"ja\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":123}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":null}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":\"  \"}"));
    }

    @Test
    void should_pass_when_validate_given_missingOrBlankConfig() {
        // given & then：整段空白 / null / 无引擎键配置按未配置通过
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate(null));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("  "));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":\"DOCUMENT_ENGINE\"}"));
    }

    @Test
    void should_pass_when_validate_given_missingOrBlankEngineType() {
        // given & then：engineType 缺失 / null / 空白按默认文档引擎放行
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"language\":\"Chinese\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":null}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":\"  \"}"));
    }

    @Test
    void should_pass_when_validate_given_caseVariantDocumentEngine() {
        // given & then：仅 DOCUMENT_ENGINE 命中白名单（trim、大小写不敏感）
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":\"document_engine\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":\" DOCUMENT_ENGINE \"}"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_mediaEngineType() {
        // given：占位媒体引擎本期不可选用
        String configJson = "{\"engineType\":\"MEDIA_ENGINE\"}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RagEngineConfigValidator.validate(configJson));

        // then：400 明确提示本期不提供该引擎类型
        assertTrue(exception.getMessage().contains("本期不提供该引擎类型"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_unknownEngineType() {
        // given & then：白名单外的非法引擎串同样拒绝（引擎类型不得参与任何运行期分支）
        assertThrows(DeepDataAgentException.class,
                () -> RagEngineConfigValidator.validate("{\"engineType\":\"VIDEO_ENGINE\"}"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_invalidJson() {
        // when // then：非法 JSON / 非对象根在写入口即拒绝（读取端已按数据损坏拒绝，前置拦截）
        assertThrows(DeepDataAgentException.class, () -> RagEngineConfigValidator.validate("{oops"));
        assertThrows(DeepDataAgentException.class, () -> RagEngineConfigValidator.validate("[\"DOCUMENT_ENGINE\"]"));
    }

    @Test
    void should_pass_when_validate_given_legalChunkStrategyNode() {
        // given & then：库级分块策略合法（含边界值与仅模式名无参数）随引擎类型一并放行
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate(
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                        + "\"modeConfig\":{\"params\":{\"chunk_token_num\":2048,\"overlapped_percent\":30,"
                        + "\"delimiter\":\"\\\\n\"}}}}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate(
                "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"TABLE\"}}"));
    }

    @Test
    void should_pass_when_validate_given_missingOrEmptyChunkStrategyNode() {
        // given & then：chunkStrategy 缺失 / null / 空对象按库级未配置放行（保持引擎类型校验既有宽松 tone）
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"engineType\":\"DOCUMENT_ENGINE\"}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"chunkStrategy\":null}"));
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate("{\"chunkStrategy\":{}}"));
    }

    @Test
    void should_pass_when_validate_given_legacyDeprecatedKeysInChunkStrategy() {
        // given：存量库配置的分块参数含已废弃的父子块 / 上下文长度键
        String configJson = "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                + "\"modeConfig\":{\"params\":{\"chunk_token_num\":1024,\"enable_children\":true}}}}";

        // when & then：编辑其他字段后保存成功，废弃键忽略不报错
        assertDoesNotThrow(() -> RagEngineConfigValidator.validate(configJson));
    }

    @Test
    void should_throwBadRequest_when_validate_given_zeroChunkTokenNum() {
        // given：库级分块参数把 token 预算写成 0
        String configJson = "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"GENERAL\","
                + "\"modeConfig\":{\"params\":{\"chunk_token_num\":0}}}}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RagEngineConfigValidator.validate(configJson));

        // then：400 指明字段与值域 (0,2048]，SHALL NOT 回退默认 512 后保存成功
        assertTrue(exception.getMessage().contains("chunk_token_num"));
        assertTrue(exception.getMessage().contains("(0,2048]"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_unknownChunkMode() {
        // given：库级分块模式写了白名单外的 auto
        String configJson = "{\"engineType\":\"DOCUMENT_ENGINE\",\"chunkStrategy\":{\"chunkMode\":\"auto\"}}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> RagEngineConfigValidator.validate(configJson));

        // then：拒绝并列出七枚举合法值
        assertTrue(exception.getMessage().contains("GENERAL/QA/BOOK/LAWS/TABLE/PRESENTATION/ONE"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_paramsWithNonGeneralMode() {
        // given & then：QA 模式携带 params 在库级入口同样拒绝
        assertThrows(DeepDataAgentException.class, () -> RagEngineConfigValidator.validate(
                "{\"chunkStrategy\":{\"chunkMode\":\"QA\",\"modeConfig\":{\"params\":{\"chunk_token_num\":512}}}}"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_illegalChunkStrategyWrappedAsString() {
        // given：chunkStrategy 以字符串形态承载（存量两种形态之一），内层为非法 JSON
        String configJson = "{\"chunkStrategy\":\"{illegal-json\"}";

        // when // then：解包后前置拒绝，非法配置不得进入落库流程
        assertThrows(DeepDataAgentException.class, () -> RagEngineConfigValidator.validate(configJson));
    }
}
