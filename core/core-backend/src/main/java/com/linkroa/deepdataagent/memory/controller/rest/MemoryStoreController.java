package com.linkroa.deepdataagent.memory.controller.rest;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.command.RedactMemoryVersionCommand;
import com.linkroa.deepdataagent.memory.application.command.UpdateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.convert.MemoryStoreCommandConvert;
import com.linkroa.deepdataagent.memory.application.query.ListMemoryStoreQuery;
import com.linkroa.deepdataagent.memory.application.service.MemoryStoreApplicationService;
import com.linkroa.deepdataagent.memory.controller.convert.MemoryResponseConvert;
import com.linkroa.deepdataagent.memory.controller.convert.MemoryStoreResponseConvert;
import com.linkroa.deepdataagent.memory.controller.convert.MemoryVersionResponseConvert;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.controller.request.UpdateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.controller.response.MemoryDetailResponse;
import com.linkroa.deepdataagent.memory.controller.response.MemoryResponse;
import com.linkroa.deepdataagent.memory.controller.response.MemoryStoreResponse;
import com.linkroa.deepdataagent.memory.controller.response.MemoryVersionResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆库 REST 控制器（统一前缀 {@code /cloud/memory-stores}）。
 * <p>端点契约对齐「Store → Memory(path) → MemoryVersion」三层原语：
 * Store 提供创建 / 列表 / 详情 / 归档 / 硬删；Memory 条目以 memoryId 定位，
 * 创建 / 列表（不含内容）/ 详情（含头版本内容）/ PATCH 更新（OCC 版本冲突 409）/ 删除；
 * MemoryVersion 提供版本历史列表、单版本详情与 redact（路径参数定位，无请求体，
 * 响应省略 content 字段）。</p>
 */
@RestController
@RequestMapping(path = "/cloud/memory-stores", version = ApiVersionConstants.CURRENT_API_VERSION)
public class MemoryStoreController {

    @Resource
    private MemoryStoreApplicationService applicationService;

    /**
     * 创建记忆库。
     */
    @PostMapping
    public ApiResponse<MemoryStoreResponse> create(@Valid @RequestBody CreateMemoryStoreRequest request) {
        CreateMemoryStoreCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(MemoryStoreResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    /**
     * 分页列出活跃记忆库（不含已归档）。
     */
    @GetMapping
    public ApiResponse<PaginatedResponse<MemoryStoreResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        ListMemoryStoreQuery query = new ListMemoryStoreQuery(safePage, safeSize);
        List<MemoryStore> stores = applicationService.list(query);
        long total = applicationService.count();
        return ApiResponse.success(new PaginatedResponse<>(
                stores.stream().map(MemoryStoreResponseConvert.INSTANCE::toResponse).toList(),
                total, safePage, safeSize));
    }

    /**
     * 记忆库详情（归档后可读）。
     */
    @GetMapping("/{storeId}")
    public ApiResponse<MemoryStoreResponse> detail(@PathVariable String storeId) {
        return ApiResponse.success(MemoryStoreResponseConvert.INSTANCE.toResponse(applicationService.get(storeId)));
    }

    /**
     * 归档记忆库（幂等：已归档返回现状；归档后禁写）。
     */
    @PostMapping("/{storeId}/archive")
    public ApiResponse<MemoryStoreResponse> archive(@PathVariable String storeId) {
        return ApiResponse.success(MemoryStoreResponseConvert.INSTANCE.toResponse(applicationService.archive(storeId)));
    }

    /**
     * 硬删记忆库（被未删 Session 引用 → 409；否则级联删除条目与版本）。
     */
    @DeleteMapping("/{storeId}")
    public ApiResponse<Void> delete(@PathVariable String storeId) {
        applicationService.delete(storeId);
        return ApiResponse.success(null);
    }

    /**
     * 创建记忆条目（path 库内唯一，重复 → 409；内容上限 100KB）。
     */
    @PostMapping("/{storeId}/memories")
    public ApiResponse<MemoryDetailResponse> createEntry(@PathVariable String storeId,
                                                         @Valid @RequestBody CreateMemoryEntryRequest request) {
        CreateMemoryEntryCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateEntryCommand(storeId, request);
        return ApiResponse.success(MemoryResponseConvert.INSTANCE.toDetailResponse(
                applicationService.createEntry(command)));
    }

    /**
     * 列出记忆库下全部条目（仅元数据，不含 content）。
     */
    @GetMapping("/{storeId}/memories")
    public ApiResponse<List<MemoryResponse>> listEntries(@PathVariable String storeId) {
        return ApiResponse.success(applicationService.listEntries(storeId).stream()
                .map(MemoryResponseConvert.INSTANCE::toResponse)
                .toList());
    }

    /**
     * 单条记忆详情（条目元数据 + 头版本内容）。
     */
    @GetMapping("/{storeId}/memories/{memoryId}")
    public ApiResponse<MemoryDetailResponse> getEntry(@PathVariable String storeId,
                                                      @PathVariable String memoryId) {
        return ApiResponse.success(MemoryResponseConvert.INSTANCE.toDetailResponse(
                applicationService.getEntry(storeId, memoryId)));
    }

    /**
     * 更新记忆内容（OCC：请求 version 与当前不符 → 409；path 不可变）。
     */
    @PatchMapping("/{storeId}/memories/{memoryId}")
    public ApiResponse<MemoryDetailResponse> updateEntry(@PathVariable String storeId,
                                                         @PathVariable String memoryId,
                                                         @Valid @RequestBody UpdateMemoryEntryRequest request) {
        UpdateMemoryEntryCommand command =
                MemoryStoreCommandConvert.INSTANCE.toUpdateEntryCommand(storeId, memoryId, request);
        return ApiResponse.success(MemoryResponseConvert.INSTANCE.toDetailResponse(
                applicationService.updateEntry(command)));
    }

    /**
     * 删除记忆条目（物理删行 + tombstone 版本）。
     */
    @DeleteMapping("/{storeId}/memories/{memoryId}")
    public ApiResponse<Void> deleteEntry(@PathVariable String storeId,
                                         @PathVariable String memoryId) {
        applicationService.deleteEntry(storeId, memoryId);
        return ApiResponse.success(null);
    }

    /**
     * 列出某条记忆的全部版本（时间正序）。
     */
    @GetMapping("/{storeId}/memories/{memoryId}/versions")
    public ApiResponse<List<MemoryVersionResponse>> listVersions(@PathVariable String storeId,
                                                                 @PathVariable String memoryId) {
        return ApiResponse.success(applicationService.listVersions(storeId, memoryId).stream()
                .map(MemoryVersionResponseConvert.INSTANCE::toResponse)
                .toList());
    }

    /**
     * 单版本详情（已脱敏时省略 content 字段）。
     */
    @GetMapping("/{storeId}/memory-versions/{versionId}")
    public ApiResponse<MemoryVersionResponse> getVersion(@PathVariable String storeId,
                                                         @PathVariable String versionId) {
        return ApiResponse.success(MemoryVersionResponseConvert.INSTANCE.toResponse(
                applicationService.getVersion(storeId, versionId)));
    }

    /**
     * 脱敏指定版本（清空内容快照与哈希，幂等；响应 MUST NOT 含 content）。
     */
    @PostMapping("/{storeId}/memory-versions/{versionId}/redact")
    public ApiResponse<MemoryVersionResponse> redactVersion(@PathVariable String storeId,
                                                            @PathVariable String versionId) {
        return ApiResponse.success(MemoryVersionResponseConvert.INSTANCE.toResponse(
                applicationService.redactVersion(new RedactMemoryVersionCommand(storeId, versionId))));
    }
}
