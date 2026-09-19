package com.linkroa.deepdataagent.file.domain.model;

import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 文件领域聚合（对应 files 表，文件一等资源形态）。
 * <p>内容由文本入库升级为<strong>磁盘目录存储</strong>（{@code <dataRoot>/files/<file_id>}），
 * 聚合仅持有元数据与 {@code content_sha256} 一致性摘要；{@code size_bytes} 取内容字节长度；
 * {@code mime_type} 由服务端探测（不接受客户端申报）；{@code purpose} 五态必填；
 * {@code downloadable} 由 purpose 派生（tool_output / skill_output 可下载，其余不可）；
 * {@code scope} 未关联为 null；{@code metadata} JSON 文本缺省 {@code {}}。</p>
 *
 * @param id            数据库主键
 * @param fileId        文件业务 ID（前缀 file_）
 * @param ownerId       归属用户 ID
 * @param filename      文件名（清理后非空、1–255 字节、不含 / \ ..）
 * @param mimeType      内容类型（服务端探测，如 text/markdown、application/json）
 * @param sizeBytes     内容字节长度
 * @param purpose       文件用途（五态，必填）
 * @param status        文件状态（上传成功即 ready）
 * @param downloadable  是否可经 /content 直接下载（由 purpose 派生）
 * @param scope         文件作用域（未关联为 null，如 {id: sess_..., type: session}）
 * @param metadata      自定义元数据 JSON 文本（≤8KB，缺省 {}）
 * @param contentSha256 磁盘内容 SHA-256 hex 摘要（一致性校验依据）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record File(
        Long id,
        String fileId,
        Long ownerId,
        String filename,
        String mimeType,
        long sizeBytes,
        FilePurpose purpose,
        FileStatus status,
        boolean downloadable,
        FileScope scope,
        String metadata,
        String contentSha256,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 文件业务 ID 前缀。 */
    public static final String FILE_ID_PREFIX = "file_";

    /** 文件对象类型常量（对外 type 字段恒为 file）。 */
    public static final String FILE_TYPE = "file";

    /** 单文件大小上限（字节，50MB，契约限额）。 */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    /** metadata JSON UTF-8 字节长度上限（8KB，契约「原始长度 ≤8KB」）。 */
    public static final int MAX_METADATA_BYTES = 8 * 1024;

    /** metadata 缺省值（空对象 JSON 文本）。 */
    public static final String EMPTY_METADATA = "{}";

    /** 文件名 UTF-8 字节长度上限（契约 1–255 字节）。 */
    public static final int MAX_FILENAME_BYTES = 255;

    /** SHA-256 hex 摘要长度。 */
    private static final int SHA256_HEX_LENGTH = 64;

    /**
     * 紧凑构造器：领域不变量校验。
     */
    public File {
        if (StringUtils.isBlank(fileId)) {
            throw new IllegalArgumentException("文件ID不能为空");
        }
        if (!fileId.startsWith(FILE_ID_PREFIX)) {
            throw new IllegalArgumentException("文件ID非法");
        }
        if (StringUtils.isBlank(filename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        int filenameBytes = filename.getBytes(StandardCharsets.UTF_8).length;
        if (filenameBytes == 0 || filenameBytes > MAX_FILENAME_BYTES) {
            throw new IllegalArgumentException("文件名长度必须在 1–255 字节之间");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("文件名非法，不能包含路径分隔符或 ..");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("文件归属用户不能为空");
        }
        if (StringUtils.isBlank(mimeType)) {
            throw new IllegalArgumentException("文件MIME类型不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("文件大小超过 50MB 上限");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("文件用途(purpose)不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("文件状态不能为空");
        }
        if (downloadable != purpose.downloadable()) {
            throw new IllegalArgumentException("downloadable 必须由文件用途派生: " + purpose.code());
        }
        if (StringUtils.isBlank(contentSha256) || contentSha256.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException("文件内容校验值必须为64位SHA-256摘要");
        }
        if (metadata == null) {
            metadata = EMPTY_METADATA;
        }
        if (metadata.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("文件元数据超过 8KB 上限");
        }
    }

    /**
     * 文件名清理：剥离目录段与路径分隔符，仅保留最长路径中的最后一个成分。
     * <p>契约（files spec「filename 清理与长度校验」）：显式文件名含目录段 / 路径分隔符时
     * <b>目录段被剥离后落库</b>，而非整请求被拒；纯穿越成分（{@code .} / {@code ..}）剥离后
     * 归一为空串，交由构造器的 1–255 字节不变量拒绝（400）。</p>
     *
     * @param raw 原始文件名（可空）
     * @return 清理后的文件名（可能为空串，由构造器拒绝）
     */
    public static String sanitizeFilename(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        int separator = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        if (separator >= 0) {
            cleaned = cleaned.substring(separator + 1);
        }
        if (cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "";
        }
        return cleaned;
    }

    /**
     * 创建新文件（上传形态）：status=ready、downloadable 由 purpose 派生、
     * size_bytes 与 content_sha256 由内容字节推导，scope 由调用方给定（上传场景通常为 null）。
     *
     * @param fileId   文件业务 ID（前缀 file_）
     * @param ownerId  归属用户 ID
     * @param filename 文件名
     * @param mimeType 服务端探测出的 MIME 类型
     * @param purpose  文件用途（必填）
     * @param scope    文件作用域（可空）
     * @param metadata 元数据 JSON 文本（可空，归一 {}）
     * @param content  文件原始字节内容（非 null）
     * @return 新文件聚合（未持久化）
     */
    public static File create(String fileId, Long ownerId, String filename, String mimeType,
                              FilePurpose purpose, FileScope scope, String metadata, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("文件用途(purpose)不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new File(null, fileId, ownerId, filename, mimeType, content.length,
                purpose, FileStatus.READY, purpose.downloadable(), scope,
                StringUtils.isBlank(metadata) ? EMPTY_METADATA : metadata,
                sha256Hex(content), now, now);
    }

    /**
     * 从数据库恢复（查询场景，downloadable / status 等均为已登记形态）。
     */
    public static File restore(
            Long id,
            String fileId,
            Long ownerId,
            String filename,
            String mimeType,
            long sizeBytes,
            FilePurpose purpose,
            FileStatus status,
            boolean downloadable,
            FileScope scope,
            String metadata,
            String contentSha256,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        return new File(id, fileId, ownerId, filename, mimeType, sizeBytes,
                purpose, status, downloadable, scope, metadata, contentSha256, createdAt, updatedAt);
    }

    /**
     * 判断该文件是否归属指定用户（owner 单维门禁的领域谓词）。
     * <p>供 {@code FileApi} 区分「不存在 / 越权」（404）与「存在但未就绪」（409）两份对外语义：
     * 存在性判定只做归属比较、不看 {@code status}。归属比较以 {@code ownerId == null}
     * 短路，null 参数一律不归属。</p>
     *
     * @param ownerId 请求方用户 ID（可空 = 未认证）
     * @return true=该文件归属此用户
     */
    public boolean ownedBy(Long ownerId) {
        return ownerId != null && ownerId.equals(this.ownerId);
    }

    /**
     * 判断该文件是否可被指定用户挂载（归属 + 就绪双门禁的领域谓词）。
     * <p>供 {@code FileApi}（元数据面）与 {@code FileMountMaterializationPort}（宿主物化面）
     * 共用同一道判定，避免 owner / ready 校验在两个 bean 中各写一份而漂移。
     * 归属比较以 {@code ownerId == null} 短路，null 参数一律不可挂载。</p>
     *
     * @param ownerId 请求方用户 ID（可空 = 未认证）
     * @return true=该文件归属此用户且状态为 ready
     */
    public boolean mountableBy(Long ownerId) {
        return ownedBy(ownerId) && this.status == FileStatus.READY;
    }

    /**
     * 校验给定内容字节与本聚合的 SHA-256 摘要一致（下载读取的一致性门禁）。
     *
     * @param content 磁盘读回的文件内容
     * @return true=内容完整
     */
    public boolean contentMatches(byte[] content) {
        return content != null && contentSha256Matches(sha256Hex(content));
    }

    /**
     * 校验给定的 SHA-256 hex 摘要与本聚合登记摘要一致（流式物化免读全文的一致性门禁）。
     * <p>摘要规则只留本类一份：{@link #contentMatches(byte[])} 与本谓词共比对口径，
     * 大小写归一（hex 比对不区分大小写）。</p>
     *
     * @param hexSha256 流式计算出的内容 SHA-256 hex 摘要（可空 = 不匹配）
     * @return true=摘要一致
     */
    public boolean contentSha256Matches(String hexSha256) {
        return hexSha256 != null && contentSha256.equalsIgnoreCase(hexSha256);
    }

    /** 内容字节 → SHA-256 hex 摘要。 */
    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要计算不可用", e);
        }
    }
}
