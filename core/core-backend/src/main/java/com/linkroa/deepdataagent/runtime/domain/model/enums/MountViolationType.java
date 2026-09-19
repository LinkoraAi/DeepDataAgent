package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 会话挂载规则冲突种类（领域拒绝词汇，供应用层映射协议状态码）。
 * <p>领域只表达「违反了哪条挂载规则」，不表达 HTTP 语义：同一冲突在创建 / 追加两条入口
 * 的状态码口径不同（如 {@link #DUPLICATE_FILE} 创建 400、追加 409），由应用层按入口映射
 * （见 {@code session.SessionLifecycleService} 的挂载拒绝映射方法）。</p>
 *
 * @see com.linkroa.deepdataagent.runtime.domain.model.MountViolationException
 */
public enum MountViolationType {

    /** 同一文件重复挂载（批内重复或与既有挂载重复）。 */
    DUPLICATE_FILE,

    /** 挂载路径已被占用（批内互撞或与既有挂载相撞）。 */
    DUPLICATE_MOUNT_PATH,

    /** 文件不可挂载（不存在 / 越权 / 未就绪——应用层材料化的字节清单缺该文件键）。 */
    FILE_NOT_MOUNTABLE,

    /** 挂载文件总量超过 500MB 上限。 */
    QUOTA_EXCEEDED,

    /** 追加项类型非法（本期仅 {@code file} 资源可追加挂载）。 */
    APPEND_TYPE_UNSUPPORTED,

    /** 资源类型不可移除（{@code github_repository / memory_store} 挂载后不可摘除）。 */
    REMOVE_TYPE_UNSUPPORTED,

    /** 资源类型不支持令牌轮换（仅 {@code github_repository} 支持）。 */
    ROTATE_TYPE_UNSUPPORTED,

    /** 轮换令牌为空（避免清空既有凭证）。 */
    TOKEN_BLANK,

    /** 挂载资源未命中（按 {@code sesr_} 资源 ID 在会话挂载列表中定位失败）。 */
    MOUNTED_RESOURCE_NOT_FOUND
}
