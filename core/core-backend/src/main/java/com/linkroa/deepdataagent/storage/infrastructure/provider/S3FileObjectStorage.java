package com.linkroa.deepdataagent.storage.infrastructure.provider;

import com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException;
import com.linkroa.deepdataagent.storage.domain.exception.FileKeyConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.FileObjectStorageException;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import com.linkroa.deepdataagent.storage.domain.repository.FileObjectStorage;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 领域端口 {@link FileObjectStorage} 的 S3 兼容协议通用实现。
 * <p>承载唯一一份协议逻辑：上传/下载/存在性预判/元数据/列表/删除，
 * 以及桶生命周期（建/删/查）。桶为显式入参，不依赖全局默认桶；Provider 差异
 * 收敛于 {@link S3ClientProviderRegistry}；异常按 S3 错误码细分为领域异常
 * （404 桶不存在/对象不存在、409 键冲突/建桶冲突/删桶冲突、其余存储异常）。</p>
 */
@Component
public class S3FileObjectStorage implements FileObjectStorage {

    /** S3 If-None-Match 条件写入头值：仅当对象不存在时才写入（原子防覆盖） */
    private static final String IF_NONE_MATCH_WILDCARD = "*";

    /** 桶不存在的 S3 错误码 */
    private static final String ERROR_CODE_NO_SUCH_BUCKET = "NoSuchBucket";

    /** 对象不存在的 S3 错误码 */
    private static final String ERROR_CODE_NO_SUCH_KEY = "NoSuchKey";

    /** 桶非空不可删的 S3 错误码 */
    private static final String ERROR_CODE_BUCKET_NOT_EMPTY = "BucketNotEmpty";

    /** 建桶时桶已被他人持有的 S3 错误码 */
    private static final String ERROR_CODE_BUCKET_ALREADY_EXISTS = "BucketAlreadyExists";

    /** 建桶时桶已被本人持有的 S3 错误码 */
    private static final String ERROR_CODE_BUCKET_ALREADY_OWNED_BY_YOU = "BucketAlreadyOwnedByYou";

    private final S3ClientProviderRegistry registry;

    public S3FileObjectStorage(S3ClientProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void put(String bucket, String objectKey, InputStream content, long size, String contentType,
                    boolean force) {
        String resolvedContentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(resolvedContentType)
                .contentLength(size);
        if (!force) {
            // 原子条件写入：并发下仅当 key 不存在才能写入成功，杜绝 TOCTOU 竞态覆盖
            builder.ifNoneMatch(IF_NONE_MATCH_WILDCARD);
        }
        try {
            registry.getClient().putObject(builder.build(), RequestBody.fromInputStream(content, size));
        } catch (S3Exception e) {
            throw translateObjectException(bucket, objectKey, e);
        }
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        try {
            return registry.getClient().getObject(request);
        } catch (S3Exception e) {
            throw translateObjectException(bucket, objectKey, e);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        try {
            registry.getClient().headObject(request);
            return true;
        } catch (S3Exception e) {
            // 对象或桶不存在均按「不存在」处理（存在性预判路径），真实原因由后续操作错误码定位
            if (e.statusCode() == 404) {
                return false;
            }
            throw translateObjectException(bucket, objectKey, e);
        }
    }

    @Override
    public FileMetadata head(String bucket, String objectKey) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        try {
            HeadObjectResponse response = registry.getClient().headObject(request);
            return new FileMetadata(objectKey, response.contentLength(), response.contentType(),
                    response.lastModified(), response.eTag());
        } catch (S3Exception e) {
            throw translateObjectException(bucket, objectKey, e);
        }
    }

    @Override
    public List<FileMetadata> list(String bucket, String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix == null ? "" : prefix)
                .build();
        try {
            ListObjectsV2Response response = registry.getClient().listObjectsV2(request);
            if (response.contents() == null || response.contents().isEmpty()) {
                return Collections.emptyList();
            }
            List<FileMetadata> result = new ArrayList<>(response.contents().size());
            for (S3Object object : response.contents()) {
                // 列表响应不含 contentType，置空由上层自行判定（如预览按扩展名推断）
                result.add(new FileMetadata(object.key(), object.size(), null,
                        object.lastModified(), object.eTag()));
            }
            return result;
        } catch (S3Exception e) {
            throw translateObjectException(bucket, prefix == null ? "" : prefix, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        try {
            // S3 删除语义幂等：对象不存在时同样返回成功
            registry.getClient().deleteObject(request);
        } catch (S3Exception e) {
            throw translateObjectException(bucket, objectKey, e);
        }
    }

    @Override
    public void createBucket(String bucket) {
        CreateBucketRequest request = CreateBucketRequest.builder()
                .bucket(bucket)
                .build();
        try {
            registry.getClient().createBucket(request);
        } catch (S3Exception e) {
            throw translateBucketException(bucket, e);
        }
    }

    @Override
    public void deleteBucket(String bucket) {
        DeleteBucketRequest request = DeleteBucketRequest.builder()
                .bucket(bucket)
                .build();
        try {
            // 空桶校验由 S3 DeleteBucket 原语保证，非空返回 BucketNotEmpty
            registry.getClient().deleteBucket(request);
        } catch (S3Exception e) {
            throw translateBucketException(bucket, e);
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        HeadBucketRequest request = HeadBucketRequest.builder()
                .bucket(bucket)
                .build();
        try {
            registry.getClient().headBucket(request);
            return true;
        } catch (S3Exception e) {
            // 桶不存在（NoSuchBucket/404）返回 false，支持「先查后建」流程
            if ("NoSuchBucket".equals(errorCode(e)) || e.statusCode() == 404) {
                return false;
            }
            throw translateBucketException(bucket, e);
        }
    }

    /**
     * 将 S3 协议异常翻译为对象操作领域异常：按错误码细分
     * （NoSuchBucket → 桶不存在 404；NoSuchKey → 对象不存在 404；
     * 412 → 键冲突 409；未知 404 → 对象不存在），其余 → 存储异常。
     *
     * @param bucket    目标桶
     * @param objectKey 操作对象 key
     * @param e         S3 异常
     * @return 领域异常
     */
    private RuntimeException translateObjectException(String bucket, String objectKey, S3Exception e) {
        String errorCode = errorCode(e);
        if (ERROR_CODE_NO_SUCH_BUCKET.equals(errorCode)) {
            return new BucketNotFoundException("桶不存在: " + bucket + "（请先创建桶）");
        }
        if (ERROR_CODE_NO_SUCH_KEY.equals(errorCode)) {
            return new ResourceNotFoundException("对象不存在: " + bucket + "/" + objectKey);
        }
        if (isBucketConflictErrorCode(errorCode)) {
            // 对象操作路径出现桶冲突错误码（异常情形），按冲突语义收敛
            return bucketConflict(e, bucket);
        }
        if (e.statusCode() == 412) {
            return new FileKeyConflictException("对象已存在: " + bucket + "/" + objectKey + "（未显式声明覆盖）");
        }
        if (e.statusCode() == 404) {
            // head 预判路径/无错误码 404：归因为对象不存在
            return new ResourceNotFoundException("对象不存在: " + bucket + "/" + objectKey);
        }
        return new FileObjectStorageException("对象存储操作失败: " + bucket + "/" + objectKey, e);
    }

    /**
     * 将 S3 协议异常翻译为桶操作领域异常：按错误码细分
     * （NoSuchBucket/404 → 桶不存在 404；BucketNotEmpty → 删桶冲突 409；
     * 建桶冲突错误码 → 409），其余 → 存储异常。
     *
     * @param bucket 操作桶名
     * @param e      S3 异常
     * @return 领域异常
     */
    private RuntimeException translateBucketException(String bucket, S3Exception e) {
        String errorCode = errorCode(e);
        if (ERROR_CODE_NO_SUCH_BUCKET.equals(errorCode) || e.statusCode() == 404) {
            return new BucketNotFoundException("桶不存在: " + bucket);
        }
        if (ERROR_CODE_BUCKET_NOT_EMPTY.equals(errorCode)) {
            return new BucketConflictException("桶非空，无法删除: " + bucket);
        }
        if (isBucketConflictErrorCode(errorCode)) {
            return bucketConflict(e, bucket);
        }
        return new FileObjectStorageException("对象存储桶操作失败: " + bucket, e);
    }

    /**
     * 判断是否为建桶冲突类错误码（桶已存在，统一译为 409 冲突）。
     *
     * @param errorCode S3 错误码
     * @return 属于建桶冲突返回 true
     */
    private static boolean isBucketConflictErrorCode(String errorCode) {
        return ERROR_CODE_BUCKET_ALREADY_EXISTS.equals(errorCode)
                || ERROR_CODE_BUCKET_ALREADY_OWNED_BY_YOU.equals(errorCode);
    }

    /**
     * 构造建桶冲突异常（桶已存在 → 409，所有权语义：冲突必须可见）。
     *
     * @param e      S3 异常
     * @param bucket 操作桶名
     * @return 桶冲突领域异常
     */
    private static BucketConflictException bucketConflict(S3Exception e, String bucket) {
        return new BucketConflictException("桶已存在: " + bucket, e);
    }

    /**
     * 提取 S3 异常错误码，缺失时返回 null。
     *
     * @param e S3 异常
     * @return 错误码或 null
     */
    private static String errorCode(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }
}