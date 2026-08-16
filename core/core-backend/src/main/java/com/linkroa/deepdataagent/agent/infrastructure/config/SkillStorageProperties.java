package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 技能存储配置（{@code app.agent.skills}）。
 */
@Configuration
@ConfigurationProperties(prefix = "app.agent.skills")
public class SkillStorageProperties {

    /** 技能包本地存储根目录（LOCAL_FILE 存储类型） */
    private String root = "./data/skills";

    /** 技能包存储类型（本期仅支持 LOCAL_FILE，OSS 预留） */
    private String storageType = "LOCAL_FILE";

    /** 单个技能包上传大小上限（字节，默认 50MB） */
    private long maxSize = 50L * 1024 * 1024;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * 启动时校验存储类型配置：仅 LOCAL_FILE 在本期实现，OSS 等未知类型直接报配置错误（fail-fast）。
     */
    @PostConstruct
    public void validateStorageType() {
        resolveStorageType();
    }

    /**
     * 存储根目录（本地文件系统路径）
     */
    public Path getRootPath() {
        return Path.of(root);
    }

    /**
     * 解析存储类型枚举；非 LOCAL_FILE（含预留 OSS）一律抛配置错误（fail-fast，不静默降级）
     */
    public SkillStorageType resolveStorageType() {
        if (StringUtils.isBlank(storageType)) {
            throw new IllegalStateException("未配置技能存储类型(app.agent.skills.storage-type)");
        }
        try {
            SkillStorageType type = SkillStorageType.valueOf(storageType.trim().toUpperCase());
            if (type != SkillStorageType.LOCAL_FILE) {
                throw new IllegalStateException("技能存储类型「" + storageType
                        + "」尚未实现（当前仅支持 LOCAL_FILE），请检查 app.agent.skills.storage-type 配置");
            }
            return type;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("尚未实现")) {
                throw e;
            }
            throw new IllegalStateException("不支持的值作为技能存储类型: " + storageType
                    + "（当前仅支持 LOCAL_FILE，OSS 为预留值）", e);
        }
    }
}