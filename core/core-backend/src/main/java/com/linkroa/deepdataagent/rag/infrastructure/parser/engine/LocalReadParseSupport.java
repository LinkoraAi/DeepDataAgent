package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.service.MultimodalMetaKeys;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 本地解析共用支撑：文本分块、占位块构造、图片签名嗅探与直通产物构造、UTF-8 可解码判定与 MD5 摘要。
 * <p>供本地 Tika 解析（{@link LocalTikaDocumentParser}）与本地直读兜底
 * （{@link LocalReadFallbackDocumentParser}）共享，保证内容块形态一致，避免两处实现漂移。</p>
 * <p><b>占位块口径</b>：解析/兜底产物无有效文本时（引擎不支持该类型且源内容<b>非图片</b>不可解码，
 * 或引擎支持该类型但内容为空/近空），统一产出正文含文件名的占位 TEXT 块，
 * 保证文档终态可 PROCESSED 且切片数 ≥ 1；占位块仅用于「引擎不支持该类型」与
 * 「解析成功但无有效文本」两种情形，真实解析失败（协议错误 / 服务不可用）仍由调用方抛出。</p>
 * <p><b>图片直通例外</b>：被字节签名识别为图片的独立文件不再落入「不可解码→占位」——
 * 由 {@link #imagePassthrough} 产出 IMAGE 内容块 + 自身字节载荷，接入引擎无关的通用视觉链路
 * （持久化 → 绑定 → 真看图描述 → 模板切片），见 openspec capability
 * {@code rag/local-image-visual-passthrough}。</p>
 */
final class LocalReadParseSupport {

    /** 文本按"空行"分段的分隔正则（兼容 CRLF 与空白行）。 */
    private static final String BLOCK_SPLIT_REGEX = "\r?\n\\s*\r?\n";

    /** meta 键：页码。 */
    static final String META_KEY_PAGE = "page";

    /** 本地解析不产坐标，统一占位页码。 */
    static final int DEFAULT_PAGE = 0;

    /** 占位块正文模板（正文含文件名，保证可辨识且非空白）。 */
    private static final String PLACEHOLDER_TEMPLATE = "（占位）未提取到有效文本内容，文件：%s";

    /** 无法取得文件名时占位块的兜底名称。 */
    private static final String PLACEHOLDER_FALLBACK_NAME = "未知文件";

    /** PNG 文件签名（89 50 4E 47）。 */
    private static final byte[] SIGNATURE_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};

    /** JPEG 文件签名（FF D8 FF；JPG / JPEG 同签名）。 */
    private static final byte[] SIGNATURE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** MD5 摘要算法名。 */
    private static final String ALGORITHM_MD5 = "MD5";

    /** 路径分隔符（兼容正反斜杠）。 */
    private static final char SLASH = '/';

    /** 路径分隔符（反斜杠）。 */
    private static final char BACKSLASH = '\\';

    private LocalReadParseSupport() {
    }

    /**
     * 全文按空行分段切为多个 TEXT 块：每段 trim 后非空才保留，meta 统一携带 page=0 占位。
     *
     * @param fullText 待分段全文（可为空白）
     * @return TEXT 内容块列表（可能为空）
     */
    static List<ContentBlockVO> splitToTextBlocks(String fullText) {
        List<ContentBlockVO> blocks = new ArrayList<>();
        if (StringUtils.isBlank(fullText)) {
            return blocks;
        }
        Arrays.stream(fullText.split(BLOCK_SPLIT_REGEX))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .forEach(segment -> blocks.add(new ContentBlockVO(ContentBlockVO.TYPE_TEXT, segment,
                        Map.of(META_KEY_PAGE, DEFAULT_PAGE))));
        return blocks;
    }

    /**
     * 构造占位解析产物：单个含文件名的占位 TEXT 块 + 全文占位文本的 MD5。
     *
     * @param fileNameHint 文件名提示（通常取对象键基名），可为空白
     * @return 占位解析产物（blocks 恒含 1 个 TEXT 块，images 为空）
     */
    static ParsedDocument placeholderDocument(String fileNameHint) {
        String text = placeholderText(fileNameHint);
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text,
                Map.of(META_KEY_PAGE, DEFAULT_PAGE));
        return new ParsedDocument(computeMd5(text), List.of(block));
    }

    /**
     * 构造占位块正文（含文件名，非空白）。
     *
     * @param fileNameHint 文件名提示，可为空白
     * @return 占位正文
     */
    static String placeholderText(String fileNameHint) {
        String name = StringUtils.defaultIfBlank(fileNameHint, PLACEHOLDER_FALLBACK_NAME);
        return String.format(PLACEHOLDER_TEMPLATE, name);
    }

    /**
     * 取对象键的基名（最后一段），用于占位块的可辨识信息。
     *
     * @param objectKey 对象键，可为空白
     * @return 基名；无有效内容时返回空串
     */
    static String baseName(String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            return StringUtils.EMPTY;
        }
        int index = Math.max(objectKey.lastIndexOf(SLASH), objectKey.lastIndexOf(BACKSLASH));
        return index >= 0 ? objectKey.substring(index + 1) : objectKey;
    }

    /**
     * 尝试以 UTF-8 严格解码字节为文本。
     * <p>任何非法字节序列（典型如图片/二进制）抛出解码异常即视为「不可解码」返回 {@code null}；
     * 解码结果空白（空文件 / 近空文本）同样返回 {@code null}，交由调用方走占位块。</p>
     *
     * @param bytes 源文件字节
     * @return 解码文本；不可解码或空白时返回 {@code null}
     */
    static String decodeUtf8OrNull(byte[] bytes) {
        if (ObjectUtils.isEmpty(bytes)) {
            return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return StringUtils.isBlank(text) ? null : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 对文本按 UTF-8 计算 MD5（hex 小写），作为 ParsedDocument 幂等参考。
     *
     * @param text 待哈希文本
     * @return 32 位小写 hex 摘要
     */
    static String computeMd5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM_MD5).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 图片文件签名嗅探：源字节头部命中受支持图片签名（PNG {@code 89 50 4E 47}、
     * JPEG {@code FF D8 FF}，JPG / JPEG 同签名）即判定为图片直通输入。
     * <p><b>口径</b>：以字节签名为准，<b>不采信声明后缀 / 声明类型</b>——直通产物将进入视觉描述
     * 链路，误判会把非图字节喂给模型；识别优先于 UTF-8 文本解码判定执行。
     * 格式域与知识库上传白名单闭合（图片仅 PNG / JPG / JPEG），故不内置 GIF / WEBP，
     * 避免未来白名单扩容时的隐式行为漂移。</p>
     *
     * @param content 源文件字节（可为 null / 短于签名长度，均判不命中）
     * @return true 表示命中受支持图片签名
     */
    static boolean looksLikeImage(byte[] content) {
        return startsWithSignature(content, SIGNATURE_PNG) || startsWithSignature(content, SIGNATURE_JPEG);
    }

    /**
     * 构造图片视觉直通产物：恰含一个 IMAGE 内容块 + 以文件基名为键的图片字节载荷。
     * <p><b>对齐契约</b>：图片载荷键与块 meta 的 {@code img_path} 同为文件基名——两侧净化后
     * 恒为同一基名，保证摄入 Worker 的媒体引用绑定（Stage 1a 持久化 → meta 注入）必命中。</p>
     * <p><b>哈希口径</b>：图片文档无「解析文本」可摘要，{@code parsedTextHash} 取<b>源字节 MD5</b>
     * （见 design D3）——该哈希仅作解析幂等与观测参考，与知识库判重的原始字节 SHA-256
     * 同名异物（持久化为独立一等列），MUST NOT 互相替代或比对。</p>
     *
     * @param content  图片源字节（非空；调用方已确保签名命中）
     * @param baseName 文件基名（对象键末段，如 {@code photo.png}）
     * @return 图片直通解析产物（1 个 IMAGE 块 + 1 条图片载荷；page 元数据齐备）
     */
    static ParsedDocument imagePassthrough(byte[] content, String baseName) {
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, StringUtils.EMPTY,
                Map.of(META_KEY_PAGE, DEFAULT_PAGE,
                        MultimodalMetaKeys.META_KEY_IMG_PATH, StringUtils.defaultString(baseName)));
        return ParsedDocument.withImages(computeMd5Bytes(content), List.of(block),
                Map.of(StringUtils.defaultString(baseName), content));
    }

    /**
     * 对原始字节计算 MD5（hex 小写），供图片直通产物的解析哈希使用。
     *
     * @param bytes 源字节（非空）
     * @return 32 位小写 hex 摘要
     */
    static String computeMd5Bytes(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM_MD5).digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 字节数组头部签名匹配（长度不足直接判不命中，防越界读）。
     *
     * @param content   源字节，可为 null
     * @param signature 待匹配签名
     * @return true 表示头部命中签名
     */
    private static boolean startsWithSignature(byte[] content, byte[] signature) {
        if (ObjectUtils.isEmpty(content) || content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}