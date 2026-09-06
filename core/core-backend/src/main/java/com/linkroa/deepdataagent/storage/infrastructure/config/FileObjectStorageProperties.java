package com.linkroa.deepdataagent.storage.infrastructure.config;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置（{@code app.provider}）。
 * <p>统一承载 S3 兼容协议下的连接参数，由 {@code app.provider.type} 决定底层 Provider；
 * 启动时对 {@code type} 做 fail-fast 校验，非法取值直接中止启动。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.storage")
public class FileObjectStorageProperties {

    /** 存储 Provider 类型（本期支持 rustfs，minio/oss 为预留值） */
    private String type = "rustfs";

    /** 服务端点（必须带协议头，path-style 寻址 endpoint/bucket/key） */
    private String endpoint = "http://192.168.204.128:9010";

    /** 访问密钥 */
    private String accessKey = "rustfs";

    /** 私有密钥 */
    private String secretKey = "rustfs";

    /** 区域（RustFS 不校验，占位即可） */
    private String region = "us-east-1";

    /** 路径寻址模式（RustFS 仅支持 path-style） */
    private boolean pathStyle = true;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isPathStyle() {
        return pathStyle;
    }

    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
    }

    /**
     * 启动时校验存储类型配置合法性，非法取值直接中止启动（fail-fast）。
     */
    @PostConstruct
    public void validate() {
        resolveType();
    }

    /**
     * 解析存储 Provider 类型枚举；空白或非法取值抛配置错误（fail-fast，不静默降级）。
     *
     * @return 存储 Provider 类型
     */
    public StorageType resolveType() {
        if (StringUtils.isBlank(type)) {
            throw new IllegalStateException("未配置对象存储类型(app.storage.type)");
        }
        try {
            return StorageType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("不支持的对象存储类型: " + type
                    + "（当前支持 rustfs，minio/oss 为预留值）", e);
        }
    }
}