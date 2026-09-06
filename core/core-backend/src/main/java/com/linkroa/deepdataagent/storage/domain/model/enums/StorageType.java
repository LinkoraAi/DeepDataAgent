package com.linkroa.deepdataagent.storage.domain.model.enums;

/**
 * 对象存储 Provider 类型。
 * <p>决定底层 S3 兼容客户端的组装差异（寻址模式、区域语义等），
 * 由配置项 {@code app.storage.type} 驱动，通过策略 + 枚举工厂在运行时路由。</p>
 */
public enum StorageType {

    /** RustFS（S3 兼容协议，本期唯一实现）。 */
    RUSTFS,

    /** MinIO（S3 兼容，预留扩展）。 */
    MINIO,

    /** 阿里云 OSS（S3 兼容，预留扩展）。 */
    OSS
}