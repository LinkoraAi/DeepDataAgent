package com.linkroa.deepdataagent.knowledgebase.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.application.command.CreateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.DeleteKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateEntityTypeConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateKnowledgeBaseCommand;
import com.linkroa.deepdataagent.knowledgebase.application.command.UpdateRetrievalConfigCommand;
import com.linkroa.deepdataagent.knowledgebase.application.query.ListKnowledgeBaseQuery;
import com.linkroa.deepdataagent.knowledgebase.application.service.KnowledgeBaseApplicationService;
import com.linkroa.deepdataagent.knowledgebase.controller.convert.KnowledgeBaseResponseConvert;
import com.linkroa.deepdataagent.knowledgebase.controller.request.CreateKnowledgeBaseRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateEntityTypeConfigRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateKnowledgeBaseRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.request.UpdateRetrievalConfigRequest;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseStatsResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.validation.Valid;
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
import java.util.Map;

/**
 * 知识库管理 REST 控制器（统一前缀 {@code /api/v1/knowledge-base/knowledge-bases}）。
 * <p>负责 HTTP 协议适配：请求参数校验与分页兜底、直接构建应用层命令、领域模型经
 * {@link KnowledgeBaseResponseConvert} 转换为响应 DTO，不包含任何业务规则。</p>
 */
@RestController
@RequestMapping(path = "/knowledge-base/knowledge-bases", version = ApiVersionConstants.CURRENT_API_VERSION)
public class KnowledgeBaseController {

    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 默认每页条数（知识库列表） */
    private static final int DEFAULT_SIZE = 10;

    /** 每页最大条数，防止一次拉取过多数据 */
    private static final int MAX_SIZE = 100;

    /** 删除受理响应码：受理成功、清退异步推进（202 Accepted 语义，HTTP 外层状态仍为 200） */
    private static final String ACCEPTED_RESPONSE_CODE = "202";

    /** 删除受理响应文案：知识库已进入删除中状态 */
    private static final String DELETING_RESPONSE_MESSAGE = "删除中，清退任务已受理";

    /** 知识库应用服务（本控制器唯一业务入口） */
    private final KnowledgeBaseApplicationService applicationService;

    /**
     * 构造知识库管理 REST 控制器。
     *
     * @param applicationService 知识库应用服务（本控制器唯一业务入口）
     */
    public KnowledgeBaseController(KnowledgeBaseApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 创建知识库。
     *
     * @param request 创建知识库请求
     * @return 新建后的知识库详情
     */
    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        CreateKnowledgeBaseCommand command = new CreateKnowledgeBaseCommand(
                request.name(), request.description(), request.language(), request.ragEngineConfig(),
                request.dedupPolicy(),
                request.retrievalStrategy(), request.embeddingConfig(), request.multiModelConfig(),
                request.entityTypeConfig());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    /**
     * 更新知识库基础信息与配置。
     *
     * @param id      知识库ID
     * @param request 更新知识库请求
     * @return 更新后的知识库详情
     */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        UpdateKnowledgeBaseCommand command = new UpdateKnowledgeBaseCommand(
                id, request.name(), request.description(), request.language(), request.ragEngineConfig(),
                request.dedupPolicy(),
                request.retrievalStrategy(), request.embeddingConfig(), request.multiModelConfig(),
                request.entityTypeConfig());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.update(command)));
    }

    /**
     * 删除知识库（受理即返回：状态置「删除中」，清退由检索增强侧异步线程推进）。
     * <p>受理为请求线程内的短事务 CAS，清退（资产回收 → DB 分批
     * 物理清退 → 图谱/缓存 → 条件 DELETE 收口）全程异步，本接口不等待任何清退步骤。
     * 响应回显受理后的知识库快照（{@code lifecycleStatus = DELETING}）；清退失败后该库以
     * {@code DELETE_FAILED} + {@code errorMessage} 出现在列表，由用户重删续跑。</p>
     *
     * @param id 知识库ID
     * @return 受理后的知识库快照（生命周期为删除中），响应文案「删除中」
     */
    @DeleteMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> delete(@PathVariable Long id) {
        KnowledgeBase accepted = applicationService.delete(new DeleteKnowledgeBaseCommand(id));
        return new ApiResponse<>(true, ACCEPTED_RESPONSE_CODE, DELETING_RESPONSE_MESSAGE,
                KnowledgeBaseResponseConvert.INSTANCE.toResponse(accepted));
    }

    /**
     * 分页查询知识库列表。
     *
     * @param keyword   名称/描述关键字，可为空
     * @param page      页码，从 1 开始，为空或非法时取 1
     * @param size      每页条数，为空或非法时取 10，最大 100
     * @param sortBy    排序字段（name/createdAt/updatedAt），为空时按创建时间倒序
     * @param sortOrder 排序方向（asc/desc），为空时按倒序
     * @return 分页的知识库列表
     */
    @GetMapping
    public ApiResponse<PaginatedResponse<KnowledgeBaseResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder
    ) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<KnowledgeBase> knowledgeBases =
                applicationService.list(new ListKnowledgeBaseQuery(keyword, safePage, safeSize, sortBy, sortOrder));
        long total = applicationService.count(keyword);
        return ApiResponse.success(new PaginatedResponse<>(
                KnowledgeBaseResponseConvert.INSTANCE.toKnowledgeBaseResponseList(knowledgeBases),
                total, safePage, safeSize));
    }

    /**
     * 查询知识库总览统计。
     *
     * @return 知识库、文档、切片总数
     */
    @GetMapping("/stats")
    public ApiResponse<KnowledgeBaseStatsResponse> stats() {
        Map<String, Long> stats = applicationService.stats();
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toStatsResponse(stats));
    }

    /**
     * 查询知识库详情。
     *
     * @param id 知识库ID
     * @return 知识库详情
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE.toResponse(applicationService.get(id)));
    }

    /**
     * 更新知识库检索策略配置。
     *
     * @param id      知识库ID
     * @param request 检索策略配置请求
     * @return 更新后的知识库详情
     */
    @PutMapping("/{id}/retrieval-config")
    public ApiResponse<KnowledgeBaseResponse> updateRetrievalConfig(
            @PathVariable Long id, @Valid @RequestBody UpdateRetrievalConfigRequest request) {
        UpdateRetrievalConfigCommand command = new UpdateRetrievalConfigCommand(id, request.retrievalStrategy());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE
                .toResponse(applicationService.updateRetrievalConfig(command)));
    }

    /**
     * 更新知识库实体类型配置。
     *
     * @param id      知识库ID
     * @param request 实体类型配置请求
     * @return 更新后的知识库详情
     */
    @PutMapping("/{id}/entity-type-config")
    public ApiResponse<KnowledgeBaseResponse> updateEntityTypeConfig(
            @PathVariable Long id, @Valid @RequestBody UpdateEntityTypeConfigRequest request) {
        UpdateEntityTypeConfigCommand command = new UpdateEntityTypeConfigCommand(id, request.entityTypeConfig());
        return ApiResponse.success(KnowledgeBaseResponseConvert.INSTANCE
                .toResponse(applicationService.updateEntityTypeConfig(command)));
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
