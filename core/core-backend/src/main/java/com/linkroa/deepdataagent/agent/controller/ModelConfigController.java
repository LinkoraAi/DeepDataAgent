package com.linkroa.deepdataagent.agent.controller;

import com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.DeleteModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.GetModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListModelConfigsRequest;
import com.linkroa.deepdataagent.agent.controller.request.ListModelTemplatesRequest;
import com.linkroa.deepdataagent.agent.controller.request.SetDefaultModelRequest;
import com.linkroa.deepdataagent.agent.controller.request.TestConnectionRequest;
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
 * <p>所有接口统一使用 POST + body 传参方式。</p>
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
    @PostMapping("/templates/list")
    public ApiResponse<List<ModelTemplateResponse>> listTemplates(@RequestBody(required = false) ListModelTemplatesRequest request) {
        List<ModelTemplateResponse> templates = modelConfigService.listTemplates().stream()
                .map(ModelTemplateResponse::from)
                .toList();
        return ApiResponse.success(templates);
    }

    /**
     * 获取我的模型配置列表
     */
    @PostMapping("/configs/list")
    public ApiResponse<List<ModelConfigResponse>> listConfigs(@RequestBody(required = false) ListModelConfigsRequest request) {
        List<ModelConfigResponse> configs = modelConfigService.listConfigs().stream()
                .map(ModelConfigResponse::from)
                .toList();
        return ApiResponse.success(configs);
    }

    /**
     * 添加模型配置
     */
    @PostMapping("/configs/add")
    public ApiResponse<String> addConfig(@Valid @RequestBody AddModelConfigRequest request) {
        modelConfigService.addConfig(request);
        return ApiResponse.success("添加模型配置成功");
    }

    /**
     * 编辑模型配置
     */
    @PostMapping("/configs/update")
    public ApiResponse<String> updateConfig(@Valid @RequestBody UpdateModelConfigRequest request) {
        modelConfigService.updateConfig(request.id(), request);
        return ApiResponse.success("更新模型配置成功");
    }

    /**
     * 删除模型配置
     */
    @PostMapping("/configs/delete")
    public ApiResponse<String> deleteConfig(@Valid @RequestBody DeleteModelConfigRequest request) {
        modelConfigService.deleteConfig(request.id());
        return ApiResponse.success("删除模型配置成功");
    }

    /**
     * 设置默认模型
     */
    @PostMapping("/configs/set-default")
    public ApiResponse<String> setDefault(@Valid @RequestBody SetDefaultModelRequest request) {
        modelConfigService.setDefaultModel(request.id());
        return ApiResponse.success("设置默认模型成功");
    }

    /**
     * 获取默认模型
     */
    @PostMapping("/default/get")
    public ApiResponse<ModelConfigResponse> getDefault(@RequestBody(required = false) Object request) {
        LlmModelConfigEntity config = modelConfigService.getDefaultModel();
        if (config == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(ModelConfigResponse.from(config));
    }

    /**
     * 测试连接
     */
    @PostMapping("/configs/test")
    public ApiResponse<TestConnectionResult> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        TestConnectionResult result = modelConfigService.testConnection(request.id());
        if (result.available()) {
            return ApiResponse.success(result);
        }
        return ApiResponse.error("500", result.message(), result);
    }
}
