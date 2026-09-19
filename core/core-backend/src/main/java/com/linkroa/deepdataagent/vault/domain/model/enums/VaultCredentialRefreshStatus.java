package com.linkroa.deepdataagent.vault.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 凭证刷新结论（四态，对齐 design D4 的 {@code refresh.status} 值域）。
 *
 * <p>分类口径：</p>
 * <ul>
 *   <li>{@link #NO_REFRESH_TOKEN}：该凭证创建时即无刷新配置，跳过刷新；</li>
 *   <li>{@link #SUCCEEDED}：换得新访问令牌（服务商轮换刷新令牌时一并承载）；</li>
 *   <li>{@link #FAILED}：收到响应但换取失败（确定性 4xx，或响应缺少访问令牌）；</li>
 *   <li>{@link #CONNECT_ERROR}：未收到响应（连接错误 / 超时 / 超出信任边界），
 *   或收到 {@code 408} / {@code 429} / {@code 5xx} 等不可归因于凭证的响应。</li>
 * </ul>
 *
 * @see VaultCredentialValidationStatus
 */
public enum VaultCredentialRefreshStatus {

    /** 无刷新配置（不尝试刷新） */
    NO_REFRESH_TOKEN("no_refresh_token"),
    /** 刷新成功（已换得新访问令牌） */
    SUCCEEDED("succeeded"),
    /** 刷新失败（令牌端点确定性拒绝） */
    FAILED("failed"),
    /** 刷新连接异常（未收到响应，或收到不可归因于凭证的服务端 / 限流响应） */
    CONNECT_ERROR("connect_error");

    /** 对外暴露的小写源码取值 */
    private final String value;

    VaultCredentialRefreshStatus(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code no_refresh_token} / {@code succeeded}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析刷新结论字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的刷新结论
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static VaultCredentialRefreshStatus fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("凭证刷新结论不能为空");
        }
        for (VaultCredentialRefreshStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知凭证刷新结论: " + value);
    }
}