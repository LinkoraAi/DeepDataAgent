package com.linkroa.deepdataagent.agent.controller;

import com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.ModelConfigResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelTemplateResponse;
import com.linkroa.deepdataagent.agent.controller.response.TestConnectionResult;
import com.linkroa.deepdataagent.agent.application.service.ModelConfigApplicationService;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * LLM 模型配置 REST 控制器
 */
@RestController
@RequestMapping("/api/llm-models")
public class ModelConfigController {

    private final ModelConfigApplicationService modelConfigService;

    public ModelConfigController(ModelConfigApplicationService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    /**
     * 获取预置模板列表
     */
    @GetMapping("/templates")
    public ApiResponse<List<ModelTemplateResponse>> listTemplates() {
        List<ModelTemplateResponse> templates = modelConfigService.listTemplates().stream()
                .map(ModelTemplateResponse::from)
                .toList();
        return ApiResponse.success(templates);
    }

    /**
     * 获取我的模型配置列表
     */
    @GetMapping("/configs")
    public ApiResponse<List<ModelConfigResponse>> listConfigs() {
        List<ModelConfigResponse> configs = modelConfigService.listConfigs().stream()
                .map(ModelConfigResponse::from)
                .toList();
        return ApiResponse.success(configs);
    }

    /**
     * 添加模型配置
     */
    @PostMapping("/configs")
    public ApiResponse<String> addConfig(@Valid @RequestBody AddModelConfigRequest request) {
        modelConfigService.addConfig(request);
        return ApiResponse.success("添加模型配置成功");
    }

    /**
     * 编辑模型配置
     */
    @PutMapping("/configs/{id}")
    public ApiResponse<String> updateConfig(@PathVariable Long id,
                                            @RequestBody UpdateModelConfigRequest request) {
        modelConfigService.updateConfig(id, request);
        return ApiResponse.success("更新模型配置成功");
    }

    /**
     * 删除模型配置
     */
    @DeleteMapping("/configs/{id}")
    public ApiResponse<String> deleteConfig(@PathVariable Long id) {
        modelConfigService.deleteConfig(id);
        return ApiResponse.success("删除模型配置成功");
    }

    /**
     * 设置默认模型
     */
    @PostMapping("/configs/{id}/default")
    public ApiResponse<String> setDefault(@PathVariable Long id) {
        modelConfigService.setDefaultModel(id);
        return ApiResponse.success("设置默认模型成功");
    }

    /**
     * 获取默认模型
     */
    @GetMapping("/default")
    public ApiResponse<ModelConfigResponse> getDefault() {
        LlmModelConfigEntity config = modelConfigService.getDefaultModel();
        if (config == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(ModelConfigResponse.from(config));
    }

    /**
     * 测试连接
     */
    @PostMapping("/configs/{id}/test")
    public ApiResponse<TestConnectionResult> testConnection(@PathVariable Long id) {
        TestConnectionResult result = modelConfigService.testConnection(id);
        if (result.available()) {
            return ApiResponse.success(result);
        }
        return ApiResponse.error("500", result.message(), result);
    }
}
