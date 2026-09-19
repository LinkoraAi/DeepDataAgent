package com.linkroa.deepdataagent.agent.controller.response;

import java.util.List;

/**
 * 环境预装依赖响应（{@code config.packages}：固定判别字段 + 六类包管理器数组全量回显）。
 * <p>判别字段 {@code type} 恒为 {@code "packages"}；请求未提交的键回显为空数组
 * （{@code cargo / gem / go} 为公开契约的响应保留字段，不参与装配）。</p>
 *
 * @param type  判别字段（恒 {@code "packages"}）
 * @param apt   Debian/Ubuntu 系统包
 * @param npm   Node.js 包
 * @param pip   Python 包
 * @param cargo Rust crate（响应保留字段，恒空）
 * @param gem   Ruby gem（响应保留字段，恒空）
 * @param go    Go 模块（响应保留字段，恒空）
 */
public record EnvironmentPackagesResponse(
        String type,
        List<String> apt,
        List<String> npm,
        List<String> pip,
        List<String> cargo,
        List<String> gem,
        List<String> go
) {

    /** 判别字段固定值。 */
    public static final String TYPE_PACKAGES = "packages";
}