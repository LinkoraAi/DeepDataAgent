package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.MountViolationType;

/**
 * 会话挂载规则冲突（领域拒绝载体）。
 * <p>继承 {@link IllegalArgumentException}：未被应用层接住的冲突天然落 400
 * {@code invalid_request_error}（与领域不变量校验同口径），应用层只对需要升级状态码的
 * 冲突（追加 / 移除面的 409、寻址未命中的 404）按 {@link #violation()} 做映射。</p>
 * <p>消息文本在此单点定义（规则口径唯一事实源），仅 {@link MountViolationType#FILE_NOT_MOUNTABLE}
 * 例外——创建面向客户端披露「不存在或无权访问」（404），追加面收敛为冲突语义
 * （「不存在、无权访问或尚未就绪」409），两者措辞由各自入口给出，领域持有追加面措辞。</p>
 *
 * @param violation 冲突种类（应用层据此映射状态码）
 * @param subject   冲突对象标识（fileId / mount_path / 资源类型 / sesr_ 资源 ID，可空）
 */
public class MountViolationException extends IllegalArgumentException {

    private final MountViolationType violation;
    private final String subject;

    private MountViolationException(MountViolationType violation, String subject, String message) {
        super(message);
        this.violation = violation;
        this.subject = subject;
    }

    /** 同一文件重复挂载。 */
    public static MountViolationException duplicateFile(String fileId) {
        return new MountViolationException(MountViolationType.DUPLICATE_FILE, fileId,
                "文件重复挂载，同一文件不可多次挂载至同一会话: " + fileId);
    }

    /** 挂载路径已被占用。 */
    public static MountViolationException duplicateMountPath(String mountPath) {
        return new MountViolationException(MountViolationType.DUPLICATE_MOUNT_PATH, mountPath,
                "挂载路径已被占用，不可重复挂载: " + mountPath);
    }

    /** 文件不可挂载（字节清单缺键 = 不存在 / 越权 / 未就绪）。 */
    public static MountViolationException fileNotMountable(String fileId) {
        return new MountViolationException(MountViolationType.FILE_NOT_MOUNTABLE, fileId,
                "挂载文件不存在、无权访问或尚未就绪: " + fileId);
    }

    /** 挂载文件总量超过 500MB 上限。 */
    public static MountViolationException quotaExceeded(String fileId) {
        return new MountViolationException(MountViolationType.QUOTA_EXCEEDED, fileId,
                "会话挂载文件总量超过 500MB 上限，拒绝挂载: " + fileId);
    }

    /** 追加项类型非法（本期仅 file 资源可追加）。 */
    public static MountViolationException appendTypeUnsupported(String type) {
        return new MountViolationException(MountViolationType.APPEND_TYPE_UNSUPPORTED, type,
                "追加挂载仅支持 file 类型资源: " + type);
    }

    /** 资源类型不可移除（github / 记忆库挂载后不可摘除）。 */
    public static MountViolationException removeTypeUnsupported(String type) {
        return new MountViolationException(MountViolationType.REMOVE_TYPE_UNSUPPORTED, type,
                "仅 file 类型资源支持移除，github_repository / memory_store 挂载不可摘除: " + type);
    }

    /** 资源类型不支持令牌轮换。 */
    public static MountViolationException rotateTypeUnsupported(String type) {
        return new MountViolationException(MountViolationType.ROTATE_TYPE_UNSUPPORTED, type,
                "仅 github_repository 资源支持令牌轮换: " + type);
    }

    /** 轮换令牌为空。 */
    public static MountViolationException blankToken() {
        return new MountViolationException(MountViolationType.TOKEN_BLANK, null,
                "authorization_token 不能为空");
    }

    /** 挂载资源未命中。 */
    public static MountViolationException mountedResourceNotFound(String resourceId) {
        return new MountViolationException(MountViolationType.MOUNTED_RESOURCE_NOT_FOUND, resourceId,
                "挂载资源不存在: " + resourceId);
    }

    /**
     * 冲突种类（应用层据此映射 HTTP 状态码，避免解析消息文本）。
     *
     * @return 冲突种类枚举
     */
    public MountViolationType violation() {
        return violation;
    }

    /**
     * 冲突对象标识（fileId / mount_path / 资源类型 / {@code sesr_} 资源 ID）。
     *
     * @return 冲突对象标识，无关联对象时（如空令牌）为 {@code null}
     */
    public String subject() {
        return subject;
    }
}
