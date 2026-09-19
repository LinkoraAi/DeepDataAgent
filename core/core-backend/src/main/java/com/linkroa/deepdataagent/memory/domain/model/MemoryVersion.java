package com.linkroa.deepdataagent.memory.domain.model;

import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 记忆版本领域模型（MemoryVersion，对应 memory_versions 表，不可变快照）。
 * <p>以 {@code memver_} 前缀业务ID标识；每条记忆（entry）的每次变更均落一个版本行，
 * {@code action} 取值 created/updated/deleted，其中 deleted 为 tombstone 墓碑版本
 * （不携带内容）；{@code redact()} 为版本级脱敏——脱敏后 content 与校验值清除、
 * 字节数保留，历史内容不再返回。</p>
 *
 * @param id             数据库主键
 * @param versionId      版本业务ID（前缀 memver_）
 * @param storeId        所属记忆库业务ID
 * @param entryId        所属记忆业务ID（mem_ 前缀）
 * @param entryPath      记忆路径快照
 * @param version        版本号（entry 内从 1 递增）
 * @param action         动作类型（created/updated/deleted）
 * @param content        文本内容（墓碑版本与已脱敏版本为 null）
 * @param size           内容字节长度（UTF-8，墓碑版本为 null）
 * @param contentSha256  内容 SHA-256 校验值（脱敏后为 null）
 * @param redacted       是否已脱敏
 * @param redactedAt     脱敏时间
 * @param createdAt      版本创建时间
 */
public record MemoryVersion(
        Long id,
        String versionId,
        String storeId,
        String entryId,
        String entryPath,
        int version,
        MemoryVersionAction action,
        String content,
        Long size,
        String contentSha256,
        boolean redacted,
        OffsetDateTime redactedAt,
        OffsetDateTime createdAt
) {

    /** 版本业务ID前缀 */
    public static final String VERSION_ID_PREFIX = "memver_";

    /** 记忆业务ID前缀（entryId 复用） */
    private static final String MEMORY_ID_PREFIX = "mem_";

    private static final String SHA_256 = "SHA-256";

    /**
     * 紧凑构造器：不变量校验
     */
    public MemoryVersion {
        if (StringUtils.isBlank(versionId)) {
            throw new IllegalArgumentException("版本业务ID不能为空");
        }
        if (!versionId.startsWith(VERSION_ID_PREFIX)) {
            throw new IllegalArgumentException("版本业务ID必须以 memver_ 为前缀");
        }
        if (StringUtils.isBlank(storeId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(entryId)) {
            throw new IllegalArgumentException("记忆业务ID不能为空");
        }
        if (!entryId.startsWith(MEMORY_ID_PREFIX)) {
            throw new IllegalArgumentException("记忆业务ID必须以 mem_ 为前缀");
        }
        if (StringUtils.isBlank(entryPath)) {
            throw new IllegalArgumentException("记忆路径不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("版本号必须大于0");
        }
        if (action == null) {
            throw new IllegalArgumentException("记忆版本动作类型不能为空");
        }
        if (action != MemoryVersionAction.DELETED && !redacted && content == null) {
            throw new IllegalArgumentException("非墓碑且未脱敏的记忆版本内容不能为null");
        }
    }

    /**
     * 条目创建版本（action=created，size/sha 由内容派生）。
     */
    public static MemoryVersion created(String versionId, String storeId, String entryId,
                                        String entryPath, int version, String content) {
        return snapshot(versionId, storeId, entryId, entryPath, version, MemoryVersionAction.CREATED, content);
    }

    /**
     * 条目更新版本（action=updated，size/sha 由内容派生）。
     */
    public static MemoryVersion updated(String versionId, String storeId, String entryId,
                                        String entryPath, int version, String content) {
        return snapshot(versionId, storeId, entryId, entryPath, version, MemoryVersionAction.UPDATED, content);
    }

    /**
     * 条目删除墓碑版本（action=deleted，不携带内容）。
     */
    public static MemoryVersion tombstone(String versionId, String storeId, String entryId,
                                          String entryPath, int version) {
        return new MemoryVersion(null, versionId, storeId, entryId, entryPath, version,
                MemoryVersionAction.DELETED, null, null, null, false, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 版本级脱敏（返回清除内容与校验值、置脱敏标记的副本；字节数与路径保留）。
     */
    public MemoryVersion redact() {
        return new MemoryVersion(id, versionId, storeId, entryId, entryPath, version, action,
                null, size, null, true,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), createdAt);
    }

    private static MemoryVersion snapshot(String versionId, String storeId, String entryId,
                                          String entryPath, int version,
                                          MemoryVersionAction action, String content) {
        byte[] bytes = requireContentBytes(content);
        return new MemoryVersion(null, versionId, storeId, entryId, entryPath, version, action,
                content, (long) bytes.length, sha256Hex(bytes), false, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 校验内容并返回 UTF-8 字节数组（与 {@link Memory} 同规则：空串合法，超 100KB 拒绝）。
     */
    private static byte[] requireContentBytes(String content) {
        if (content == null) {
            throw new IllegalArgumentException("记忆内容不能为null");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Memory.MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("记忆内容UTF-8字节数不能超过100KB");
        }
        return bytes;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256算法不可用", e);
        }
    }
}
