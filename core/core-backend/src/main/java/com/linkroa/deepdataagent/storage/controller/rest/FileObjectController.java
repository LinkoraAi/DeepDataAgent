package com.linkroa.deepdataagent.storage.controller.rest;

import com.linkroa.deepdataagent.storage.application.command.CreateBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.DeleteBucketCommand;
import com.linkroa.deepdataagent.storage.application.command.PutFileObjectCommand;
import com.linkroa.deepdataagent.storage.application.query.BucketExistsQuery;
import com.linkroa.deepdataagent.storage.application.query.ListFileObjectsQuery;
import com.linkroa.deepdataagent.storage.application.service.FileObjectStorageAppService;
import com.linkroa.deepdataagent.storage.controller.convert.FileMetadataResponseConvert;
import com.linkroa.deepdataagent.storage.controller.request.CreateBucketRequest;
import com.linkroa.deepdataagent.storage.controller.response.BucketKeyResponse;
import com.linkroa.deepdataagent.storage.controller.response.FileKeyResponse;
import com.linkroa.deepdataagent.storage.controller.response.FileMetadataResponse;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 文件对象管理 REST 控制器（统一前缀 {@code /api/v1/storage}）。
 * <p>对外暴露三大能力：桶生命周期（{@code /buckets} 建/删/查存在性）、
 * 对象管理（{@code /files} 上传、列出、下载（attachment）、在线预览（inline）、
 * 元数据查询与删除，均需 query 显式传桶）。下载与预览采用后端流式代理，不在内存全量缓存。
 * 跨 BC 的进程内调用请依赖 {@code storage.api.FileObjectStorageApi}。</p>
 */
@RestController
@RequestMapping(path = "/storage", version = ApiVersionConstants.CURRENT_API_VERSION)
public class FileObjectController {

    private final FileObjectStorageAppService appService;

    public FileObjectController(FileObjectStorageAppService appService) {
        this.appService = appService;
    }

    // ==================== 桶生命周期端点 ====================

    /**
     * 创建桶（桶重名返回 409 冲突，不幂等）。
     *
     * @param request 创建桶请求（body 携带桶名）
     * @return 已创建的桶名
     */
    @PostMapping("/buckets")
    public ApiResponse<BucketKeyResponse> createBucket(@RequestBody(required = false) CreateBucketRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空，须携带 bucket 桶名");
        }
        appService.createBucket(new CreateBucketCommand(request.bucket()));
        return ApiResponse.success(new BucketKeyResponse(request.bucket()));
    }

    /**
     * 删除桶（仅允许空桶；桶缺失返回 404，非空返回 409）。
     *
     * @param bucket 桶名（path 变量，无斜杠可安全入路径）
     * @return 操作成功响应
     */
    @PostMapping("/buckets/{bucket}")
    public ApiResponse<Void> deleteBucket(@PathVariable("bucket") String bucket) {
        appService.deleteBucket(new DeleteBucketCommand(bucket));
        return ApiResponse.success(null);
    }

    /**
     * 查询桶存在性（支持「先查后建」）。
     *
     * @param bucket 桶名（path 变量）
     * @return 桶是否存在
     */
    @GetMapping("/buckets/{bucket}")
    public ApiResponse<Boolean> bucketExists(@PathVariable("bucket") String bucket) {
        return ApiResponse.success(appService.bucketExists(new BucketExistsQuery(bucket)));
    }

    // ==================== 对象管理端点 ====================

    /**
     * 上传文件对象（multipart/form-data）。
     *
     * @param file      文件内容
     * @param bucket    目标桶（必填，须已创建）
     * @param objectKey 对象唯一标识（必填，调用方自定义存储路径）
     * @param force     是否显式覆盖已有对象（默认 false，已存在将返回 409）
     * @return 已存储的 objectKey
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileKeyResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("bucket") String bucket,
            @RequestParam("objectKey") String objectKey,
            @RequestParam(value = "force", defaultValue = "false") boolean force
    ) throws IOException {
        String storedKey = appService.putObject(
                new PutFileObjectCommand(bucket, objectKey, file.getInputStream(), file.getSize(),
                        file.getContentType(), force));
        return ApiResponse.success(new FileKeyResponse(storedKey));
    }

    /**
     * 按前缀列出文件对象。
     *
     * @param bucket 目标桶（必填）
     * @param prefix 对象 key 前缀（可选）
     * @return 对象元数据列表
     */
    @GetMapping("/files")
    public ApiResponse<List<FileMetadataResponse>> list(
            @RequestParam("bucket") String bucket,
            @RequestParam(value = "prefix", required = false) String prefix
    ) {
        return ApiResponse.success(appService.listObjects(new ListFileObjectsQuery(bucket, prefix)).stream()
                .map(FileMetadataResponseConvert.INSTANCE::toResponse)
                .toList());
    }

    /**
     * 下载文件对象（attachment 流式代理）。
     *
     * @param bucket    目标桶（必填）
     * @param objectKey 对象唯一标识
     * @return 文件内容流
     */
    @GetMapping("/files/download")
    public ResponseEntity<InputStreamResource> download(
            @RequestParam("bucket") String bucket,
            @RequestParam("objectKey") String objectKey) {
        FileMetadata metadata = appService.headObject(bucket, objectKey);
        InputStream stream = appService.getObject(bucket, objectKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filenameOf(objectKey) + "\"")
                .contentType(resolveContentType(metadata))
                .contentLength(metadata.size())
                .body(new InputStreamResource(stream));
    }

    /**
     * 在线预览文件对象（inline 流式代理）。
     *
     * @param bucket    目标桶（必填）
     * @param objectKey 对象唯一标识
     * @return 文件内容流
     */
    @GetMapping("/files/preview")
    public ResponseEntity<InputStreamResource> preview(
            @RequestParam("bucket") String bucket,
            @RequestParam("objectKey") String objectKey) {
        FileMetadata metadata = appService.headObject(bucket, objectKey);
        InputStream stream = appService.getObject(bucket, objectKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(resolveContentType(metadata))
                .contentLength(metadata.size())
                .body(new InputStreamResource(stream));
    }

    /**
     * 查询文件对象元数据。
     *
     * @param bucket    目标桶（必填）
     * @param objectKey 对象唯一标识
     * @return 对象元数据
     */
    @GetMapping("/files/metadata")
    public ApiResponse<FileMetadataResponse> metadata(
            @RequestParam("bucket") String bucket,
            @RequestParam("objectKey") String objectKey) {
        return ApiResponse.success(
                FileMetadataResponseConvert.INSTANCE.toResponse(appService.headObject(bucket, objectKey)));
    }

    /**
     * 删除文件对象（幂等）。
     *
     * @param bucket    目标桶（必填）
     * @param objectKey 对象唯一标识
     * @return 操作成功响应
     */
    @PostMapping("/files")
    public ApiResponse<Void> delete(
            @RequestParam("bucket") String bucket,
            @RequestParam("objectKey") String objectKey) {
        appService.deleteObject(bucket, objectKey);
        return ApiResponse.success(null);
    }

    /**
     * 从 objectKey 中提取文件名（末路径段），用于 attachment 响应头。
     *
     * @param objectKey 对象唯一标识
     * @return 文件名（无路径段时返回 objectKey 本身）
     */
    private String filenameOf(String objectKey) {
        int idx = objectKey.lastIndexOf('/');
        return idx < 0 ? objectKey : objectKey.substring(idx + 1);
    }

    /**
     * 解析下载/预览响应内容类型：元数据缺失时回退为通用二进制流。
     *
     * @param metadata 文件对象元数据
     * @return 内容类型
     */
    private MediaType resolveContentType(FileMetadata metadata) {
        return StringUtils.isBlank(metadata.contentType())
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(metadata.contentType());
    }
}