package com.linkroa.deepdataagent.shared.storage.s3;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import com.linkroa.deepdataagent.shared.storage.ObjectKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * S3 兼容协议的 {@link ObjectStorage} 实现（RustFS / MinIO / OSS 共用）。
 * <p>承载唯一一份 S3 协议逻辑：流式上传 / 下载、全量翻页列举、单删与前缀批量删除、
 * 启动桶确保。桶固定为配置桶、不出现在方法签名；协议异常统一翻译为
 * {@link ObjectStorageException}（404 类→NOT_FOUND、409 类→CONFLICT、5xx / 连接失败
 * →UNAVAILABLE、其余→IO_ERROR）。</p>
 * <p><b>列举必须翻页</b>：单次 ListObjectsV2 单页上限 1000 key，本实现以 continuationToken
 * 循环至 {@code isTruncated=false}，杜绝孤儿对象对账在 >1000 key 时静默漏扫。</p>
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    /** contentType 缺省兜底值。 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** S3 ListObjectsV2 / DeleteObjects 单页 / 单批 key 上限。 */
    private static final int S3_PAGE_SIZE = 1000;

    /** 桶不存在错误码。 */
    private static final String CODE_NO_SUCH_BUCKET = "NoSuchBucket";

    /** 对象不存在错误码。 */
    private static final String CODE_NO_SUCH_KEY = "NoSuchKey";

    /** 建桶时桶已被本人持有错误码（幂等吞掉）。 */
    private static final String CODE_BUCKET_ALREADY_OWNED = "BucketAlreadyOwnedByYou";

    /** 建桶时桶已被他人持有错误码（按冲突处理）。 */
    private static final String CODE_BUCKET_ALREADY_EXISTS = "BucketAlreadyExists";

    /** 桶非空错误码。 */
    private static final String CODE_BUCKET_NOT_EMPTY = "BucketNotEmpty";

    private final S3Client client;

    private final String bucket;

    /**
     * @param client 已按后端类型组装的 S3 客户端
     * @param bucket 固定配置桶
     */
    public S3ObjectStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        ObjectKeys.validateKey(key);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType == null ? DEFAULT_CONTENT_TYPE : contentType)
                .contentLength(size)
                .build();
        try {
            client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (S3Exception e) {
            throw translate("写入对象", key, e);
        } catch (SdkClientException e) {
            throw unavailable("写入对象", key, e);
        }
    }

    @Override
    public InputStream get(String key) {
        ObjectKeys.validateKey(key);
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            return client.getObject(request);
        } catch (S3Exception e) {
            throw translate("读取对象", key, e);
        } catch (SdkClientException e) {
            throw unavailable("读取对象", key, e);
        }
    }

    @Override
    public List<ObjectMetadata> list(String prefix) {
        ObjectKeys.validatePrefix(prefix);
        List<ObjectMetadata> result = new ArrayList<>();
        String continuationToken = null;
        try {
            do {
                ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .maxKeys(S3_PAGE_SIZE);
                if (continuationToken != null) {
                    builder.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = client.listObjectsV2(builder.build());
                if (response.contents() != null) {
                    for (S3Object object : response.contents()) {
                        // 列举响应不含 contentType，置 null；etag 仅技术信息，禁用于完整性校验
                        result.add(new ObjectMetadata(object.key(), object.size(), null,
                                object.lastModified() == null ? null : object.lastModified(),
                                object.eTag()));
                    }
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
            return result;
        } catch (S3Exception e) {
            throw translate("列举对象", prefix, e);
        } catch (SdkClientException e) {
            throw unavailable("列举对象", prefix, e);
        }
    }

    @Override
    public void delete(String key) {
        ObjectKeys.validateKey(key);
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            // S3 删除幂等：对象不存在同样返回成功
            client.deleteObject(request);
        } catch (S3Exception e) {
            throw translate("删除对象", key, e);
        } catch (SdkClientException e) {
            throw unavailable("删除对象", key, e);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        ObjectKeys.validatePrefix(prefix);
        List<ObjectMetadata> objects = list(prefix);
        List<ObjectIdentifier> batch = new ArrayList<>(S3_PAGE_SIZE);
        for (ObjectMetadata metadata : objects) {
            batch.add(ObjectIdentifier.builder().key(metadata.key()).build());
            if (batch.size() == S3_PAGE_SIZE) {
                deleteBatch(batch, prefix);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            deleteBatch(batch, prefix);
        }
    }

    @Override
    public void ensureReady() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                createBucketIfAbsent();
                return;
            }
            throw translate("桶就绪检查", bucket, e);
        } catch (SdkClientException e) {
            throw unavailable("桶就绪检查", bucket, e);
        }
        log.info("对象存储配置桶就绪: bucket={}", bucket);
    }

    /**
     * 缺桶时创建；桶已被本人持有视为幂等成功，其余冲突 / 失败 fail-fast。
     */
    private void createBucketIfAbsent() {
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("对象存储配置桶已自动创建: bucket={}", bucket);
        } catch (S3Exception e) {
            String code = errorCode(e);
            if (CODE_BUCKET_ALREADY_OWNED.equals(code)) {
                return;
            }
            if (CODE_BUCKET_ALREADY_EXISTS.equals(code) || CODE_BUCKET_NOT_EMPTY.equals(code)
                    || e.statusCode() == 409) {
                throw new ObjectStorageException(ObjectStorageErrorKind.CONFLICT,
                        "配置桶已存在且非本人持有: " + bucket, e);
            }
            throw translate("创建配置桶", bucket, e);
        } catch (SdkClientException e) {
            throw unavailable("创建配置桶", bucket, e);
        }
    }

    /**
     * 批量删除一批对象（DeleteObjects 单批 ≤1000 key）。
     *
     * @param identifiers 待删对象标识（非空、≤1000）
     * @param prefix      关联前缀（仅错误信息）
     */
    private void deleteBatch(Collection<ObjectIdentifier> identifiers, String prefix) {
        DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(identifiers).build())
                .build();
        try {
            client.deleteObjects(request);
        } catch (S3Exception e) {
            throw translate("批量删除对象", prefix, e);
        } catch (SdkClientException e) {
            throw unavailable("批量删除对象", prefix, e);
        }
    }

    /**
     * S3 协议异常 → 技术异常分类。
     *
     * @param action 动作描述（仅日志 / 消息）
     * @param target 对象 key 或桶名
     * @param e      S3 异常
     * @return 翻译后的技术异常
     */
    private ObjectStorageException translate(String action, String target, S3Exception e) {
        String code = errorCode(e);
        if (CODE_NO_SUCH_BUCKET.equals(code) || CODE_NO_SUCH_KEY.equals(code) || e.statusCode() == 404) {
            return new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND,
                    action + "目标不存在: " + target, e);
        }
        if (CODE_BUCKET_NOT_EMPTY.equals(code) || CODE_BUCKET_ALREADY_EXISTS.equals(code)
                || e.statusCode() == 409 || e.statusCode() == 412) {
            return new ObjectStorageException(ObjectStorageErrorKind.CONFLICT,
                    action + "发生冲突: " + target, e);
        }
        if (e.statusCode() >= 500) {
            return new ObjectStorageException(ObjectStorageErrorKind.UNAVAILABLE,
                    action + "时存储端服务异常: " + target, e);
        }
        return new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                action + "失败: " + target, e);
    }

    /**
     * 连接层异常（不可达 / 超时等）→ UNAVAILABLE，消费方据此 fail-closed。
     *
     * @param action 动作描述
     * @param target 对象 key 或桶名
     * @param e      客户端异常
     * @return 翻译后的技术异常
     */
    private ObjectStorageException unavailable(String action, String target, SdkClientException e) {
        return new ObjectStorageException(ObjectStorageErrorKind.UNAVAILABLE,
                action + "时对象存储不可达: " + target, e);
    }

    /**
     * 判断 S3 异常是否为「不存在」（无错误码时以 404 兜底）。
     *
     * @param e S3 异常
     * @return 不存在返回 true
     */
    private boolean isNotFound(S3Exception e) {
        return CODE_NO_SUCH_BUCKET.equals(errorCode(e)) || e.statusCode() == 404;
    }

    /**
     * 提取 S3 错误码，缺失返回 null。
     *
     * @param e S3 异常
     * @return 错误码或 null
     */
    private static String errorCode(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }
}
