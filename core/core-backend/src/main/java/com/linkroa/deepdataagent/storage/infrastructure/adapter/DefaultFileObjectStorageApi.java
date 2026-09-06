package com.linkroa.deepdataagent.storage.infrastructure.adapter;

import com.linkroa.deepdataagent.storage.api.FileObjectStorageApi;
import com.linkroa.deepdataagent.storage.application.command.CreateBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.DeleteBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.PutFileObjectCommand;
import com.linkroa.deepdataagent.storage.application.query.BucketExistsQuery;
import com.linkroa.deepdataagent.storage.application.query.ListFileObjectsQuery;
import com.linkroa.deepdataagent.storage.application.service.FileObjectStorageAppService;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * {@link FileObjectStorageApi} 跨 BC 服务契约的进程内实现。
 * <p>纯委托 {@link FileObjectStorageAppService}，负责接口参数与命令/查询的翻译；
 * 未来该契约迁移 Feign 时，仅需在接口追加 {@code @FeignClient} 并提供远程实现，
 * 消费方依赖不变。对象操作显式传桶，桶生命周期命令/查询透传至应用服务。</p>
 */
@Component
public class DefaultFileObjectStorageApi implements FileObjectStorageApi {

    private final FileObjectStorageAppService appService;

    public DefaultFileObjectStorageApi(FileObjectStorageAppService appService) {
        this.appService = appService;
    }

    @Override
    public void createBucket(String bucket) {
        appService.createBucket(new CreateBucketCommand(bucket));
    }

    @Override
    public void deleteBucket(String bucket) {
        appService.deleteBucket(new DeleteBucketCommand(bucket));
    }

    @Override
    public boolean bucketExists(String bucket) {
        return appService.bucketExists(new BucketExistsQuery(bucket));
    }

    @Override
    public String putObject(String bucket, String objectKey, InputStream content, long size, String contentType,
                            boolean force) {
        return appService.putObject(new PutFileObjectCommand(bucket, objectKey, content, size, contentType, force));
    }

    @Override
    public InputStream getObject(String bucket, String objectKey) {
        return appService.getObject(bucket, objectKey);
    }

    @Override
    public FileMetadata headObject(String bucket, String objectKey) {
        return appService.headObject(bucket, objectKey);
    }

    @Override
    public List<FileMetadata> listObjects(String bucket, String prefix) {
        return appService.listObjects(new ListFileObjectsQuery(bucket, prefix));
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        appService.deleteObject(bucket, objectKey);
    }

}