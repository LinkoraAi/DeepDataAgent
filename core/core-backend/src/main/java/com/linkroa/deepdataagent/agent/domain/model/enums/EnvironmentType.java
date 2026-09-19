package com.linkroa.deepdataagent.agent.domain.model.enums;

import java.util.Locale;

/**
 * 运行环境配置类型（Environment {@code config.type} 契约值域）。
 * <p>收敛为两类：{@link #CLOUD}（云端托管容器）与 {@link #SELF_HOSTED}
 * （自托管，外部 worker 轮询工作；本系统无 worker 执行平面，仅允许创建与查询，
 * Session 装配被拒 400「不支持的执行平面」）。</p>
 * <p>旧自建类型 {@code LOCAL}（本地 Docker）与 {@code SANDBOX}（E2B 云沙箱）
 * 随 config 结构化改造移除：存量行经迁移脚本统一映射为 {@code cloud}
 * （沙箱资源明细不再由环境承载）。</p>
 */
public enum EnvironmentType {

    /** 云端托管容器（缺省类型） */
    CLOUD,

    /** 自托管执行平面（本期不可装配进 Session） */
    SELF_HOSTED;

    /**
     * 规范持久化 / 对外取值（小写，如 {@code "cloud"}、{@code "self_hosted"}）。
     *
     * @return 小写类型值
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 从字符串解析类型（大小写不敏感，仅接受 {@code cloud} / {@code self_hosted}）。
     * <p>旧自建取值（{@code LOCAL} / {@code SANDBOX} / {@code remote} 等）一律拒绝。</p>
     *
     * @param value 类型字符串
     * @return 类型枚举；空白或未知取值抛 {@link IllegalArgumentException}
     */
    public static EnvironmentType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("环境配置类型不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cloud" -> CLOUD;
            case "self_hosted" -> SELF_HOSTED;
            default -> throw new IllegalArgumentException("环境配置类型仅支持 cloud / self_hosted: " + value);
        };
    }
}
