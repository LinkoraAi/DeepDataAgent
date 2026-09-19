package com.linkroa.deepdataagent.vault.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 凭证校验结论（三态，对齐 design D4 的分类规则）。
 *
 * <p>分类口径：</p>
 * <ul>
 *   <li>{@link #INVALID}：确定性拒绝——收到 4xx（{@code 408} 超时 / {@code 429} 限流除外），
 *   或无可信 access token（解密失败）；</li>
 *   <li>{@link #UNKNOWN}：无法判定——连接错误、超时、{@code 408} / {@code 429} / {@code 5xx}。
 *   <b>不得据此判定凭证失效</b>（避免误导用户重新授权）；</li>
 *   <li>{@link #VALID}：探测通过（收到 2xx / 3xx 响应）。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.vault.domain.model.VaultCredential
 */
public enum VaultCredentialValidationStatus {

    /** 校验通过 */
    VALID("valid"),
    /** 确证无效（确定性拒绝或无可信访问令牌） */
    INVALID("invalid"),
    /** 无法判定（连接错误 / 超时 / 限流 / 服务端错误） */
    UNKNOWN("unknown");

    /** 对外暴露的小写源码取值 */
    private final String value;

    VaultCredentialValidationStatus(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code valid} / {@code invalid} / {@code unknown}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析校验结论字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的校验结论
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static VaultCredentialValidationStatus fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("凭证校验结论不能为空");
        }
        for (VaultCredentialValidationStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知凭证校验结论: " + value);
    }
}