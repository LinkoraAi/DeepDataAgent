package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.ReparseDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UploadDocumentCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListDocumentQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.application.result.DocumentContentResult;
import com.linkroa.deepdataagent.knowledgebase.application.service.DocumentApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.convert.KnowledgeBaseResponseConvert;
import com.linkroa.deepdataagent.knowledgebase.controller.request.PrecheckUploadRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.ReparseDocumentRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UploadDocumentRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DuplicateDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.UploadDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.validation.Valid;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库文档管理 REST 控制器（统一前缀 {@code /api/v1/knowledge-base/documents}）。
 * <p>上传为 multipart/form-data 单次调用：文件字节与登记元数据同请求提交，
 * 服务端负责落对象存储、计算大小与可信内容哈希。</p>
 */
@RestController
@RequestMapping(path = "/knowledge-base/documents", version = ApiVersionConstants.CURRENT_API_VERSION)
public class DocumentController {

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数（文档列表） */
    private static final int DEFAULT_SIZE = 10;

    /** 每页最大条数，防止一次拉取过多数据 */
    private static final int MAX_SIZE = 100;

    /** 删除受理响应码：受理成功、清退异步推进（202 Accepted 语义，HTTP 外层状态仍为 200） */
    private static final String ACCEPTED_RESPONSE_CODE = "202";

    /** 删除受理响应文案：文档已进入删除中状态 */
    private static final String DELETING_RESPONSE_MESSAGE = "删除中，清退任务已受理";

    /** 文档应用服务（本控制器唯一业务入口） */
    private final DocumentApplicationService applicationService;

    /**
     * 构造知识库文档管理 REST 控制器。
     *
     * @param applicationService 文档应用服务（本控制器唯一业务入口）
     */
    public DocumentController(DocumentApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 上传文档（multipart/form-data 单次调用）：{@code file} 为文件字节，{@code meta} 为登记元数据 JSON。
     * <p>源文件对象引用、文件大小与判重内容哈希全部由服务端派生，不采信调用方自报值；
     * 按知识库级去重策略执行判重处置：REJECT 时返回 409（消息携带重复摘要）、
     * SKIP 时返回命中的既有文档并置 {@code skipped=true}、OVERWRITE 时清旧登记新。</p>
     *
     * @param file 上传的文件字节
     * @param meta 登记元数据（知识库ID、文件名、文件格式、源文件关键信息、分块策略）
     * @return 上传结果（含跳过标记与文档详情）
     * @throws IOException 读取上传文件字节失败
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadDocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") UploadDocumentRequest meta
    ) throws IOException {
        UploadDocumentCommand command = new UploadDocumentCommand(
                meta.kbId(), meta.fileName(), meta.fileType(), file.getContentType(),
                meta.sourceFileProfile(), meta.chunkStrategy(), file.getBytes());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE
                .toUploadResponse(applicationService.upload(command)));
    }

    /**
     * 上传判重预检（只读）：按知识库级去重策略探测同名 / 同内容文档，返回重复摘要供上传前决策。
     *
     * @param request 预检请求（知识库ID必填，文件名与内容哈希至少提供一个）
     * @return 命中的重复文档摘要列表；未启用检测或无命中时为空列表
     */
    @PostMapping("/precheck")
    public ApiResponse<List<DuplicateDocumentResponse>> precheck(@Valid @RequestBody PrecheckUploadRequest request) {
        List<DedupHit> hits = applicationService.precheck(request.kbId(), request.fileName(), request.contentHash());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toDuplicateResponseList(hits));
    }

    /**
     * 删除文档（受理即返回：状态置「删除中」，清退由删除清退虚拟线程异步推进）。
     * <p>契约 BREAKING：由同步清退改为异步受理。
     * 受理为请求线程内的短事务 CAS（五源态 → DELETING），清退（掐灭等待 → Storage 资产回收 →
     * DB 分批物理清退 → 条件 DELETE 收口）全程异步，本接口不等待任何清退步骤。
     * 响应回显受理后的文档快照（{@code status = DELETING}），在飞重复删除按幂等同样回「删除中」；
     * 清退失败后该文档以 {@code DELETE_FAILED} + {@code errorMessage} 出现在详情 / 列表，由用户重删续跑。</p>
     *
     * @param id 文档ID
     * @return 受理后的文档快照（状态为删除中），响应文案「删除中」
     */
    @DeleteMapping("/{id}")
    public ApiResponse<DocumentResponse> delete(@PathVariable Long id) {
        Document accepted = applicationService.delete(new DeleteDocumentCommand(id));
        return new ApiResponse<>(true, ACCEPTED_RESPONSE_CODE, DELETING_RESPONSE_MESSAGE,
                KnowledgeBaseResponseConvert.INSTANCE.toResponse(accepted));
    }

    /**
     * 分页查询指定知识库下的文档列表。
     *
     * @param kbId     知识库ID
     * @param fileName 文件名关键字，可为空
     * @param status   文档处理状态，可为空
     * @param page     页码，从 1 开始，为空或非法时取 1
     * @param size     每页条数，为空或非法时取 10，最大 100
     * @return 分页的文档列表
     */
    @GetMapping
    public ApiResponse<PaginatedResponse<DocumentResponse>> list(
            @RequestParam Long kbId,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<Document> documents = applicationService.list(
                new ListDocumentQuery(kbId, fileName, status, safePage, safeSize));
        long total = applicationService.count(kbId, fileName, status);
        return ApiResponse.success(new PaginatedResponse<>(
                KnowledgeBaseResponseConvert.INSTANCE.toDocumentResponseList(documents),
                total, safePage, safeSize));
    }

    /**
     * 重新解析文档（刷新分块策略快照并重置状态，实际解析由 RAG 上下文的异步摄入任务接管）。
     * <p><b>显式语义（BREAKING）</b>：解析成功后的整篇替换将清除该文档的<b>全部</b>分块
     * （<b>含人工新增的分块</b>）并按解析结果重建，重建分块序号由服务端按解析顺序连续分配；
     * 人工维护的切片内容不会保留，需用户在重新解析后重新添加。前端 MUST 在执行前向用户
     * 二次确认该清除语义。</p>
     *
     * @param id      文档ID
     * @param request 重新解析请求体，可为空；携带分块策略覆盖时按覆盖值重建，否则沿用知识库当前分块配置
     * @return 文档详情
     */
    @PostMapping("/{id}/reparse")
    public ApiResponse<DocumentResponse> reparse(@PathVariable Long id,
                                                 @RequestBody(required = false) ReparseDocumentRequest request) {
        String chunkStrategyOverride = ObjectUtils.isEmpty(request) ? null : request.chunkStrategyOverride();
        Document document = applicationService.reparse(new ReparseDocumentCommand(id, chunkStrategyOverride));
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(document));
    }

    /**
     * 查询文档详情。
     *
     * @param id 文档ID
     * @return 文档详情
     */
    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.get(id)));
    }

    /**
     * 在线预览文档原文（流式回传，Content-Disposition 为 inline）。
     * <p>所属知识库不可用时拒绝并回传业务错误，不回传任何文件内容；
     * 文档不存在回传 404；内容类型以存储侧元数据为准，解析失败兜底为二进制流。</p>
     *
     * @param id 文档ID
     * @return 文档原文内容流
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> previewContent(@PathVariable Long id) {
        return buildContentResponse(applicationService.openContent(id), false);
    }

    /**
     * 下载文档原文（流式回传，Content-Disposition 为 attachment）。
     * <p>前置校验与错误语义同在线预览端点。</p>
     *
     * @param id 文档ID
     * @return 文档原文内容流（附件下载）
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadContent(@PathVariable Long id) {
        return buildContentResponse(applicationService.openContent(id), true);
    }

    /**
     * 组装文件回传响应：携带 Content-Disposition（inline / attachment，UTF-8 文件名）、
     * Content-Type 与 Content-Length 响应头，响应体为原文内容流。
     *
     * @param result     应用层内容结果
     * @param attachment 是否作为附件下载（true 为 attachment，false 为 inline 预览）
     * @return 流式文件响应
     */
    private ResponseEntity<InputStreamResource> buildContentResponse(DocumentContentResult result, boolean attachment) {
        ContentDisposition.Builder dispositionBuilder = attachment
                ? ContentDisposition.attachment() : ContentDisposition.inline();
        ContentDisposition disposition = dispositionBuilder
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, result.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(result.contentLength()))
                .body(new InputStreamResource(result.content()));
    }

    /**
     * 页码兜底：为空或小于 1 时取默认页码。
     */
    private int safePage(Integer page) {
        return page == null || page < 1 ? DEFAULT_PAGE : page;
    }

    /**
     * 每页条数兜底：为空或小于 1 时取默认值，超过上限时按上限截断。
     */
    private int safeSize(Integer size) {
        return size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }
}
