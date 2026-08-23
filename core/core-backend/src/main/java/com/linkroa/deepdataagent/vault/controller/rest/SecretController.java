package com.linkroa.deepdataagent.vault.controller.rest;

import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import com.linkroa.deepdataagent.vault.application.convert.SecretCommandConvert;
import com.linkroa.deepdataagent.vault.application.command.CreateSecretCommand;
import com.linkroa.deepdataagent.vault.application.query.ListSecretQuery;
import com.linkroa.deepdataagent.vault.application.service.SecretApplicationService;
import com.linkroa.deepdataagent.vault.controller.convert.SecretResponseConvert;
import com.linkroa.deepdataagent.vault.controller.request.CreateSecretRequest;
import com.linkroa.deepdataagent.vault.controller.response.SecretResponse;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
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
 * 密钥管理 REST 控制器（统一前缀 {@code /api/v1/vault/secrets}）。
 */
@RestController
@RequestMapping(path = "/vault/secrets", version = ApiVersionConstants.CURRENT_API_VERSION)
public class SecretController {

    @Resource
    private SecretApplicationService applicationService;

    @PostMapping
    public ApiResponse<SecretResponse> create(@Valid @RequestBody CreateSecretRequest request) {
        CreateSecretCommand command = SecretCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(SecretResponseConvert.INSTANCE.toResponse(applicationService.create(command)));
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<SecretResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 20 : Math.min(size, 100);
        ListSecretQuery query = new ListSecretQuery(safePage, safeSize);
        List<Secret> secrets = applicationService.list(query);
        long total = applicationService.count();
        return ApiResponse.success(new PaginatedResponse<>(
                secrets.stream().map(SecretResponseConvert.INSTANCE::toResponse).toList(),
                total, safePage, safeSize));
    }

    @GetMapping("/{secretId}")
    public ApiResponse<SecretResponse> detail(@PathVariable String secretId) {
        return ApiResponse.success(SecretResponseConvert.INSTANCE.toResponse(applicationService.get(secretId)));
    }

    @DeleteMapping("/{secretId}")
    public ApiResponse<Void> delete(@PathVariable String secretId) {
        applicationService.delete(secretId);
        return ApiResponse.success(null);
    }
}