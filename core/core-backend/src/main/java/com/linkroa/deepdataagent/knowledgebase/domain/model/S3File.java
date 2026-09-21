package com.linkroa.deepdataagent.knowledgebase.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 对象存储文件引用值对象（仅对象键）。
 * <p>桶概念已退役：对象统一落在配置固定的存储桶 / 本地根，业务引用只由
 * 对象键（{@code rag/{kbId}/...} 命名空间下的相对路径）构成。</p>
 *
 * @param objectKey 对象键
 */
public record S3File(
        String objectKey
) {

    /**
     * 紧凑构造器：对象键必须非空白。
     *
     * @throws IllegalArgumentException 对象键为空白
     */
    public S3File {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("对象键不能为空");
        }
    }
}
