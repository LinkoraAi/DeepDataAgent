package com.linkroa.deepdataagent.shared.storage.provider;

/**
 * 对象存储后端类型（由配置 {@code app.storage.type} 驱动）。
 * <p>{@link #LOCAL} 为本地磁盘布局（单机 / 开发形态）；其余为 S3 兼容协议后端，
 * 经 S3 客户端策略路由（{@code RUSTFS} 强制 path-style，{@code MINIO}/{@code OSS}
 * 寻址模式随配置）。</p>
 */
public enum StorageBackendType {

    /** 本地磁盘布局（默认，仅支持单机 / 开发，多实例部署不得选用）。 */
    LOCAL,

    /** RustFS（S3 兼容协议，path-style）。 */
    RUSTFS,

    /** MinIO（S3 兼容协议）。 */
    MINIO,

    /** 阿里云 OSS（S3 兼容协议）。 */
    OSS;

    /**
     * 是否为 S3 兼容协议后端。
     *
     * @return 非 LOCAL 返回 true
     */
    public boolean isS3() {
        return this != LOCAL;
    }
}
