package com.linkroa.deepdataagent.knowledgebase.controller.response;

import java.time.OffsetDateTime;

/**
 * 文档响应 DTO。
 *
 * @param id                主键
 * @param kbId              所属知识库ID
 * @param fileName          文件名
 * @param fileType          文件格式（枚举名）
 * @param status            文档处理状态（六态：PENDING/PROCESSING/PROCESSED/FAILED/DELETING/DELETE_FAILED，枚举名直透；已删除文档行物理缺失，不再对外暴露 DELETED 状态）
 * @param fileSize          文件大小（字节）
 * @param chunkCount        分块数量
 * @param sourceFileProfile 源文件关键信息 JSON
 * @param importType        导入方式（枚举名）
 * @param s3File            源文件对象存储引用 JSON
 * @param fileContentHash   判重内容哈希（64 位十六进制 SHA-256 字符串；服务端对上传的原始文件字节实算，
 *                          唯一用途是判重轴，MUST NOT 用作文件完整性校验、秒传或解析产物指纹）
 * @param chunkStrategy     文档级分块策略 JSON
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record DocumentResponse(
        Long id,
        Long kbId,
        String fileName,
        String fileType,
        String status,
        String errorMessage,
        Long fileSize,
        Integer chunkCount,
        String sourceFileProfile,
        String importType,
        String s3File,
        String fileContentHash,
        String chunkStrategy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
