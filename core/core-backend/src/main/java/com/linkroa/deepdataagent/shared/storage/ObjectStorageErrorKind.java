package com.linkroa.deepdataagent.shared.storage;

/**
 * 对象存储技术错误分类（与具体后端协议无关的错误语义）。
 * <p>不同后端（本地磁盘 / S3 兼容协议）的异常由各自实现翻译为本枚举，
 * 供消费方（file / skill BC 的装配适配器）按类决定业务语义；
 * 本枚举仅描述技术失败形态，不是业务错误码。</p>
 */
public enum ObjectStorageErrorKind {

    /** 目标对象（或桶）不存在。 */
    NOT_FOUND,

    /** 操作冲突（如并发条件冲突、桶非空等）。 */
    CONFLICT,

    /** 存储后端不可达 / 不可用（连接失败、超时等），消费方应 fail-closed。 */
    UNAVAILABLE,

    /** 已到达后端但发生非前述分类的 IO / 协议错误。 */
    IO_ERROR
}
