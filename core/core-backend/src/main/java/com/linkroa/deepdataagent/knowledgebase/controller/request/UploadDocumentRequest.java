package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 上传文档元数据请求（multipart 的 {@code meta} JSON 部分）。
 * <p>文件字节由同一请求的 {@code file} 部分提交，源文件对象引用与判重内容哈希由服务端派生，
 * 故本请求不再携带 {@code s3File} / {@code fileContentHash} / {@code fileSize}。</p>
 *
 * @param kbId              所属知识库ID
 * @param fileName          文件名（文件名判重轴）
 * @param fileType          文件格式（对应 FileType 枚举名或扩展名，如 PDF、docx）
 * @param sourceFileProfile 源文件关键信息 JSON
 * @param chunkStrategy     文档级分块策略 JSON（为空表示继承知识库级策略）
 */
public record UploadDocumentRequest(

        @NotNull(message = "知识库ID不能为空")
        Long kbId,

        @NotBlank(message = "文件名不能为空")
        String fileName,

        @NotBlank(message = "文件格式不能为空")
        String fileType,

        String sourceFileProfile,

        String chunkStrategy
) {
}
