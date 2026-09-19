package com.linkroa.deepdataagent.shared.constant.api;

/**
 * API 版本化常量（跨上下文共享）。
 * <p>{@code CURRENT_API_VERSION} 供各限界上下文的控制器声明
 * {@code @RequestMapping(version = ...)} 共用，避免反向依赖运行时上下文的配置类型。</p>
 * <p>Spring Framework 7 的 {@code @RequestMapping#version} 为<b>单值</b>属性（非数组），
 * 需要「一个形状跨多个版本可达」时只能走基线声明（{@code "1+"} 形式）。</p>
 */
public final class ApiVersionConstants {

    /** 当前运行时 API 主版本号（语义化版本，URL 形态 {@code /api/v1/...}）。 */
    public static final String CURRENT_API_VERSION = "1";

    /**
     * 基线版本声明（{@code "1+"}）：匹配本版本及所有更高版本，供「同一契约形状跨版本可达」的控制器共用。
     * <p>语义由 Spring 的 {@code VersionRequestCondition} 承载——基线在候选期按
     * {@code mappingVersion <= requestVersion} 匹配、在 {@code handleMatch} 跳过等值校验，
     * 故 {@code /api/v1/cloud/vaults} 与 {@code /api/v2/cloud/vaults} 会落到同一处理器；
     * 若后续为更高版本单独声明固定版本（如 {@code version = "2"}），按「高版本上浮」自动优先命中，
     * 基线不会成为阻碍（见 design D9）。</p>
     */
    public static final String BASELINE_API_VERSION = CURRENT_API_VERSION + "+";

    private ApiVersionConstants() {
    }
}