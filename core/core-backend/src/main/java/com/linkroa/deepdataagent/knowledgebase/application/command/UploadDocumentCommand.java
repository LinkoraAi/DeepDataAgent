package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 上传文档命令（multipart 单次调用：文件字节随命令进入应用层）。
 * <p>源文件对象引用、文件大小与判重内容哈希均由应用服务在服务端派生
 * （事务外落对象存储、字节实算大小、对上传的原始文件字节实算 SHA-256 内容轴），故本命令不再携带
 * {@code s3File} / {@code fileContentHash} / {@code fileSize}，调用方无从自报。</p>
 *
 * @param kbId              所属知识库ID
 * @param fileName          文件名（文件名判重轴）
 * @param fileType          文件格式（FileType 枚举名或文件扩展名，如 PDF、docx）
 * @param contentType       文件内容类型（取自 multipart 文件部分，可为空）
 * @param sourceFileProfile 源文件关键信息 JSON
 * @param chunkStrategy     文档级分块策略 JSON（为空表示继承知识库级策略）
 * @param content           文件字节（服务端据此计算大小、判重内容哈希并落对象存储）
 */
public record UploadDocumentCommand(
        Long kbId,
        String fileName,
        String fileType,
        String contentType,
        String sourceFileProfile,
        String chunkStrategy,
        byte[] content
) {
}
