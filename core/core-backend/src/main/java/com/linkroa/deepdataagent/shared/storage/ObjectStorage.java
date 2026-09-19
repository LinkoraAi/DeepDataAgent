package com.linkroa.deepdataagent.shared.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 对象存储技术端口（shared 技术能力，非业务限界上下文）。
 * <p>抽象「本地磁盘布局」与「S3 兼容协议对象存储」两类后端，提供流式对象存取原语；
 * 桶 / 存储根由配置固定，不出现在方法签名中。业务 BC（file / skill）MUST NOT 直接依赖
 * 本端口，应在各自 application.port 定义业务语义端口、由 infrastructure 装配适配器
 * 调用本端口并完成 key 命名空间规划与异常翻译。</p>
 * <p>对象 key 一律为相对命名空间路径（如 {@code files/<file_id>}、
 * {@code skills/<skill_id>/<version>/SKILL.md}），经 {@link ObjectKeys} 校验。</p>
 */
public interface ObjectStorage {

    /**
     * 写入对象（覆盖语义由「key 由系统按 ID 确定性派生 + 业务写一次性」保证，端口不做条件写）。
     * <p>实现须原子可见：并发 / 失败读只能读到完整旧对象或完整新对象。</p>
     *
     * @param key         对象 key（非空，经 {@link ObjectKeys} 校验）
     * @param content     内容输入流（调用方不要求关闭，实现负责消费与关闭）
     * @param size        内容字节数（须与流实际长度一致）
     * @param contentType 内容类型（null 时实现按 application/octet-stream 兜底）
     * @throws ObjectStorageException 写入失败（UNAVAILABLE / IO_ERROR / CONFLICT）
     */
    void put(String key, InputStream content, long size, String contentType);

    /**
     * 读取对象内容流（调用方负责关闭返回的流）。
     *
     * @param key 对象 key
     * @return 对象内容流
     * @throws ObjectStorageException 对象不存在时 kind={@link ObjectStorageErrorKind#NOT_FOUND}
     */
    InputStream get(String key);

    /**
     * 按前缀列出对象（全量列举，实现 MUST 内部翻页至结束，禁止单页截断）。
     *
     * @param prefix key 前缀（允许以 {@code /} 结尾表示目录前缀；非空）
     * @return 命中对象的元数据列表（无命中返回空列表）
     * @throws ObjectStorageException 列举失败
     */
    List<ObjectMetadata> list(String prefix);

    /**
     * 删除单个对象（幂等：对象不存在时静默成功）。
     *
     * @param key 对象 key
     * @throws ObjectStorageException 删除失败（非「不存在」类错误）
     */
    void delete(String key);

    /**
     * 删除指定前缀下的全部对象（用于有界命名空间的整体清空 / 回收）。
     * <p>实现 MUST 全量翻页列举后批量删除；前缀仅允许由系统传入的有界命名空间，
     * 调用方不得以前缀匹配方式删除无法精确解析归属的 key。</p>
     *
     * @param prefix key 前缀（非空，通常以 {@code /} 结尾）
     * @throws ObjectStorageException 删除失败
     */
    void deletePrefix(String prefix);

    /**
     * 启动就绪准备（默认空实现）：后端可在此创建本地根目录 / 确保配置桶存在，
     * 失败应 fail-fast 中止启动。仅在应用启动装配期由配置调用一次。
     */
    default void ensureReady() {
        // 默认无就绪动作
    }
}
