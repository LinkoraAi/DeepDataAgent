package com.linkroa.deepdataagent.memory.controller.rest;

import com.linkroa.deepdataagent.memory.application.convert.MemoryStoreCommandConvert;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.query.ListMemoryStoreQuery;
import com.linkroa.deepdataagent.memory.application.service.MemoryStoreApplicationService;
import com.linkroa.deepdataagent.memory.controller.convert.MemoryStoreResponseConvert;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.controller.response.MemoryStoreResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆库管理 REST 控制器（统一前缀 {@code /api/v1/memory/memory-stores}）。
 */
@RestController
@RequestMapping(path = "/memory/memory-stores", version = ApiVersionConstants.CURRENT_API_VERSION)
public class MemoryStoreController {

    @Resource
    private MemoryStoreApplicationService applicationService;

    @PostMapping
    public ApiResponse<MemoryStoreResponse> create(@Valid @RequestBody CreateMemoryStoreRequest request) {
        CreateMemoryStoreCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(MemoryStoreResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

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

    @GetMapping("/{memoryId}")
    public ApiResponse<MemoryStoreResponse> detail(@PathVariable String memoryId) {
        return ApiResponse.success(MemoryStoreResponseConvert.INSTANCE.toResponse(applicationService.get(memoryId)));
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<Void> delete(@PathVariable String memoryId) {
        applicationService.delete(memoryId);
        return ApiResponse.success(null);
    }
}