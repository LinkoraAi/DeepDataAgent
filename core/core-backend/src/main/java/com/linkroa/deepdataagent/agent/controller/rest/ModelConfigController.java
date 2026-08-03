package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.AddModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelInfoDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelProviderDTO;
import com.linkroa.deepdataagent.agent.application.service.ModelConfigApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.ModelConfigResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelInfoResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelProviderResponse;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型配置 REST 控制器
 * <p>提供模型配置的 CRUD 操作、服务商查询、模型列表查询等接口，
 * 路径和返回格式与前端完全对齐。</p>
 * <p>控制器负责将请求对象转换为命令对象、将应用层 DTO 转换为响应对象。</p>
 */
@RestController
@RequestMapping("/api/model")
public class ModelConfigController {

    private final ModelConfigApplicationService service;

    public ModelConfigController(ModelConfigApplicationService service) {
        this.service = service;
    }

    // ==================== 服务商接口 ====================

    /**
     * 获取所有启用的服务商列表
     */
    @GetMapping("/providers")
    public ApiResponse<List<ModelProviderResponse>> listProviders() {
        List<ModelProviderDTO> providers = service.listProviders();
        List<ModelProviderResponse> list = providers.stream()
                .map(p -> new ModelProviderResponse(
                        p.id(), p.providerDisplayName(), p.providerName(), p.apiUrl()))
                .toList();
        return ApiResponse.success(list);
    }

    /**
     * 根据服务商标识获取模型列表
     *
     * @param providerKey 服务商标识
     */
    @GetMapping("/providers/{providerKey}/models")
    public ApiResponse<List<ModelInfoResponse>> listModelsByProvider(@PathVariable String providerKey) {
        List<ModelInfoDTO> models = service.getModelsByProvider(providerKey);
        List<ModelInfoResponse> list = models.stream()
                .map(m -> new ModelInfoResponse(m.id(), m.modelKey(), m.displayName()))
                .toList();
        return ApiResponse.success(list);
    }

    // ==================== 模型配置 CRUD 接口 ====================

    /**
     * 获取所有模型配置列表
     */
    @GetMapping("/configs")
    public ApiResponse<List<ModelConfigResponse>> listConfigs() {
        List<ModelConfigDTO> dtos = service.listConfigDTOs();
        return ApiResponse.success(dtos.stream().map(this::toResponse).toList());
    }

    /**
     * 获取模型配置详情
     *
     * @param id 配置 ID
     */
    @GetMapping("/configs/{id}")
    public ApiResponse<ModelConfigResponse> getConfigById(@PathVariable Long id) {
        return ApiResponse.success(toResponse(service.getConfigDTO(id)));
    }

    /**
     * 获取默认模型配置
     */
    @GetMapping("/configs/default")
    public ApiResponse<ModelConfigResponse> getDefaultConfig() {
        return ApiResponse.success(toResponse(service.getDefaultConfigDTO()));
    }

    /**
     * 获取模型配置详情（编辑用，返回解密后的 API Key）
     *
     * @param id 配置 ID
     */
    @GetMapping("/configs/{id}/edit")
    public ApiResponse<ModelConfigResponse> getConfigForEdit(@PathVariable Long id) {
        return ApiResponse.success(toResponse(service.getConfigForEditDTO(id)));
    }

    /**
     * 添加模型配置
     *
     * @param request 添加请求
     */
    @PostMapping("/configs")
    public ApiResponse<Void> addConfig(@Valid @RequestBody AddModelConfigRequest request) {
        service.addConfig(toAddCommand(request));
        return ApiResponse.success(null);
    }

    /**
     * 更新模型配置
     *
     * @param id      配置 ID
     * @param request 更新请求
     */
    @PutMapping("/configs/{id}")
    public ApiResponse<Void> updateConfig(@PathVariable Long id,
                                          @RequestBody UpdateModelConfigRequest request) {
        service.updateConfig(id, toUpdateCommand(request));
        return ApiResponse.success(null);
    }

    /**
     * 删除模型配置（软删除）
     *
     * @param id 配置 ID
     */
    @DeleteMapping("/configs/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) {
        service.deleteConfig(id);
        return ApiResponse.success(null);
    }

    /**
     * 设置默认模型
     *
     * @param id 配置 ID
     */
    @PutMapping("/configs/{id}/default")
    public ApiResponse<Void> setDefaultModel(@PathVariable Long id) {
        service.setDefaultModel(id);
        return ApiResponse.success(null);
    }

    /**
     * 测试连接
     *
     * @param id 配置 ID
     */
    @PostMapping("/configs/{id}/test")
    public ApiResponse<TestConnectionResult> testConnection(@PathVariable Long id) {
        TestConnectionResult result = service.testConnection(id);
        return ApiResponse.success(result);
    }

    /**
     * 将添加请求转换为命令对象
     */
    private AddModelConfigCommand toAddCommand(AddModelConfigRequest request) {
        return new AddModelConfigCommand(
                request.providerKey(), request.modelKey(), request.baseUrl(),
                request.apiFormat(), request.apiKey(), request.setDefault());
    }

    /**
     * 将更新请求转换为命令对象
     */
    private UpdateModelConfigCommand toUpdateCommand(UpdateModelConfigRequest request) {
        return new UpdateModelConfigCommand(request.apiKey(), request.baseUrl());
    }

    /**
     * 将模型配置 DTO 转换为响应对象
     */
    private ModelConfigResponse toResponse(ModelConfigDTO dto) {
        if (dto == null) {
            return null;
        }
        return new ModelConfigResponse(
                dto.id(), dto.providerKey(), dto.providerName(), dto.modelKey(),
                dto.baseUrl(), dto.apiKeyMasked(), dto.apiFormat(),
                dto.isDefault(), dto.createdAt(), dto.updatedAt());
    }
}