package com.linkroa.deepdataagent.file.controller.rest;

import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import com.linkroa.deepdataagent.file.application.convert.FileCommandConvert;
import com.linkroa.deepdataagent.file.application.query.ListFilesQuery;
import com.linkroa.deepdataagent.file.application.service.FileApplicationService;
import com.linkroa.deepdataagent.file.controller.convert.FileResponseConvert;
import com.linkroa.deepdataagent.file.controller.response.FileListResponse;
import com.linkroa.deepdataagent.file.controller.response.FileResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件 REST 控制器（版本化前缀，有效路径 {@code /api/v1/cloud/files}，文件一等资源端点）。
 * <p>上传走 multipart（purpose 必填、≤50MB、仅文本类）；列表为游标分页
 * （purpose / scope_id 过滤）；{@code /content} 直接二进制流下载
 * （downloadable 门禁 403、存储缺失 500）；不提供 Managed delete / search 端点
 * （删除仅 Forward 面，清理随 Session 生命周期承载）。</p>
 */
@RestController
@RequestMapping(path = "/cloud/files", version = ApiVersionConstants.CURRENT_API_VERSION)
public class FileController {

    @Resource
    private FileApplicationService applicationService;

    /**
     * multipart 上传文件（file 部分 + 可选 filename + purpose + metadata）。
     *
     * @param file     文件部分（仅文本类，≤50MB）
     * @param filename 显式文件名（可空，优先于 multipart 原始文件名；目录段由领域清理后落库）
     * @param purpose  文件用途（user_upload / tool_output / skill_output / session_resource / agent_output，必填）
     * @param metadata 自定义元数据 JSON 文本（可空，≤8KB）
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileResponse> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(name = "filename", required = false) String filename,
                                            @RequestParam(name = "purpose", required = false) String purpose,
                                            @RequestParam(name = "metadata", required = false) String metadata)
            throws IOException {
        String effectiveFilename = StringUtils.isNotBlank(filename) ? filename : file.getOriginalFilename();
        CreateFileCommand command = FileCommandConvert.INSTANCE.toCreateCommand(
                effectiveFilename, purpose, metadata, file.getBytes());
        return ApiResponse.success(
                FileResponseConvert.INSTANCE.toResponse(applicationService.upload(command)));
    }

    /**
     * 游标分页列出文件（对齐 {@code GET /files}，支持 purpose / scope_id 过滤）。
     */
    @GetMapping
    public ApiResponse<FileListResponse> list(
            @RequestParam(name = "purpose", required = false) String purpose,
            @RequestParam(name = "scope_id", required = false) String scopeId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        FileApplicationService.FilePage page = applicationService.list(new ListFilesQuery(
                purpose, scopeId, cursor, limit == null ? 0 : limit));
        List<FileResponse> data = page.data().stream()
                .map(FileResponseConvert.INSTANCE::toResponse)
                .toList();
        return ApiResponse.success(new FileListResponse(data, page.nextCursor()));
    }

    /**
     * 文件详情（元数据，不含内容）。
     */
    @GetMapping("/{id}")
    public ApiResponse<FileResponse> detail(@PathVariable String id) {
        return ApiResponse.success(
                FileResponseConvert.INSTANCE.toResponse(applicationService.getMeta(id)));
    }

    /**
     * 直接下载文件内容（二进制流；downloadable=false → 403，存储缺失 / 校验不一致 → 500）。
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        FileApplicationService.FileDownload download = applicationService.downloadContent(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(download.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(download.content());
    }
}
