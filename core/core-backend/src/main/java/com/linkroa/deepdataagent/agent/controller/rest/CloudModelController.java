package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.service.ModelCatalogService;
import com.linkroa.deepdataagent.agent.controller.response.ModelCatalogResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型目录只读控制器（版本化前缀 {@code /api/v1/cloud}，契约端点 {@code GET /cloud/models}）。
 * <p>响应仅含目录展示字段（id / displayName / efforts / isVl / isDefault / 窗口档位），
 * 不暴露内部供应商映射（api_format / base_url / credential）。</p>
 */
@RestController
@RequestMapping(path = "/cloud", version = ApiVersionConstants.CURRENT_API_VERSION)
public class CloudModelController {

    @Resource
    private ModelCatalogService modelCatalogService;

    /** 查询模型目录清单。 */
    @GetMapping("/models")
    public ApiResponse<List<ModelCatalogResponse>> listModels() {
        List<ModelCatalogResponse> responses = modelCatalogService.listModels().stream()
                .map(ModelCatalogResponse::of)
                .toList();
        return ApiResponse.success(responses);
    }
}
