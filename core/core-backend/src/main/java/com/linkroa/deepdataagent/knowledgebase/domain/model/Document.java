package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 文档聚合根。
 * <p>管理文档生命周期（PENDING → PROCESSING → PROCESSED / FAILED），
 * 关联知识库，持有分块列表。</p>
 *
 * @param id                主键
 * @param kbId              所属知识库ID
 * @param fileName          文件名
 * @param fileType          文件格式
 * @param status            文档处理状态
 * @param errorMessage      处理失败原因（FAILED 时由 RAG 经摄入契约回写；其余状态恒为 null）
 * @param fileSize          文件大小（字节）
 * @param chunkCount        分块数量
 * @param sourceFileProfile 源文件关键信息 JSON
 * @param importType        导入方式
 * @param s3File            源文件对象存储引用 JSON
 * @param fileContentHash   判重内容轴：服务端对上传的原始文件字节计算 SHA-256，输出小写十六进制、恒 64 字符；
 *                          该值唯一用途是判重轴，MUST NOT 用作文件完整性校验、秒传或解析产物指纹
 * @param chunkStrategy     文档级分块策略 JSON（NULL=继承库级）
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record Document(
        Long id,
        Long kbId,
        String fileName,
        FileType fileType,
        DocumentStatus status,
        String errorMessage,
        Long fileSize,
        Integer chunkCount,
        String sourceFileProfile,
        ImportType importType,
        String s3File,
        String fileContentHash,
        String chunkStrategy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * 紧凑构造器：不变量校验。
     */
    public Document {
        if (kbId == null) {
            throw new IllegalArgumentException("文档必须关联知识库");
        }
        if (StringUtils.isBlank(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (fileType == null) {
            throw new IllegalArgumentException("文件格式不能为空");
        }
        if (status == null) {
            status = DocumentStatus.PENDING;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
        if (importType == null) {
            importType = ImportType.UPLOAD;
        }
    }

    /**
     * 创建新文档（上传导入）。
     */
    public static Document create(Long kbId, String fileName, FileType fileType,
                                  Long fileSize, String sourceFileProfile,
                                  String s3File, String fileContentHash, String chunkStrategy) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new Document(null, kbId, fileName, fileType, DocumentStatus.PENDING,
                null, fileSize, 0, sourceFileProfile, ImportType.UPLOAD,
                s3File, fileContentHash, chunkStrategy, now, now);
    }

    /**
     * 从数据库恢复。
     */
    public static Document restore(Long id, Long kbId, String fileName, FileType fileType,
                                   DocumentStatus status, String errorMessage, Long fileSize, Integer chunkCount,
                                   String sourceFileProfile, ImportType importType,
                                   String s3File, String fileContentHash, String chunkStrategy,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new Document(id, kbId, fileName, fileType, status, errorMessage, fileSize, chunkCount,
                sourceFileProfile, importType, s3File, fileContentHash, chunkStrategy, createdAt, updatedAt);
    }

    /**
     * 更新处理状态。
     */
    public Document withStatus(DocumentStatus newStatus) {
        return new Document(id, kbId, fileName, fileType, newStatus, errorMessage, fileSize, chunkCount,
                sourceFileProfile, importType, s3File, fileContentHash, chunkStrategy,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 更新处理失败原因（null 表示无失败原因；领取摄入与重新解析时清除）。
     *
     * @param newErrorMessage 失败原因，可为 null
     * @return 覆盖失败原因后的新文档实例
     */
    public Document withErrorMessage(String newErrorMessage) {
        return new Document(id, kbId, fileName, fileType, status, newErrorMessage, fileSize, chunkCount,
                sourceFileProfile, importType, s3File, fileContentHash, chunkStrategy,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 更新文档级分块策略快照（重新解析时刷新为本次重建实际生效的策略）。
     *
     * @param newChunkStrategy 分块策略 JSON，为空表示继承库级
     * @return 覆盖策略后的新文档实例
     */
    public Document withChunkStrategy(String newChunkStrategy) {
        return new Document(id, kbId, fileName, fileType, status, errorMessage, fileSize, chunkCount,
                sourceFileProfile, importType, s3File, fileContentHash, newChunkStrategy,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 更新分块数量。
     */
    public Document withChunkCount(int count) {
        return new Document(id, kbId, fileName, fileType, status, errorMessage, fileSize, count,
                sourceFileProfile, importType, s3File, fileContentHash, chunkStrategy,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}
