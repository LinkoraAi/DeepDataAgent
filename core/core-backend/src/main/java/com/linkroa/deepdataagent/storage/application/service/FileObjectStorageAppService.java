package com.linkroa.deepdataagent.storage.application.service;

import com.linkroa.deepdataagent.storage.application.command.CreateBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.DeleteBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.PutFileObjectCommand;
import com.linkroa.deepdataagent.storage.application.query.BucketExistsQuery;
import com.linkroa.deepdataagent.storage.application.query.ListFileObjectsQuery;
import com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException;
import com.linkroa.deepdataagent.storage.domain.exception.FileKeyConflictException;
import com.linkroa.deepdataagent.storage.domain.model.BucketNameValidator;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import com.linkroa.deepdataagent.storage.domain.repository.FileObjectStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文件对象存储应用服务。
 * <p>承载对象存储流程的编排与入参校验：桶名合法性（非法 → 400）、objectKey 合法性（非法 → 400）、
 * 空内容（→ 400）、已存在且未显式覆盖（→ 409）；桶生命周期编排（建/删/查：
 * 桶重名与非空删除冲突透出 409、桶缺失透出 404），再编排领域端口完成存取。</p>
 */
@Service
public class FileObjectStorageAppService {

    /** objectKey 长度上限（对齐主流对象存储 key 约束） */
    private static final int MAX_OBJECT_KEY_LENGTH = 1024;

    /** objectKey 允许的字符集：字母 / 数字 / 点 / 下划线 / 中划线 / 斜杠（路径分段） */
    private static final Pattern OBJECT_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._/\\-]+$");

    /** 路径穿越片段（禁止出现在 objectKey 的任一路径段中） */
    private static final String PATH_TRAVERSAL_SEGMENT = "..";

    private final FileObjectStorage fileObjectStorage;

    public FileObjectStorageAppService(FileObjectStorage fileObjectStorage) {
        this.fileObjectStorage = fileObjectStorage;
    }

    /**
     * 上传文件对象。
     *
     * @param command 上传命令（含目标桶）
     * @return 已存储的 objectKey
     * @throws IllegalArgumentException 桶名/objectKey 非法或内容为空
     * @throws FileKeyConflictException 对象已存在且未显式声明覆盖
     */
    public String putObject(PutFileObjectCommand command) {
        validateBucket(command.bucket());
        validateObjectKey(command.objectKey());
        if (command.size() <= 0) {
            throw new IllegalArgumentException("上传内容不能为空");
        }
        if (!command.force() && fileObjectStorage.exists(command.bucket(), command.objectKey())) {
            throw new FileKeyConflictException("对象已存在: " + command.objectKey() + "（未显式声明覆盖）");
        }
        fileObjectStorage.put(command.bucket(), command.objectKey(), command.content(),
                command.size(), command.contentType(), command.force());
        return command.objectKey();
    }

    /**
     * 读取对象内容流（供下载 / 预览流式代理）。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象内容流（调用方负责关闭并释放连接）
     */
    public InputStream getObject(String bucket, String objectKey) {
        validateBucket(bucket);
        validateObjectKey(objectKey);
        return fileObjectStorage.get(bucket, objectKey);
    }

    /**
     * 查询对象元数据。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     * @return 对象元数据
     */
    public FileMetadata headObject(String bucket, String objectKey) {
        validateBucket(bucket);
        validateObjectKey(objectKey);
        return fileObjectStorage.head(bucket, objectKey);
    }

    /**
     * 按前缀列出对象。
     *
     * @param query 列表查询（含目标桶；prefix 可为空串，表示列出全部）
     * @return 对象元数据列表
     */
    public List<FileMetadata> listObjects(ListFileObjectsQuery query) {
        validateBucket(query.bucket());
        String prefix = query.prefix() == null ? "" : query.prefix();
        return fileObjectStorage.list(query.bucket(), prefix);
    }

    /**
     * 删除对象（幂等）。
     *
     * @param bucket    目标桶
     * @param objectKey 对象唯一标识
     */
    public void deleteObject(String bucket, String objectKey) {
        validateBucket(bucket);
        validateObjectKey(objectKey);
        fileObjectStorage.delete(bucket, objectKey);
    }

    /**
     * 创建桶（桶重名抛冲突 409，不幂等，供调用方识别所有权冲突）。
     * 兼容幂等型存储后端（如 RustFS 重名建桶不抛错）：先探测存在性，已存在则按规格返回 409。
     *
     * @param command 创建桶命令
     * @throws IllegalArgumentException 桶名非法
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException 桶已存在
     */
    public void createBucket(CreateBucketCommand command) {
        validateBucket(command.bucket());
        if (fileObjectStorage.bucketExists(command.bucket())) {
            throw new BucketConflictException("桶已存在: " + command.bucket());
        }
        fileObjectStorage.createBucket(command.bucket());
    }

    /**
     * 删除桶（仅允许空桶；桶缺失抛 404，非空抛冲突 409）。
     *
     * @param command 删除桶命令
     * @throws IllegalArgumentException 桶名非法
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketNotFoundException 桶不存在
     * @throws com.linkroa.deepdataagent.storage.domain.exception.BucketConflictException 桶非空
     */
    public void deleteBucket(DeleteBucketCommand command) {
        validateBucket(command.bucket());
        fileObjectStorage.deleteBucket(command.bucket());
    }

    /**
     * 判断桶是否存在（支持「先查后建」）。
     *
     * @param query 桶存在性查询
     * @return 存在返回 true，否则 false
     * @throws IllegalArgumentException 桶名非法
     */
    public boolean bucketExists(BucketExistsQuery query) {
        validateBucket(query.bucket());
        return fileObjectStorage.bucketExists(query.bucket());
    }

    /**
     * 校验桶名合法性（S3 命名规范子集，领域层校验器，REST 与进程内调用共用）。
     *
     * @param bucket 桶名
     * @throws IllegalArgumentException 校验不通过
     */
    private void validateBucket(String bucket) {
        BucketNameValidator.validate(bucket);
    }

    /**
     * 校验 objectKey 合法性：非空、长度上限、字符白名单、禁止路径穿越片段。
     *
     * @param objectKey 对象唯一标识
     * @throws IllegalArgumentException 校验不通过
     */
    private void validateObjectKey(String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        if (objectKey.length() > MAX_OBJECT_KEY_LENGTH) {
            throw new IllegalArgumentException("objectKey 超出长度上限: " + MAX_OBJECT_KEY_LENGTH);
        }
        if (!OBJECT_KEY_PATTERN.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("objectKey 含非法字符，仅允许字母/数字/点/下划线/中划线/斜杠");
        }
        if (containsPathTraversal(objectKey)) {
            throw new IllegalArgumentException("objectKey 不能包含路径穿越片段(..)");
        }
    }

    /**
     * 判断 objectKey 的任一路径段是否为穿越片段。
     *
     * @param objectKey 对象唯一标识
     * @return 存在穿越片段返回 true
     */
    private boolean containsPathTraversal(String objectKey) {
        for (String segment : objectKey.split("/")) {
            if (PATH_TRAVERSAL_SEGMENT.equals(segment)) {
                return true;
            }
        }
        return false;
    }
}