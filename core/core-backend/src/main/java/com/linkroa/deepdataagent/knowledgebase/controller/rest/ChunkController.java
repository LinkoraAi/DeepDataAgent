package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.CreateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteChunksCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateChunkCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListChunkQuery;
import com.linkroa.deepdataagent.knowledgebase.application.result.ChunkMediaResult;
import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.convert.KnowledgeBaseResponseConvert;
import com.linkroa.deepdataagent.knowledgebase.controller.request.CreateChunkRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.DeleteChunksRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateChunkRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.ChunkResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库切片管理 REST 控制器（统一前缀 {@code /api/v1/knowledge-base/chunks}）。
 */
@RestController
@RequestMapping(path = "/knowledge-base/chunks", version = ApiVersionConstants.CURRENT_API_VERSION)
public class ChunkController {

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数（切片列表，单页数据量小于文档列表） */
    private static final int DEFAULT_SIZE = 20;

    /** 每页最大条数，防止一次拉取过多数据 */
    private static final int MAX_SIZE = 100;

    /** 切片应用服务（本控制器唯一业务入口） */
    private final ChunkApplicationService applicationService;

    /**
     * 构造知识库切片管理 REST 控制器。
     *
     * @param applicationService 切片应用服务（本控制器唯一业务入口）
     */
    public ChunkController(ChunkApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 新增切片。
     *
     * @param request 新增切片请求
     * @return 新建后的切片详情
     */
    @PostMapping
    public ApiResponse<ChunkResponse> create(@Valid @RequestBody CreateChunkRequest request) {
        CreateChunkCommand command = new CreateChunkCommand(
                request.kbId(), request.documentId(), request.sequence(), request.tokens(),
                request.chunkContent(), request.originalItem(), request.chunkContentType(), request.sourceFileName());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    /**
     * 更新切片内容。
     *
     * @param id      切片ID
     * @param request 更新切片请求
     * @return 更新后的切片详情
     */
    @PutMapping("/{id}")
    public ApiResponse<ChunkResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateChunkRequest request) {
        UpdateChunkCommand command = new UpdateChunkCommand(id, request.chunkContent(), request.tokens());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.update(command)));
    }

    /**
     * 删除切片（单条，「Storage 先删、DB 后删」时序：图片对象事务前回收、切片派生数据事务内物理清退）。
     *
     * @param id 切片ID
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        applicationService.delete(new DeleteChunkCommand(id));
        return ApiResponse.success(null);
    }

    /**
     * 批量删除切片（服务端去重后按批复用单条删除的闸门 + 对象回收 + 原语事务时序）。
     *
     * @param request 批量删除请求（切片ID集合非空）
     * @return 空响应体
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@Valid @RequestBody DeleteChunksRequest request) {
        applicationService.deleteBatch(new DeleteChunksCommand(request.ids()));
        return ApiResponse.success(null);
    }

    /**
     * 分页查询切片列表。
     *
     * @param kbId       知识库ID
     * @param documentId 文档ID，可为空
     * @param sequence   切片序号，可为空
     * @param keyword    切片内容关键字，可为空
     * @param page       页码，从 1 开始，为空或非法时取 1
     * @param size       每页条数，为空或非法时取 20，最大 100
     * @return 分页的切片列表
     */
    @GetMapping
    public ApiResponse<PaginatedResponse<ChunkResponse>> list(
            @RequestParam Long kbId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) Integer sequence,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<Chunk> chunks = applicationService.list(
                new ListChunkQuery(kbId, documentId, sequence, keyword, safePage, safeSize));
        long total = applicationService.count(kbId, documentId, sequence, keyword);
        return ApiResponse.success(new PaginatedResponse<>(
                KnowledgeBaseResponseConvert.INSTANCE.toChunkResponseList(chunks),
                total, safePage, safeSize));
    }

    /**
     * 查询切片详情。
     *
     * @param id 切片ID
     * @return 切片详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ChunkResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.get(id)));
    }

    /**
     * 在线预览切片媒体图片（inline 流式代理，替代已退役的通用对象存储预览端点）。
     * <p>按切片登记的媒体引用回传原图，不外泄对象存储端点与桶凭据；
     * 切片不存在 / 无媒体引用 / 媒体对象缺失回传 404；所属知识库不可用回传业务错误。</p>
     *
     * @param id 切片ID
     * @return 媒体图片内容流
     */
    @GetMapping("/{id}/media")
    public ResponseEntity<InputStreamResource> media(@PathVariable Long id) {
        ChunkMediaResult result = applicationService.openMedia(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
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
