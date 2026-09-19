package com.linkroa.deepdataagent.file.application.dto;

import java.nio.file.Path;

/**
 * 文件挂载物化结果载荷（{@code application.dto}：仅被 {@code FileMountMaterializationPort}
 * 引用，携带宿主 {@link Path} 与流式摘要，<b>永不 Feign 化、永不进 {@code api} 面</b>）。
 * <p>表达「一个归属就绪的文件已按 SHA-256 校验流式复制到宿主目标路径」的结果，
 * 供 runtime BC 会话挂载物化编排记账（体积上报清单、副本路径回填）。
 * 纯 record、零业务逻辑，仅契约不变量校验。</p>
 *
 * @param fileId           文件业务 ID（前缀 {@code file_}）
 * @param sizeBytes        物化副本字节数（≥0）
 * @param contentSha256    流式计算的 SHA-256 hex 摘要（64 位）
 * @param materializedPath 宿主目标文件绝对路径（非空）
 */
public record FileMountMaterializationDTO(
        String fileId,
        long sizeBytes,
        String contentSha256,
        Path materializedPath
) {

    public FileMountMaterializationDTO {
        if (fileId == null || !fileId.startsWith("file_")) {
            throw new IllegalArgumentException("文件ID非法: " + fileId);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("物化字节数不能为负: " + sizeBytes);
        }
        if (contentSha256 == null || contentSha256.length() != 64) {
            throw new IllegalArgumentException("SHA-256 摘要须为 64 位 hex: " + contentSha256);
        }
        if (materializedPath == null) {
            throw new IllegalArgumentException("物化目标路径不能为空");
        }
    }
}
