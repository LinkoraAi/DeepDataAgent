package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * MinerU 媒体图片对象存储键约定工具。
 * <p>图片资产按「知识库 + 文档」两级前缀组织，读写两侧与删除链共用同一套规则：</p>
 * <ul>
 *   <li>前缀：{@code rag/{kbId}/{documentId}/images/}（{@link #prefixOf(Long, Long)}，
 *       删除链按前缀批量清理，故必须 public）；</li>
 *   <li>对象键：前缀 + 净化后的图片文件名（{@link #keyOf(Long, Long, String)}）。</li>
 * </ul>
 * <p>净化口径（{@link #sanitize(String)}）：只取最后一个路径段（兼容 {@code /} 与 {@code \\} 分隔），
 * 剥离 MinerU 返回的相对目录；凡输入含上级目录标记 {@code ".."} 一律拒绝（保守口径，
 * {@code a/../../b.png} 这类「取基名后看似安全」的输入同样拒绝，避免不同版本对穿越语义的歧义）。</p>
 * <p>失败语义：净化失败（含 {@code null} / 空白 / 以分隔符结尾 / 含 {@code ".."}）返回 {@code null}
 * 而非抛异常——调用方需要的是「跳过该图、不阻断整篇摄入」，由调用方 WARN 留痕后继续。</p>
 *
 * @author DeepDataAgent
 */
public final class MediaImageObjectKeys {

    /** 对象键命名空间段：RAG 域。 */
    private static final String KEY_NAMESPACE = "rag";

    /** 对象键目录段：媒体图片资产目录。 */
    private static final String KEY_IMAGES_DIRECTORY = "images";

    /** 对象键路径分隔符（对象存储统一使用正斜杠）。 */
    private static final String PATH_SEPARATOR = "/";

    /** 本地路径分隔符（净化时与正斜杠同等剥离）。 */
    private static final char LOCAL_PATH_SEPARATOR = '\\';

    /** 上级目录标记（路径穿越特征，命中即拒绝）。 */
    private static final String PARENT_DIRECTORY_MARKER = "..";

    /**
     * 工具类禁止实例化。
     */
    private MediaImageObjectKeys() {
    }

    /**
     * 媒体图片对象键前缀：{@code rag/{kbId}/{documentId}/images/}（以分隔符结尾，可直接拼接文件名）。
     *
     * @param kbId       知识库主键，必填
     * @param documentId 文档主键，必填
     * @return 前缀字符串
     * @throws IllegalArgumentException 知识库ID或文档ID为空（前缀无法定位归属）
     */
    public static String prefixOf(Long kbId, Long documentId) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("媒体图片对象键前缀要求知识库ID与文档ID均非空");
        }
        return KEY_NAMESPACE + PATH_SEPARATOR + kbId + PATH_SEPARATOR + documentId
                + PATH_SEPARATOR + KEY_IMAGES_DIRECTORY + PATH_SEPARATOR;
    }

    /**
     * 单张媒体图片的对象键：{@link #prefixOf(Long, Long)} + 净化后的图片文件名。
     *
     * @param kbId       知识库主键，必填
     * @param documentId 文档主键，必填
     * @param imgName    MinerU 返回的图片名（可含相对目录，如 {@code images/a.png}）
     * @return 对象键；图片名净化失败时返回 {@code null}（调用方跳过该图并 WARN）
     */
    public static String keyOf(Long kbId, Long documentId, String imgName) {
        String sanitized = sanitize(imgName);
        if (StringUtils.isBlank(sanitized)) {
            return null;
        }
        return prefixOf(kbId, documentId) + sanitized;
    }

    /**
     * 图片文件名净化：剥离路径段，拒绝含上级目录标记或净化后为空的输入。
     * <p>示例：{@code images/a.png → a.png}、{@code ..\\a.png → null}、
     * {@code a/../../b.png → null}、{@code a/ → null}。</p>
     *
     * @param imgName 原始图片名，可为 null
     * @return 纯文件名；净化失败返回 {@code null}（由调用方跳过该图并 WARN 留痕）
     */
    public static String sanitize(String imgName) {
        if (StringUtils.isBlank(imgName)) {
            return null;
        }
        String trimmed = StringUtils.trim(imgName);
        if (StringUtils.contains(trimmed, PARENT_DIRECTORY_MARKER)) {
            return null;
        }
        int separatorIndex = Math.max(trimmed.lastIndexOf(PATH_SEPARATOR),
                trimmed.lastIndexOf(LOCAL_PATH_SEPARATOR));
        String baseName = separatorIndex >= 0 ? trimmed.substring(separatorIndex + 1) : trimmed;
        return StringUtils.isBlank(baseName) ? null : baseName;
    }
}
