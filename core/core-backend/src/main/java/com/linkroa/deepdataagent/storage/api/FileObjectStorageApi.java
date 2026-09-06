package com.linkroa.deepdataagent.storage.api;

import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;

import java.io.InputStream;
import java.util.List;

/**
 * 文件对象存储服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>与领域出站端口分离：本接口是 provider BC 对外暴露的跨 BC 文件对象操作能力面，
 * 消费方（agent / runtime / 知识库等）依赖本接口而非进程内领域端口实现。当前由
 * {@code DefaultFileObjectStorageApi} 进程内实现，未来接入 Feign 时消费方无需改动。
 * 桶为对象存储一等资源：对象操作显式传桶，桶生命周期（建/删/查）由调用方自行维护。</p>
 */
public interface FileObjectStorageApi {
    
    /**
     * 创建桶（桶已存在抛冲突 409，不幂等）。
     *
     * @param bucket 桶名（S3 命名规范子集）
     */
    void createBucket(String bucket);

    /**
     * 删除桶（仅允许空桶；桶缺失抛 404，非空抛冲突 409）。
     *
     * @param bucket 桶名
     */
    void deleteBucket(String bucket);

    /**
     * 判断桶是否存在（支持「先查后建」）。
     *
     * @param bucket 桶名
     * @return 存在返回 true，否则 false
     */
    boolean bucketExists(String bucket);

    /**
     * 上传文件对象到调用方指定的桶与 objectKey。
     *
     * @param bucket      目标桶（须已通过 createBucket 建立）
     * @param objectKey   对象唯一标识（自定义存储路径，命名空间由调用方规划）
     * @param content     文件内容流
     * @param size        内容字节数
     * @param contentType 内容类型
     * @param force       是否显式覆盖已有对象（false 时已存在将抛冲突）
     * @return 已存储的 objectKey
     */
    String putObject(String bucket, String objectKey, InputStream content, long size, String contentType,
                     boolean force);

    /**
     * 读取对象内容流（供下载 / 预览）。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象内容流（调用方负责关闭）
     */
    InputStream getObject(String bucket, String objectKey);

    /**
     * 查询对象元数据。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象元数据
     */
    FileMetadata headObject(String bucket, String objectKey);

    /**
     * 按前缀列出对象。
     *
     * @param bucket 目标桶
     * @param prefix 对象 key 前缀（可为空串）
     * @return 对象元数据列表
     */
    List<FileMetadata> listObjects(String bucket, String prefix);

    /**
     * 删除对象（幂等）。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     */
    void deleteObject(String bucket, String objectKey);

}