package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 上传判重预检请求（只读探测，不产生任何写入）。
 * <p>文件名与内容哈希至少提供一个，两轴全空由应用层校验拒绝（400）。</p>
 * <p>内容哈希入参口径：须为服务端对<b>上传的原始文件字节</b>实算的 SHA-256，
 * <b>小写十六进制、恒 64 字符</b>（大小写视为同一算法的表示法差异，服务端归一化为小写后比对）；
 * 其他算法摘要（如 32 位 MD5）或非法格式一律 400 拒绝，不做静默放行。
 * 该值唯一用途是判重轴，MUST NOT 用作文件完整性校验、秒传或解析产物指纹。</p>
 *
 * @param kbId        目标知识库ID
 * @param fileName    待检文件名，可为空
 * @param contentHash 待检内容哈希（64 位十六进制 SHA-256，大小写不敏感），可为空
 */
public record PrecheckUploadRequest(

        @NotNull(message = "知识库ID不能为空")
        Long kbId,

        String fileName,

        String contentHash
) {
}
