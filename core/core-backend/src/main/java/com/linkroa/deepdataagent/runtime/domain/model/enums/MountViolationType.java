package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 会话挂载规则冲突种类（领域拒绝词汇，供应用层映射协议状态码）。
 * <p>领域只表达「违反了哪条挂载规则」，不表达 HTTP 语义：创建 / 追加两条入口由应用层按本词汇
 * 统一映射协议状态码——{@link #DUPLICATE_FILE} / {@link #DUPLICATE_MOUNT_PATH} → 409 {@code conflict_error}，
 * {@link #FILE_NOT_MOUNTABLE} → 409 {@code invalid_request_error}（创建路径不存在 / 越权则 404），
 * {@link #QUOTA_EXCEEDED} → 400（见 {@code session.SessionLifecycleService} 与
 * {@code validation.SessionMountValidator} 的挂载拒绝映射）。</p>
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

    /** 挂载资源未命中（按 {@code sesr_} 资源 ID 在会话挂载列表中定位失败）。 */
    MOUNTED_RESOURCE_NOT_FOUND
}