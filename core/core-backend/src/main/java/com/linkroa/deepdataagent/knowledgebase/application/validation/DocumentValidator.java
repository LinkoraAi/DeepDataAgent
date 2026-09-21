package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文档应用级校验器。
 * <p>负责应用层入参格式与状态机规则校验，避免应用服务直接抛出裸异常：
 * 参数非法 → 400，状态冲突 → 409。</p>
 */
public final class DocumentValidator {

    /** 文件大小上限单位换算：1MB 的字节数 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /**
     * 判重内容哈希的精确长度。
     * <p>与 SHA-256 的十六进制输出对齐，亦与持久化列 {@code document.file_content_hash} 的列长一致。</p>
     */
    private static final int CONTENT_HASH_LENGTH = 64;

    /**
     * 判重内容哈希允许的字符形态：仅十六进制字符（大小写均可，校验通过后统一归一化为小写）。
     * <p>口径原则：同一算法（SHA-256）的表示法差异（大小写）做归一化；
     * 不同算法（如 MD5 的 32 位）或非哈希串一律拒绝。</p>
     */
    private static final Pattern CONTENT_HASH_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]+$");

    /**
     * 已进入删除链的文档状态集合（闸门拒绝集）：DELETING 清退进行中、DELETE_FAILED 待用户重删。
     * <p>{@code DELETED} 常量已随彻底物理删体系移除——
     * 「已删除」的唯一表达是行物理缺失，由调用方按 404 处理，不落入本集合。</p>
     */
    private static final Set<DocumentStatus> DELETION_CHAIN_STATUSES =
            Set.of(DocumentStatus.DELETING, DocumentStatus.DELETE_FAILED);

    private DocumentValidator() {
    }

    /**
     * 校验并解析文件格式。
     * <p>取值兼容 {@code FileType} 枚举名与文件扩展名（忽略大小写、允许前导点号），如 {@code PDF}、{@code docx}、{@code .md}。</p>
     *
     * @param fileType 文件格式字符串
     * @return 匹配的文件格式枚举
     * @throws DeepDataAgentException 格式为空或不受支持（400）
     */
    public static FileType validateFileType(String fileType) {
        if (StringUtils.isBlank(fileType)) {
            throw new DeepDataAgentException("文件格式不能为空");
        }
        String normalized = StringUtils.removeStart(fileType.trim(), ".").toUpperCase();
        for (FileType candidate : FileType.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        throw new DeepDataAgentException("不支持的文件格式：" + fileType);
    }

    /**
     * 校验文件大小不超过上限。
     * <p>上限唯一来源为 {@code spring.servlet.multipart.max-file-size} 配置（由调用方注入后传入），
     * 本校验器不再维护独立的硬编码兜底值，避免出现「配置与代码双上限」漂移。</p>
     *
     * @param fileSize 文件大小（字节），为空表示未知不做校验
     * @param maxBytes 允许的最大字节数（来自 multipart 配置）
     * @throws DeepDataAgentException 大小为负或超过上限（400）
     */
    public static void validateFileSize(Long fileSize, long maxBytes) {
        if (ObjectUtils.isEmpty(fileSize)) {
            return;
        }
        if (fileSize < 0) {
            throw new DeepDataAgentException("文件大小非法：" + fileSize);
        }
        if (fileSize > maxBytes) {
            throw new DeepDataAgentException("文件大小超过上限 " + (maxBytes / BYTES_PER_MB) + "MB");
        }
    }

    /**
     * 校验判重预检请求的判定轴，并归一化内容哈希。
     * <p>预检端点为只读探测，文件名与内容哈希全空时检测无任何语义，直接拒绝。</p>
     * <p>内容哈希口径定版（与生产者 {@code DocumentDedupService#sha256Hex}、持久化列
     * {@code document.file_content_hash} 镜像一致）：服务端对<b>上传的原始文件字节</b>计算 SHA-256，
     * 输出<b>小写十六进制、恒 64 字符</b>；该值唯一用途是判重轴，
     * MUST NOT 用作文件完整性校验、秒传或解析产物指纹。故此处只接受 64 位十六进制形态，
     * 大小写视为同一算法（SHA-256）的表示法差异予以归一化，其他算法摘要或非法形态一律拒绝，
     * MUST NOT 静默放行。</p>
     *
     * @param fileName    待检文件名，可为空
     * @param contentHash 待检内容哈希，可为空；非空时须为 64 位十六进制
     * @return 归一化（小写）后的内容哈希；内容哈希为空白时返回 {@code null}（仅文件名轴）
     * @throws DeepDataAgentException 两轴全为空白，或内容哈希形态非法（400）
     */
    public static String validatePrecheckAxes(String fileName, String contentHash) {
        if (StringUtils.isAllBlank(fileName, contentHash)) {
            throw new DeepDataAgentException("判重预检至少提供文件名或内容哈希之一");
        }
        String trimmedHash = StringUtils.trimToNull(contentHash);
        if (StringUtils.isBlank(trimmedHash)) {
            return null;
        }
        boolean hashShapeValid = trimmedHash.length() == CONTENT_HASH_LENGTH
                && CONTENT_HASH_HEX_PATTERN.matcher(trimmedHash).matches();
        if (!hashShapeValid) {
            throw new DeepDataAgentException("内容哈希需为 64 位十六进制 SHA-256，当前取值非法：" + contentHash);
        }
        return StringUtils.lowerCase(trimmedHash, Locale.ROOT);
    }

    /**
     * 校验文档允许发起重新解析：以文档状态作为并发闸门，解析中的文档拒绝重复触发重建，
     * 避免与 RAG 侧正在执行的摄入任务竞态写入切片；删除链两态（DELETING / DELETE_FAILED）
     * 同样拒绝，防止正常链与删除链并发写同一文档（
     * DELETED 退出持久状态后，「已删除」以行缺失表达、由调用方按 404 处理，不落入本闸门）。
     *
     * @param document 目标文档
     * @throws ResourceConflictException 文档正在解析中，或已进入删除流程（409）
     */
    public static void validateCanReparse(Document document) {
        if (ObjectUtils.isEmpty(document)) {
            return;
        }
        if (document.status() == DocumentStatus.PROCESSING) {
            throw new ResourceConflictException("文档「" + document.fileName() + "」正在解析中，暂不能重复发起重建");
        }
        if (DELETION_CHAIN_STATUSES.contains(document.status())) {
            throw new ResourceConflictException("文档「" + document.fileName() + "」已进入删除流程，暂不能重新解析");
        }
    }

    /**
     * 校验文档允许人工切片操作（新增 / 编辑 / 删除）——三层删除链统一的文档态闸门。
     * <p>闸门口径（与 chunk-deletion 规格）：文档已进入删除链
     * （DELETING 清退进行中 / DELETE_FAILED 待重删）一律拒绝，人工写入会与分批清退赛跑、
     * 造成派生数据与分块计数漂移；「已删除」不作为持久状态存在（DELETED 常量已移除），
     * 文档行缺失由调用方按 404 处理，不落入本闸门；知识库生命周期态由
     * {@code KnowledgeBaseValidator#validateNotDeleting} 单独把关。</p>
     *
     * @param document 目标文档聚合根，为空视为不做校验（由调用方先行判定 404）
     * @throws ResourceConflictException 文档已进入删除链（409）
     */
    public static void validateChunkOperationAllowed(Document document) {
        if (ObjectUtils.isEmpty(document)) {
            return;
        }
        if (DELETION_CHAIN_STATUSES.contains(document.status())) {
            throw new ResourceConflictException("文档「" + document.fileName() + "」已进入删除流程，禁止切片操作");
        }
    }

    /**
     * 解析文档处理状态，允许为空。
     *
     * @param status 状态字符串（DocumentStatus 枚举名）
     * @return 状态枚举；入参为空返回 {@code null}（表示不按状态过滤）
     * @throws DeepDataAgentException 状态取值非法（400）
     */
    public static DocumentStatus parseStatusOrNull(String status) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        for (DocumentStatus candidate : DocumentStatus.values()) {
            if (StringUtils.equals(candidate.name(), normalized)) {
                return candidate;
            }
        }
        throw new DeepDataAgentException("不支持的文档状态：" + status);
    }
}
