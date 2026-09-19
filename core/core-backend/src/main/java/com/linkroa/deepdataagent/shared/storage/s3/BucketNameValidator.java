package com.linkroa.deepdataagent.shared.storage.s3;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 桶名校验器（S3 命名规范子集，技术校验）。
 * <p>校验规则：长度 3-63、字符限小写字母 / 数字 / 点 / 中划线、非 IP 形态、
 * 首尾不得为点或中划线。非法抛 {@link IllegalArgumentException}（配置期 fail-fast）。</p>
 */
public final class BucketNameValidator {

    private BucketNameValidator() {
    }

    /** 最短桶名长度（S3 规范）。 */
    private static final int MIN_BUCKET_NAME_LENGTH = 3;

    /** 最长桶名长度（S3 规范）。 */
    private static final int MAX_BUCKET_NAME_LENGTH = 63;

    /** 合法桶名字符集及边界：首尾为小写字母或数字，中间允许小写字母 / 数字 / 点 / 中划线。 */
    private static final Pattern BUCKET_NAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9.\\-]*[a-z0-9]$");

    /** IP 形态（如 192.168.0.1），S3 规范禁止作为桶名。 */
    private static final Pattern IP_ADDRESS_PATTERN =
            Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * 校验桶名合法性，非法抛参数异常。
     *
     * @param bucket 桶名
     * @throws IllegalArgumentException 桶名为空、长度或字符规则不满足
     */
    public static void validate(String bucket) {
        if (StringUtils.isBlank(bucket)) {
            throw new IllegalArgumentException("对象存储桶名(app.storage.bucket)不能为空");
        }
        if (bucket.length() < MIN_BUCKET_NAME_LENGTH || bucket.length() > MAX_BUCKET_NAME_LENGTH) {
            throw new IllegalArgumentException("对象存储桶名长度必须在 " + MIN_BUCKET_NAME_LENGTH + "-"
                    + MAX_BUCKET_NAME_LENGTH + " 之间: " + bucket);
        }
        if (!BUCKET_NAME_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException(
                    "对象存储桶名含非法字符或首尾非法，仅允许小写字母/数字/点/中划线，且首尾须为字母或数字: "
                            + bucket);
        }
        if (IP_ADDRESS_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException("对象存储桶名不能为 IP 形态: " + bucket);
        }
    }
}
