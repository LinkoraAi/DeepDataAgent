package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkStrategyFileTypeValidator} 单元测试。
 * <p>覆盖「分块策略 × 文档类型」矩阵：匹配放行、不匹配拒绝（400）、未配置策略按 GENERAL 放行、
 * 非法 JSON 拒绝、字符串再包一层形态可解析。</p>
 */
class ChunkStrategyFileTypeValidatorTest {

    /**
     * 场景：GENERAL 策略 + 图片类型（矩阵内允许全部白名单类型）。
     * 预期：放行，不抛异常。
     */
    @Test
    void should_pass_when_validate_given_generalModeWithImageType() {
        // given
        String strategy = "{\"chunkMode\":\"GENERAL\"}";

        // when // then
        assertDoesNotThrow(() -> ChunkStrategyFileTypeValidator.validate(FileType.PNG, strategy));
    }

    /**
     * 场景：TABLE 策略 + PDF 类型（矩阵不允许）。
     * 预期：抛 400，消息含策略名与类型名。
     */
    @Test
    void should_throwBadRequest_when_validate_given_tableModeWithPdfType() {
        // given
        String strategy = "{\"chunkMode\":\"TABLE\"}";

        // when
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyFileTypeValidator.validate(FileType.PDF, strategy));

        // then
        assertTrue(exception.getMessage().contains("TABLE"));
        assertTrue(exception.getMessage().contains("PDF"));
    }

    /**
     * 场景：BOOK 策略 + 图片类型（矩阵不允许）。
     * 预期：抛 400。
     */
    @Test
    void should_throwBadRequest_when_validate_given_bookModeWithImageType() {
        // given
        String strategy = "{\"chunkMode\":\"BOOK\"}";

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyFileTypeValidator.validate(FileType.PNG, strategy));
    }

    /**
     * 场景：PRESENTATION 策略 + PPTX（矩阵允许）。
     * 预期：放行。
     */
    @Test
    void should_pass_when_validate_given_presentationModeWithPptxType() {
        // when // then
        assertDoesNotThrow(() -> ChunkStrategyFileTypeValidator.validate(FileType.PPTX,
                "{\"chunkMode\":\"PRESENTATION\"}"));
    }

    /**
     * 场景：QA 策略 + CSV（矩阵允许）与 PDF（矩阵不允许）。
     * 预期：CSV 放行，PDF 拒绝。
     */
    @Test
    void should_distinguishCsvAndPdf_when_validate_given_qaMode() {
        // given
        String strategy = "{\"chunkMode\":\"QA\"}";

        // when // then
        assertDoesNotThrow(() -> ChunkStrategyFileTypeValidator.validate(FileType.CSV, strategy));
        assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyFileTypeValidator.validate(FileType.PDF, strategy));
    }

    /**
     * 场景：未配置策略（null / 空白）。
     * 预期：按 GENERAL 处理，全部白名单类型放行。
     */
    @Test
    void should_passAllTypes_when_validate_given_blankStrategy() {
        // when // then
        assertDoesNotThrow(() -> ChunkStrategyFileTypeValidator.validate(FileType.PNG, null));
        assertDoesNotThrow(() -> ChunkStrategyFileTypeValidator.validate(FileType.PDF, "   "));
    }

    /**
     * 场景：策略 JSON 被再包一层字符串（存量形态）。
     * 预期：可解包并按内层模式判定——TABLE 内层 + PDF 拒绝。
     */
    @Test
    void should_unwrapNestedString_when_validate_given_doubleWrappedStrategy() {
        // given：内层 JSON 字符串
        String strategy = "\"{\\\"chunkMode\\\":\\\"TABLE\\\"}\"";

        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyFileTypeValidator.validate(FileType.PDF, strategy));
    }

    /**
     * 场景：策略 JSON 非法。
     * 预期：抛 400。
     */
    @Test
    void should_throwBadRequest_when_validate_given_invalidJson() {
        // when // then
        assertThrows(DeepDataAgentException.class,
                () -> ChunkStrategyFileTypeValidator.validate(FileType.PDF, "{illegal"));
    }
}