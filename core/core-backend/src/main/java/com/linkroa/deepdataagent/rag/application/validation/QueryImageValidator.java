package com.linkroa.deepdataagent.rag.application.validation;

import com.linkroa.deepdataagent.rag.application.contract.QueryImageDTO;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import com.linkroa.deepdataagent.shared.exception.InvalidQueryImageException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索附图入参校验器（通路 A）。
 * <p>位于 API 入参校验层（controller 与其共用的参数校验环节），职责是把请求体内的
 * 内联图片校验并解码为领域侧 {@link LlmImage}：<b>任一附件非法即整请求拒绝</b>
 * （抛 {@link InvalidQueryImageException}，由全局异常处理映射<b>真实 HTTP 400</b>，
 * 区别于一般业务异常的 200 包装形态），
 * MUST NOT 静默丢弃或截断后继续（避免「部分附件生效」的静默降级）。</p>
 *
 * <p>校验规则（错误信息均含定位：第几张 + 原因）：</p>
 * <ul>
 *   <li>数量：不超过 {@link MultimodalConstraints#MAX_IMAGES_PER_QUERY_REQUEST}；</li>
 *   <li>内容类型：落在白名单 {@code image/jpeg|png|gif|webp}（大小写不敏感，归一为小写）；</li>
 *   <li>base64：可解码（非 base64 字符即拒绝）；</li>
 *   <li>体积：单图解码字节数不超过 {@link MultimodalConstraints#MAX_IMAGE_BYTES}；</li>
 *   <li>真实性：文件头魔数与声明的 contentType 一致（防伪装类型与空/损坏载荷进入转译提示词）。</li>
 * </ul>
 *
 * <p>无外部依赖、无状态静态工具（与 {@code RetrievalConfigValidator} 同形态）；
 * 校验须在事务与任何 LLM 调用开启前完成。</p>
 *
 * @author DeepDataAgent
 */
public final class QueryImageValidator {

    /** MIME 类型：JPEG */
    private static final String IMAGE_JPEG = "image/jpeg";

    /** MIME 类型：PNG */
    private static final String IMAGE_PNG = "image/png";

    /** MIME 类型：GIF */
    private static final String IMAGE_GIF = "image/gif";

    /** MIME 类型：WEBP */
    private static final String IMAGE_WEBP = "image/webp";

    /** 受支持的图片内容类型白名单 */
    private static final Set<String> SUPPORTED_CONTENT_TYPES =
            Set.of(IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF, IMAGE_WEBP);

    /** JPEG 文件头魔数：FF D8 FF */
    private static final int[] MAGIC_JPEG = {0xFF, 0xD8, 0xFF};

    /** PNG 文件头魔数：89 50 4E 47 */
    private static final int[] MAGIC_PNG = {0x89, 0x50, 0x4E, 0x47};

    /** GIF 文件头魔数：47 49 46（"GIF"） */
    private static final int[] MAGIC_GIF = {0x47, 0x49, 0x46};

    /** WEBP 文件头魔数：0-3 字节 "RIFF" */
    private static final int[] MAGIC_WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};

    /** WEBP 文件头魔数：8-11 字节 "WEBP" */
    private static final int[] MAGIC_WEBP_TAG = {0x57, 0x45, 0x42, 0x50};

    /** WEBP 容器中标识 "WEBP" 的字节偏移 */
    private static final int WEBP_TAG_OFFSET = 8;

    /** 格式未知时的日志/错误占位标签 */
    private static final String FORMAT_UNKNOWN = "未知";

    /** 错误信息中的附件序号前缀 */
    private static final String INDEX_PREFIX = "第 ";

    /** 错误信息中的附件序号后缀 */
    private static final String INDEX_SUFFIX = " 张附图";

    /** 字节单位后缀（错误信息可读性） */
    private static final String BYTE_UNIT = " bytes";

    /**
     * 工具校验器禁止实例化。
     */
    private QueryImageValidator() {
        // 静态校验工具类不允许构造实例
    }

    /**
     * 校验并解码检索附图（空请求与未携带附件均返回空列表，纯文本检索路径零影响）。
     *
     * @param images 请求携带的附图列表（可空 / 可空列表）
     * @return 解码后的图片载荷列表（顺序与入参一致，contentType 归一为小写）；无附件返回空列表
     * @throws InvalidQueryImageException 任一附件非法（真实 HTTP 400，含第几张与原因定位）
     */
    public static List<LlmImage> validateAndDecode(List<QueryImageDTO> images) {
        if (CollectionUtils.isEmpty(images)) {
            return List.of();
        }
        if (images.size() > MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST) {
            throw new InvalidQueryImageException("检索附图数量 " + images.size()
                    + " 超过上限 " + MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST
                    + "，请减少附件后重试");
        }
        List<LlmImage> decoded = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            decoded.add(validateOne(i + 1, images.get(i)));
        }
        return List.copyOf(decoded);
    }

    /**
     * 校验单张附图：内容类型白名单 → base64 解码 → 字节上限 → 魔数一致性。
     *
     * @param index 附件序号（从 1 起，用于错误定位）
     * @param image 待校验附图，可为空
     * @return 解码后的图片载荷
     * @throws InvalidQueryImageException 任一规则不通过（真实 HTTP 400）
     */
    private static LlmImage validateOne(int index, QueryImageDTO image) {
        if (ObjectUtils.isEmpty(image)) {
            throw new InvalidQueryImageException(located(index, "附件内容为空"));
        }
        String contentType = normalizeContentType(index, image.contentType());
        if (StringUtils.isBlank(image.data())) {
            throw new InvalidQueryImageException(located(index, "data（base64）不能为空"));
        }
        byte[] content = decodeBase64(index, image.data());
        if (content.length > MultimodalConstraints.MAX_IMAGE_BYTES) {
            throw new InvalidQueryImageException(located(index, "大小 " + content.length + BYTE_UNIT
                    + " 超过单图上限 " + MultimodalConstraints.MAX_IMAGE_BYTES + BYTE_UNIT));
        }
        String actualFormat = detectFormat(content);
        if (!StringUtils.equals(expectedFormat(contentType), actualFormat)) {
            throw new InvalidQueryImageException(located(index, "contentType（" + contentType
                    + "）与图片实际格式（" + actualFormat + "）不一致"));
        }
        return new LlmImage(contentType, content);
    }

    /**
     * 归一并校验内容类型：trim + 小写（MIME 类型大小写不敏感），白名单外一律拒绝。
     *
     * @param index       附件序号（错误定位用）
     * @param rawType     原始 contentType，可为空
     * @return 归一后的 contentType（小写）
     * @throws InvalidQueryImageException 空白或白名单外（真实 HTTP 400）
     */
    private static String normalizeContentType(int index, String rawType) {
        if (StringUtils.isBlank(rawType)) {
            throw new InvalidQueryImageException(located(index, "contentType 不能为空"));
        }
        String normalized = rawType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CONTENT_TYPES.contains(normalized)) {
            throw new InvalidQueryImageException(located(index, "contentType（" + rawType
                    + "）不受支持，仅允许 image/jpeg、image/png、image/gif、image/webp"));
        }
        return normalized;
    }

    /**
     * base64 解码（严格模式：非 base64 字符即拒绝）。
     *
     * @param index 附件序号（错误定位用）
     * @param data  base64 文本
     * @return 解码字节
     * @throws InvalidQueryImageException 非法 base64（真实 HTTP 400）
     */
    private static byte[] decodeBase64(int index, String data) {
        try {
            return Base64.getDecoder().decode(data.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryImageException(located(index, "base64 解码失败：" + e.getMessage()));
        }
    }

    /**
     * contentType → 期望格式标签（与 {@link #detectFormat} 同源口径）。
     *
     * @param contentType 归一后的 contentType（白名单内）
     * @return 格式标签（JPEG / PNG / GIF / WEBP）
     */
    private static String expectedFormat(String contentType) {
        return switch (contentType) {
            case IMAGE_JPEG -> "JPEG";
            case IMAGE_PNG -> "PNG";
            case IMAGE_GIF -> "GIF";
            default -> "WEBP";
        };
    }

    /**
     * 按文件头魔数识别图片真实格式。
     *
     * @param content 图片字节（非空）
     * @return 格式标签；无法识别返回 {@value #FORMAT_UNKNOWN}
     */
    private static String detectFormat(byte[] content) {
        if (startsWith(content, MAGIC_JPEG, 0)) {
            return "JPEG";
        }
        if (startsWith(content, MAGIC_PNG, 0)) {
            return "PNG";
        }
        if (startsWith(content, MAGIC_GIF, 0)) {
            return "GIF";
        }
        if (startsWith(content, MAGIC_WEBP_RIFF, 0) && startsWith(content, MAGIC_WEBP_TAG, WEBP_TAG_OFFSET)) {
            return "WEBP";
        }
        return FORMAT_UNKNOWN;
    }

    /**
     * 判断字节数组在指定偏移处是否以给定无符号魔数序列开头。
     *
     * @param content 图片字节
     * @param magic   魔数序列（0~255 无符号值）
     * @param offset  起始偏移
     * @return 匹配返回 true；长度不足或字节不符返回 false
     */
    private static boolean startsWith(byte[] content, int[] magic, int offset) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((content[offset + i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 组装带附件序号定位的错误信息。
     *
     * @param index  附件序号（从 1 起）
     * @param reason 失败原因
     * @return 完整错误信息
     */
    private static String located(int index, String reason) {
        return INDEX_PREFIX + index + INDEX_SUFFIX + "非法：" + reason;
    }
}
