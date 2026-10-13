package com.linkroa.deepdataagent.shared.constant.api;

/**
 * API 版本化常量（跨上下文共享）。
 * <p>{@code CURRENT_API_VERSION} 供各限界上下文的控制器声明
 * {@code @RequestMapping(version = ...)} 共用，避免反向依赖运行时上下文的配置类型。</p>
 * <p>本期只交付 v1：所有版本化控制器固定声明 {@code CURRENT_API_VERSION}；不再提供
 * {@code "1+"} 形式的基线声明（原基线唯一消费者为已退役的保管库面控制器）。</p>
 */
public final class ApiVersionConstants {

    /** 当前运行时 API 主版本号（语义化版本，URL 形态 {@code /api/v1/...}）。 */
    public static final String CURRENT_API_VERSION = "1";

    private ApiVersionConstants() {
    }
}