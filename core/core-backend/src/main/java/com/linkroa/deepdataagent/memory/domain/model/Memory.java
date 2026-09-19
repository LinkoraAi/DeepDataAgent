package com.linkroa.deepdataagent.memory.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 记忆条目领域模型（Memory，对应 memories 表，store + path 活跃唯一）。
 * <p>以 {@code mem_} 前缀业务ID标识；{@code version} 为 OCC 乐观并发版本号
 * （初始 1，每次更新 +1）；{@code path} 更新时不可变且必须为不以 / 开头的相对路径；
 * 内容按 UTF-8 字节数上限 100KB，{@code size} / {@code contentSha256} 由内容自动派生。</p>
 *
 * @param id             数据库主键
 * @param memoryId       记忆业务ID（前缀 mem_）
 * @param storeId        所属记忆库业务ID
 * @param path           记忆路径（相对，不以 / 开头）
 * @param version        当前版本号（OCC）
 * @param size           当前内容字节长度（UTF-8）
 * @param contentSha256  当前内容 SHA-256 校验值（hex，64 字符）
 * @param metadata       自定义元数据（值对象，null 归一为空元数据）
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record Memory(
        Long id,
        String memoryId,
        String storeId,
        String path,
        int version,
        long size,
        String contentSha256,
        MemoryMetadata metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 内容字节长度上限（UTF-8，100KB） */
    public static final int MAX_CONTENT_BYTES = 100 * 1024;

    /** 记忆业务ID前缀 */
    public static final String MEMORY_ID_PREFIX = "mem_";

    private static final String SHA_256 = "SHA-256";

    /**
     * 紧凑构造器：不变量校验
     */
    public Memory {
        if (StringUtils.isBlank(memoryId)) {
            throw new IllegalArgumentException("记忆业务ID不能为空");
        }
        if (!memoryId.startsWith(MEMORY_ID_PREFIX)) {
            throw new IllegalArgumentException("记忆业务ID必须以 mem_ 为前缀");
        }
        if (StringUtils.isBlank(storeId)) {
            throw new IllegalArgumentException("记忆库ID不能为空");
        }
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("记忆路径不能为空");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("记忆路径必须是相对路径，不能以 / 开头");
        }
        if (path.length() > 512) {
            throw new IllegalArgumentException("记忆路径长度不能超过512个字符");
        }
        if (version < 1) {
            throw new IllegalArgumentException("版本号必须大于0");
        }
        if (size < 0) {
            throw new IllegalArgumentException("内容字节数不能为负");
        }
        if (StringUtils.isBlank(contentSha256)) {
            throw new IllegalArgumentException("内容SHA-256校验值不能为空");
        }
        if (metadata == null) {
            metadata = MemoryMetadata.empty();
        }
    }

    /**
     * 创建新记忆条目（版本 1，size / contentSha256 由内容派生）。
     *
     * @throws IllegalArgumentException 内容为 null 或 UTF-8 字节数超过 100KB
     */
    public static Memory create(String memoryId, String storeId, String path, String content, MemoryMetadata metadata) {
        byte[] bytes = requireContentBytes(content);
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new Memory(null, memoryId, storeId, path, 1, bytes.length, sha256Hex(bytes),
                metadata, now, now);
    }

    /**
     * 更新内容产生新版本副本（OCC version+1，path 与 createdAt 不变，updatedAt 刷新）。
     *
     * @param content     新内容
     * @param newMetadata 新元数据（null=沿用现有）
     */
    public Memory nextVersion(String content, MemoryMetadata newMetadata) {
        byte[] bytes = requireContentBytes(content);
        return new Memory(id, memoryId, storeId, path, version + 1, bytes.length, sha256Hex(bytes),
                newMetadata == null ? metadata : newMetadata,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 校验内容并返回 UTF-8 字节数组（空串合法，超 100KB 拒绝）。
     */
    private static byte[] requireContentBytes(String content) {
        if (content == null) {
            throw new IllegalArgumentException("记忆内容不能为null");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
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
