package com.linkroa.deepdataagent.knowledgebase.application.service;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 媒体图片对象键前缀工具（knowledgebase BC）。
 * <p>集中承载删除链侧的媒体图片对象前缀拼接，替换此前散落在 {@code DocumentApplicationService}
 * 中的就地复刻常量。前缀约定 MUST 与 rag 侧
 * {@code com.linkroa.deepdataagent.rag.domain.service.MediaImageObjectKeys#prefixOf(Long, Long)}
 * 逐字符一致——因依赖方向为 rag → knowledgebase，knowledgebase 不得反向引用 rag 域工具类，
 * 故在此独立定义；该一致性由 {@code MediaImageCleanupContractTest} 在构建期强制锁定。</p>
 * <p>两级粒度：文档级 {@code rag/{kbId}/{documentId}/images/} 与整库级 {@code rag/{kbId}/}；
 * 整库级前缀必然包含该库全部文档级前缀，故整库清理可覆盖文档清理范围。</p>
 * <p>失败语义：ID 为空时返回 {@code null}（MUST NOT 抛异常）——清理方按「无法定位前缀」WARN 跳过，
 * 不得因 ID 异常阻断删除主流程，故不照搬 rag 侧 {@code prefixOf} 的 {@code IllegalArgumentException}。</p>
 *
 * @author DeepDataAgent
 */
public final class MediaImageObjectPrefixes {

    /** 媒体图片对象键命名空间段（须与 rag 侧 {@code MediaImageObjectKeys} 一致）。 */
    private static final String MEDIA_KEY_NAMESPACE = "rag";

    /** 媒体图片对象键目录段（须与 rag 侧 {@code MediaImageObjectKeys} 一致）。 */
    private static final String MEDIA_IMAGES_DIRECTORY = "images";

    /** 对象键路径分隔符（对象存储统一使用正斜杠）。 */
    private static final String OBJECT_KEY_SEPARATOR = "/";

    /**
     * 工具类禁止实例化。
     */
    private MediaImageObjectPrefixes() {
    }

    /**
     * 文档级媒体图片对象键前缀：{@code rag/{kbId}/{documentId}/images/}（以分隔符结尾）。
     *
     * @param kbId       知识库主键，可为空
     * @param documentId 文档主键，可为空
     * @return 前缀字符串；任一 ID 为空时返回 {@code null}（清理方 WARN 跳过）
     */
    public static String documentPrefix(Long kbId, Long documentId) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(documentId)) {
            return null;
        }
        return MEDIA_KEY_NAMESPACE + OBJECT_KEY_SEPARATOR + kbId + OBJECT_KEY_SEPARATOR + documentId
                + OBJECT_KEY_SEPARATOR + MEDIA_IMAGES_DIRECTORY + OBJECT_KEY_SEPARATOR;
    }

    /**
     * 整库级媒体图片对象键前缀：{@code rag/{kbId}/}（以分隔符结尾，覆盖该库全部文档的图片资产）。
     *
     * @param kbId 知识库主键，可为空
     * @return 前缀字符串；知识库 ID 为空时返回 {@code null}（清理方 WARN 跳过）
     */
    public static String knowledgeBasePrefix(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return null;
        }
        return MEDIA_KEY_NAMESPACE + OBJECT_KEY_SEPARATOR + kbId + OBJECT_KEY_SEPARATOR;
    }
}
