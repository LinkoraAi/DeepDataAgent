package com.linkroa.deepdataagent.shared.storage;

/**
 * 对象 key / 前缀集中校验工具（技术防线，第二道）。
 * <p>统一约束各后端可接受的 key 形态，防止路径穿越、控制字符与超界 key 渗入：</p>
 * <ul>
 *   <li>非空且长度 ≤ {@link #MAX_KEY_LENGTH}；</li>
 *   <li>不得以 {@code /} 开头，不得出现连续 {@code //}（精确 key 也不得以 {@code /} 结尾）；</li>
 *   <li>路径段不得为 {@code .} / {@code ..}（杜绝穿越）；</li>
 *   <li>路径段不得含反斜杠 {@code \}（Windows 下会被当分隔符形成穿越）与控制字符。</li>
 * </ul>
 * <p>采用「威胁拒绝」而非字符白名单：S3 对象 key 为 UTF-8，允许 Unicode 与常见文件名字符，
 * 仅拦截真正的穿越 / 控制 / 分隔威胁。业务侧（如 skill 资源相对路径）仍须保留各自的业务
 * 不变量校验（绝对路径、保留文件名等），本工具不替代业务校验。</p>
 */
public final class ObjectKeys {

    /** key 最大长度（字节级有界，防超界 key）。 */
    public static final int MAX_KEY_LENGTH = 1024;

    private ObjectKeys() {
    }

    /**
     * 校验精确对象 key（指向单个对象，不得以 {@code /} 结尾或含空段）。
     *
     * @param key 对象 key
     * @throws IllegalArgumentException key 为空、超长或含非法形态
     */
    public static void validateKey(String key) {
        validate(key, false);
    }

    /**
     * 校验前缀（允许以 {@code /} 结尾表示目录前缀）。
     *
     * @param prefix key 前缀
     * @throws IllegalArgumentException 前缀为空、超长或含非法形态
     */
    public static void validatePrefix(String prefix) {
        validate(prefix, true);
    }

    /**
     * 统一校验实现：按 {@code /} 切分逐段检查，前缀允许末尾空段（结尾 {@code /}）。
     *
     * @param value          待校验 key / 前缀
     * @param allowTrailingSlash 是否允许以 {@code /} 结尾
     */
    private static void validate(String value, boolean allowTrailingSlash) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("对象 key 不能为空");
        }
        if (value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("对象 key 长度不能超过 " + MAX_KEY_LENGTH);
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException("对象 key 不得以 / 开头: " + value);
        }
        boolean trailingSlash = value.endsWith("/");
        if (trailingSlash && !allowTrailingSlash) {
            throw new IllegalArgumentException("对象 key 不得以 / 结尾: " + value);
        }
        String body = trailingSlash ? value.substring(0, value.length() - 1) : value;
        String[] segments = body.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment, value);
        }
    }

    /**
     * 校验单个路径段：非空、非点号穿越、无反斜杠与控制字符。
     *
     * @param segment 路径段
     * @param whole   完整 key（仅用于异常信息）
     */
    private static void validateSegment(String segment, String whole) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("对象 key 含空路径段(//): " + whole);
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("对象 key 含点号穿越段: " + whole);
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("对象 key 含非法字符(反斜杠/控制字符): " + whole);
            }
        }
    }
}
