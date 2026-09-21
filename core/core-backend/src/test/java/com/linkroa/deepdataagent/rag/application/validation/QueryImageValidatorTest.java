package com.linkroa.deepdataagent.rag.application.validation;

import com.linkroa.deepdataagent.rag.application.contract.QueryImageDTO;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.InvalidQueryImageException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueryImageValidator} 单元测试（通路 A 检索附图入参校验）。
 * <p>覆盖：① 无附件（缺字段 / 空列表）与基线一致返回空列表；② 合法附件解码；
 * ③ 数量超限（4 张）拒绝；④ 单图字节超限拒绝；⑤ contentType 与魔数不符拒绝；
 * ⑥ 非 base64 拒绝；⑦ 白名单外类型 / 空白 contentType / 空白 data / 空附件元素拒绝；
 * ⑧ 拒绝消息含「第几张」定位（禁止静默丢弃或部分生效）；
 * ⑨ 拒绝异常类型为 {@link InvalidQueryImageException}（
 * 全局异常处理映射真实 HTTP 400），并保留一例按父类型
 * {@link DeepDataAgentException} 断言的子类型兼容性用例。</p>
 *
 * @author DeepDataAgent
 */
class QueryImageValidatorTest {

    /** PNG 魔数 + 载荷 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};

    /** JPEG 魔数 + 载荷 */
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x03};

    /** GIF 魔数（"GIF89a"）+ 载荷 */
    private static final byte[] GIF_BYTES = "GIF89a-payload".getBytes(StandardCharsets.US_ASCII);

    /** WEBP 容器（RIFF + 长度 + WEBP）+ 载荷 */
    private static final byte[] WEBP_BYTES = "RIFF....WEBP-payload".getBytes(StandardCharsets.US_ASCII);

    /** 文本类型图片（白名单外，用于伪装拒绝） */
    private static final String UNSUPPORTED_TYPE = "text/plain";

    /**
     * 构造 base64 附图 DTO。
     *
     * @param contentType 声明的内容类型
     * @param bytes       图片字节
     * @return 附图 DTO
     */
    private static QueryImageDTO image(String contentType, byte[] bytes) {
        return new QueryImageDTO(contentType, Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void should_returnEmptyList_when_validateAndDecode_given_noImagesField() {
        // given // when
        List<LlmImage> fromNull = QueryImageValidator.validateAndDecode(null);
        List<LlmImage> fromEmpty = QueryImageValidator.validateAndDecode(List.of());

        // then：纯文本请求路径零影响
        assertTrue(fromNull.isEmpty());
        assertTrue(fromEmpty.isEmpty());
    }

    @Test
    void should_returnDecodedImage_when_validateAndDecode_given_validSinglePng() {
        // given
        List<QueryImageDTO> images = List.of(image("image/png", PNG_BYTES));

        // when
        List<LlmImage> decoded = QueryImageValidator.validateAndDecode(images);

        // then：解码字节与声明类型逐字段一致
        assertEquals(1, decoded.size());
        assertEquals("image/png", decoded.get(0).contentType());
        assertArrayEquals(PNG_BYTES, decoded.get(0).content());
    }

    @Test
    void should_returnImagesInOrder_when_validateAndDecode_given_threeSupportedFormats() {
        // given：白名单四类中的三类（JPEG / GIF / WEBP），恰好等于数量上限
        List<QueryImageDTO> images = List.of(
                image("image/jpeg", JPEG_BYTES),
                image("image/gif", GIF_BYTES),
                image("image/webp", WEBP_BYTES));

        // when
        List<LlmImage> decoded = QueryImageValidator.validateAndDecode(images);

        // then
        assertEquals(3, decoded.size());
        assertEquals(List.of("image/jpeg", "image/gif", "image/webp"),
                decoded.stream().map(LlmImage::contentType).toList());
        assertArrayEquals(JPEG_BYTES, decoded.get(0).content());
        assertArrayEquals(GIF_BYTES, decoded.get(1).content());
        assertArrayEquals(WEBP_BYTES, decoded.get(2).content());
    }

    @Test
    void should_normalizeContentTypeToLowerCase_when_validateAndDecode_given_upperCaseMimeType() {
        // given：MIME 类型大小写不敏感（RFC 口径）
        List<QueryImageDTO> images = List.of(image(" IMAGE/PNG ", PNG_BYTES));

        // when
        List<LlmImage> decoded = QueryImageValidator.validateAndDecode(images);

        // then
        assertEquals("image/png", decoded.get(0).contentType());
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_fourImages() {
        // given
        List<QueryImageDTO> images = List.of(
                image("image/png", PNG_BYTES), image("image/png", PNG_BYTES),
                image("image/png", PNG_BYTES), image("image/png", PNG_BYTES));

        // when：刻意保留父类型断言（子类型兼容口径）——细化为 InvalidQueryImageException
        // 后，旧有按 DeepDataAgentException 捕获/断言的调用方仍全部命中，零破坏兼容
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then
        assertTrue(exception instanceof InvalidQueryImageException,
                "数量超限拒绝必须抛真实 400 专用异常子类型");
        assertTrue(exception.getMessage().contains("超过上限"));
        assertTrue(exception.getMessage().contains(String.valueOf(
                MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST)));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_oversizedSingleImage() {
        // given：仅超限 1 字节（魔数合法，失败原因必须落在体积）
        byte[] oversized = new byte[(int) MultimodalConstraints.MAX_IMAGE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        List<QueryImageDTO> images = List.of(image("image/png", oversized));

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then
        assertTrue(exception.getMessage().contains("单图上限"));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_contentTypeMismatchMagic() {
        // given：声明 PNG 但字节实为 JPEG
        List<QueryImageDTO> images = List.of(image("image/png", JPEG_BYTES));

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then：错误信息含定位与实际格式识别结果
        assertTrue(exception.getMessage().contains("第 1 张附图"));
        assertTrue(exception.getMessage().contains("不一致"));
        assertTrue(exception.getMessage().contains("JPEG"));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_illegalBase64() {
        // given：含 base64 字符集外的符号
        List<QueryImageDTO> images = List.of(new QueryImageDTO("image/png", "非法@base64!字符"));

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then
        assertTrue(exception.getMessage().contains("第 1 张附图"));
        assertTrue(exception.getMessage().contains("base64 解码失败"));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_unsupportedContentType() {
        // given：白名单外的合法 base64（且魔数为 PNG）
        List<QueryImageDTO> images = List.of(image(UNSUPPORTED_TYPE, PNG_BYTES));

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then
        assertTrue(exception.getMessage().contains("不受支持"));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_blankContentTypeOrBlankData() {
        // given
        List<QueryImageDTO> blankType = List.of(new QueryImageDTO("  ", "aGVsbG8="));
        List<QueryImageDTO> blankData = List.of(new QueryImageDTO("image/png", " "));

        // when
        InvalidQueryImageException typeFailure = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(blankType));
        InvalidQueryImageException dataFailure = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(blankData));

        // then
        assertTrue(typeFailure.getMessage().contains("contentType 不能为空"));
        assertTrue(dataFailure.getMessage().contains("data（base64）不能为空"));
    }

    @Test
    void should_throwRejectException_when_validateAndDecode_given_nullAttachmentElement() {
        // given：列表含空元素
        List<QueryImageDTO> images = new ArrayList<>();
        images.add(null);

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then
        assertTrue(exception.getMessage().contains("第 1 张附图"));
    }

    @Test
    void should_rejectWholeRequestWithoutPartialAcceptance_when_validateAndDecode_given_secondImageInvalid() {
        // given：首张合法、次张魔数不符（禁止「部分附件生效」的静默降级）
        List<QueryImageDTO> images = List.of(
                image("image/png", PNG_BYTES), image("image/jpeg", PNG_BYTES));

        // when
        InvalidQueryImageException exception = assertThrows(InvalidQueryImageException.class,
                () -> QueryImageValidator.validateAndDecode(images));

        // then：定位到第二张，整请求被拒绝（不返回「仅首张生效」的部分产物）
        assertTrue(exception.getMessage().contains("第 2 张附图"));
    }
}
