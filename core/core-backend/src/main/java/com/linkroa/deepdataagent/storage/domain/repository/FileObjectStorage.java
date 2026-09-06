package com.linkroa.deepdataagent.storage.domain.repository;

import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;

import java.io.InputStream;
import java.util.List;

/**
 * 文件对象存储端口（领域出站端口）。
 * <p>领域层仅依赖本端口抽象，底层 Provider（RustFS / MinIO / OSS）差异由
 * {@code infrastructure.provider} 实现收敛，不渗入业务。
 * 桶为对象存储的一等资源：对象操作显式传桶，桶生命周期由调用方自行维护（建/删/查）。</p>
 */
public interface FileObjectStorage {

    /**
     * 写入对象。
     *
     * @param bucket      目标桶（须已通过 {@link #createBucket} 建立）
     * @param objectKey   对象唯一标识（调用方自定义存储路径）
     * @param content     文件内容流
     * @param size        内容字节数（须与流实际长度一致）
     * @param contentType 内容类型
     * @param force       是否显式覆盖已有对象；false 时通过 S3 原子条件写入防并发覆盖
     */
    void put(String bucket, String objectKey, InputStream content, long size, String contentType, boolean force);

    /**
     * 读取对象内容流。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象内容流（调用方负责关闭）
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException 桶不存在
     * @throws com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException 对象不存在
     */
    InputStream get(String bucket, String objectKey);

    /**
     * 判断对象是否存在。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 存在返回 true，否则 false
     */
    boolean exists(String bucket, String objectKey);

    /**
     * 查询对象元数据。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象元数据
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException 桶不存在
     * @throws com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException 对象不存在
     */
    FileMetadata head(String bucket, String objectKey);

    /**
     * 按前缀列出对象。
     *
     * @param bucket 目标桶
     * @param prefix 对象 key 前缀（可为空串，列出全部）
     * @return 对象元数据列表（不包含目录占位符对应的空对象依赖上层过滤）
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException 桶不存在
     */
    List<FileMetadata> list(String bucket, String prefix);

    /**
     * 删除对象（幂等：对象不存在不报错）。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     */
    void delete(String bucket, String objectKey);

    /**
     * 创建桶（桶已存在时抛冲突异常，不幂等，供调用方识别所有权冲突）。
     *
     * @param bucket 桶名
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException 桶已存在
     */
    void createBucket(String bucket);

    /**
     * 删除桶（仅允许空桶；桶不存在抛异常，与对象删除的幂等语义刻意区分）。
     *
     * @param bucket 桶名
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException 桶不存在
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException 桶非空
     */
    void deleteBucket(String bucket);

    /**
     * 判断桶是否存在（支持「先查后建」）。
     *
     * @param bucket 桶名
     * @return 存在返回 true，否则 false
     */
    boolean bucketExists(String bucket);
}