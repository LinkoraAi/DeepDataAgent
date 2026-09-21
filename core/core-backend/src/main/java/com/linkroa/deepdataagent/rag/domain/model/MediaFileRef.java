package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 图片/音频/视频的对象存储引用值对象（仅对象键）。
 * <p>对齐 {@code document.s3_file} 的仅对象键引用先例（桶概念已退役）：
 * 解析阶段把 MinerU 返回的图片字节落到对象存储后，仅以本引用随多模态块落
 * {@code chunk.original_item}，<b>不</b>携带任何图片字节 / base64 原文。</p>
 *
 * @param objectKey 对象键（形如 {@code rag/{kbId}/{documentId}/images/{img_name}}）
 */
public record MediaFileRef(String objectKey) {

    /**
     * 紧凑构造器：对象键必须非空白，否则视为无引用（不应构造本对象）。
     */
    public MediaFileRef {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("媒体图片引用要求对象键非空白");
        }
    }
}
