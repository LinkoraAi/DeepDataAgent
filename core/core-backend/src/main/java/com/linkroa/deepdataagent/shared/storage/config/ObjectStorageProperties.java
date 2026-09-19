package com.linkroa.deepdataagent.shared.storage.config;

import com.linkroa.deepdataagent.shared.storage.provider.StorageBackendType;
import com.linkroa.deepdataagent.shared.storage.s3.BucketNameValidator;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置（{@code app.storage}）。
 * <p>承载后端类型、固定配置桶、本地磁盘根与 S3 兼容连接参数；{@link #type} 默认
 * {@code local}（本地开发 / CI 零外部依赖）。启动时按后端类型做分支 fail-fast 校验：
 * local 仅校验本地根，S3 系校验 endpoint / 凭证 / 桶名，非法即中止启动、不静默降级。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.storage")
public class ObjectStorageProperties {

    /** 存储后端类型：local | rustfs | minio | oss。 */
    private String type = "local";

    /** 固定配置桶（S3 系使用；本地布局下仅为逻辑名）。 */
    private String bucket = "deepdataagent";

    /** 本地磁盘布局配置。 */
    private Local local = new Local();

    /** S3 兼容服务端点（必须带协议头），仅 S3 系必填。 */
    private String endpoint = "";

    /** 访问密钥，仅 S3 系必填。 */
    private String accessKey = "";

    /** 私有密钥，仅 S3 系必填。 */
    private String secretKey = "";

    /** 区域（部分实现不校验，占位即可）。 */
    private String region = "us-east-1";

    /** 路径寻址模式（RustFS 强制 true；MinIO / OSS 随配置）。 */
    private boolean pathStyle = true;

    /**
     * 本地磁盘布局子配置。
     */
    public static class Local {

        /** 本地对象存储根目录（内容按 key 相对路径落盘）。 */
        private String root = "./data/objects";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    /**
     * 启动校验：按后端类型分支 fail-fast（空白或非法取值 / 必填缺失直接中止启动）。
     */
    @PostConstruct
    public void validate() {
        StorageBackendType resolved = resolveType();
        if (resolved == StorageBackendType.LOCAL) {
            if (StringUtils.isBlank(local.root)) {
                throw new IllegalStateException("本地对象存储根目录(app.storage.local.root)不能为空");
            }
            return;
        }
        if (StringUtils.isBlank(endpoint)) {
            throw new IllegalStateException("S3 系对象存储必须配置端点(app.storage.endpoint)");
        }
        if (StringUtils.isBlank(accessKey) || StringUtils.isBlank(secretKey)) {
            throw new IllegalStateException("S3 系对象存储必须配置访问密钥(app.storage.access-key/secret-key)");
        }
        if (StringUtils.isBlank(region)) {
            throw new IllegalStateException("S3 系对象存储区域(app.storage.region)不能为空");
        }
        BucketNameValidator.validate(bucket);
    }

    /**
     * 解析后端类型枚举（空白 / 非法取值抛配置错误，fail-fast 不静默降级）。
     *
     * @return 后端类型
     */
    public StorageBackendType resolveType() {
        if (StringUtils.isBlank(type)) {
            throw new IllegalStateException("未配置对象存储类型(app.storage.type)");
        }
        try {
            return StorageBackendType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "不支持的对象存储类型: " + type + "（支持 local/rustfs/minio/oss）", e);
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
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
}
