package com.linkroa.deepdataagent.vault.controller.rest;

import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.command.AddVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.command.CreateVaultCommand;
import com.linkroa.deepdataagent.vault.application.command.UpdateVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.convert.VaultCommandConvert;
import com.linkroa.deepdataagent.vault.application.dto.VaultCredentialViewDTO;
import com.linkroa.deepdataagent.vault.application.query.ListVaultCredentialQuery;
import com.linkroa.deepdataagent.vault.application.query.ListVaultQuery;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService;
import com.linkroa.deepdataagent.vault.controller.convert.VaultCredentialResponseConvert;
import com.linkroa.deepdataagent.vault.controller.convert.VaultCredentialValidationResponseConvert;
import com.linkroa.deepdataagent.vault.controller.convert.VaultResponseConvert;
import com.linkroa.deepdataagent.vault.controller.request.AddVaultCredentialRequest;
import com.linkroa.deepdataagent.vault.controller.request.CreateVaultRequest;
import com.linkroa.deepdataagent.vault.controller.request.SearchVaultsRequest;
import com.linkroa.deepdataagent.vault.controller.request.UpdateVaultCredentialRequest;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialResponse;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialValidationResponse;
import com.linkroa.deepdataagent.vault.controller.response.VaultDeletedResponse;
import com.linkroa.deepdataagent.vault.controller.response.VaultResponse;
import com.linkroa.deepdataagent.vault.controller.response.VaultSearchResponse;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
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

/**
 * 保管库 REST 控制器（统一前缀 {@code /cloud/vaults}）。
 * <p>端点契约对齐「Vault → VaultCredential」两层模型语义：创建 / 列表 / 搜索 / 详情 / 归档 /
 * 硬删管保管库；credentials 子资源承载添加 / 列表 / 详情 / 更新 / 删除 / 校验
 * （validate 仅适用于 active 的 {@code mcp_oauth} 凭证：先刷新后探测，见 design D4）。
 * 所有凭证响应仅含脱敏元数据，明文 / 密文绝不下发。</p>
 *
 * <p><b>版本承载（design D9）</b>：本控制器以基线版本 {@code "1+"} 声明。项目尚未上线，
 * 只交付 v1 接口、不并行维护第二套契约形状；基线声明的固有语义是「本版本及所有更高版本可达」，
 * 故未单独声明版本号时 {@code /api/v2/cloud/vaults} 亦落到本处理器——这是 Spring 基线匹配的结果，
 * 而非并行维护的 v2 契约。后续若要为更高版本冻结独立形状，另声明 {@code version = "2"} 的
 * 控制器会按「高版本上浮」自动胜出。</p>
 *
 * <p><b>动词约定</b>：更新凭证用 {@code POST}（Agent 更新维持 {@code PATCH}）；
 * 搜索用独立端点且只接受 JSON Body（见 design D13）。</p>
 */
@RestController
@RequestMapping(path = "/cloud/vaults", version = ApiVersionConstants.BASELINE_API_VERSION)
public class VaultController {

    @Resource
    private VaultApplicationService applicationService;

    /**
     * 创建保管库：生成 {@code vault_} 前缀业务 ID 并归属当前认证用户。
     *
     * @param request 创建请求（name 必填）
     * @return 新建保管库详情
     */
    @PostMapping
    public ApiResponse<VaultResponse> create(@Valid @RequestBody CreateVaultRequest request) {
        CreateVaultCommand command = VaultCommandConvert.INSTANCE.toCreateCommand(request);
        return ApiResponse.success(VaultResponseConvert.INSTANCE.toCreatedResponse(applicationService.create(command)));
    }

    /**
     * 游标分页列出当前用户的保管库（创建时间降序，支持名称模糊与归档态过滤；
     * 游标锚点未知 / 越权返回 404）。
     *
     * @param name 名称模糊过滤（可选）
     * @param includeArchived 是否包含已归档（可选）
     * @param limit 单页条数（可选）
     * @param afterId 向后翻页游标（可选）
     * @param beforeId 向前翻页游标（可选）
     * @param page 页码翻页参数（可选）
     * @return 保管库游标分页
     */
    @GetMapping
    public ApiResponse<CursorPage<VaultResponse>> list(
            @RequestParam(required = false) String name,
            @RequestParam(name = "include_archived", required = false) String includeArchived,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId,
            @RequestParam(required = false) String page
    ) {
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toListQuery(
                AuthContext.requireUserId(), name, includeArchived, limit, afterId, beforeId, page);
        CursorPage<Vault> result = applicationService.list(query);
        return ApiResponse.success(result.map(VaultResponseConvert.INSTANCE::toResponse));
    }

    /**
     * 搜索保管库（独立端点，design D13）：筛选条件<b>只从 JSON Body 取</b>，
     * 同请求的 URL Query 参数一律忽略；{@code total} 仅首页返回。
     */
    @PostMapping("/search")
    public ApiResponse<VaultSearchResponse> search(@Valid @RequestBody SearchVaultsRequest request) {
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toSearchQuery(AuthContext.requireUserId(), request);
        VaultApplicationService.VaultSearchResult result = applicationService.search(query);
        return ApiResponse.success(
                VaultResponseConvert.INSTANCE.toSearchResponse(result.page(), result.total()));
    }

    /**
     * 读取保管库详情（owner 隔离 + 已归档过滤：非 owner / 已归档 → 404）。
     *
     * @param vaultId 保管库 ID
     * @return 保管库详情
     */
    @GetMapping("/{vaultId}")
    public ApiResponse<VaultResponse> detail(@PathVariable String vaultId) {
        return ApiResponse.success(VaultResponseConvert.INSTANCE.toResponse(applicationService.get(vaultId)));
    }

    /**
     * 归档保管库（软删）：置归档时间，此后列表 / 详情不再返回。
     *
     * @param vaultId 保管库 ID
     * @return 空响应
     */
    @PostMapping("/{vaultId}/archive")
    public ApiResponse<Void> archive(@PathVariable String vaultId) {
        applicationService.archive(vaultId);
        return ApiResponse.success(null);
    }

    /**
     * 删除保管库：仍有历史 Session 引用时拒绝（409）；同一事务内先级联逻辑删其下全部凭证。
     *
     * @param vaultId 保管库 ID
     * @return 被删保管库标识
     */
    @DeleteMapping("/{vaultId}")
    public ApiResponse<VaultDeletedResponse> delete(@PathVariable String vaultId) {
        applicationService.delete(vaultId);
        return ApiResponse.success(VaultDeletedResponse.vault(vaultId));
    }

    /**
     * 添加凭证：明文经 AES-GCM 加密为 {@code ciphertext} 落库，响应不含明文；
     * 同一 MCP server 已有活跃凭证或活跃数达上限（20）返回 409。
     *
     * @param vaultId 保管库 ID
     * @param request 添加请求（含 MCP server URL 与机密材料）
     * @return 脱敏凭证元数据
     */
    @PostMapping("/{vaultId}/credentials")
    public ApiResponse<VaultCredentialResponse> addCredential(@PathVariable String vaultId,
                                                              @Valid @RequestBody AddVaultCredentialRequest request) {
        AddVaultCredentialCommand command = VaultCommandConvert.INSTANCE.toAddCredentialCommand(vaultId, request);
        return ApiResponse.success(toResponse(applicationService.addCredential(command)));
    }

    /**
     * 游标分页列出保管库下的凭证（仅元数据，创建时间降序；游标锚点须属本保管库，未知 / 越权 → 404）。
     *
     * @param vaultId 保管库 ID
     * @param name 按 MCP 服务器 URL 模糊过滤（可选）
     * @param includeArchived 是否包含已归档（可选）
     * @param limit 单页条数（可选）
     * @param afterId 向后翻页游标（可选）
     * @param beforeId 向前翻页游标（可选）
     * @param page 页码翻页参数（可选）
     * @return 凭证脱敏元数据游标分页
     */
    @GetMapping("/{vaultId}/credentials")
    public ApiResponse<CursorPage<VaultCredentialResponse>> listCredentials(
            @PathVariable String vaultId,
            @RequestParam(required = false) String name,
            @RequestParam(name = "include_archived", required = false) String includeArchived,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId,
            @RequestParam(required = false) String page
    ) {
        ListVaultCredentialQuery query = VaultCommandConvert.INSTANCE.toListCredentialQuery(
                vaultId, name, includeArchived, limit, afterId, beforeId, page);
        return ApiResponse.success(applicationService.listCredentials(query).map(this::toResponse));
    }

    /**
     * 读取凭证详情（仅元数据，明文绝不下发；不存在 / 越权 → 404）。
     *
     * @param vaultId 保管库 ID
     * @param credentialId 凭证 ID
     * @return 脱敏凭证元数据
     */
    @GetMapping("/{vaultId}/credentials/{credentialId}")
    public ApiResponse<VaultCredentialResponse> credentialDetail(@PathVariable String vaultId,
                                                                 @PathVariable String credentialId) {
        return ApiResponse.success(toResponse(applicationService.getCredential(vaultId, credentialId)));
    }

    /**
     * 更新凭证（merge 补丁，未传字段保持原值）：身份字段不可改绑，{@code auth.type} 不一致 → 400，
     * 已归档凭证 → 409；机密材料按类型合并后整体重加密。
     *
     * @param vaultId 保管库 ID
     * @param credentialId 凭证 ID
     * @param request 更新补丁
     * @return 更新后的脱敏凭证元数据
     */
    @PostMapping("/{vaultId}/credentials/{credentialId}")
    public ApiResponse<VaultCredentialResponse> updateCredential(@PathVariable String vaultId,
                                                                 @PathVariable String credentialId,
                                                                 @Valid @RequestBody UpdateVaultCredentialRequest request) {
        UpdateVaultCredentialCommand command =
                VaultCommandConvert.INSTANCE.toUpdateCredentialCommand(vaultId, credentialId, request);
        return ApiResponse.success(toResponse(applicationService.updateCredential(command)));
    }

    /**
     * 归档凭证（软删）：归档后不再注入新 Session，重复归档返回 409。
     *
     * @param vaultId 保管库 ID
     * @param credentialId 凭证 ID
     * @return 空响应
     */
    @PostMapping("/{vaultId}/credentials/{credentialId}/archive")
    public ApiResponse<Void> archiveCredential(@PathVariable String vaultId,
                                               @PathVariable String credentialId) {
        applicationService.archiveCredential(vaultId, credentialId);
        return ApiResponse.success(null);
    }

    /**
     * 删除凭证（逻辑删，tasks 5.3）：删除后从列表 / 详情不可见，不再参与 Session 挂载鉴权。
     */
    @DeleteMapping("/{vaultId}/credentials/{credentialId}")
    public ApiResponse<VaultDeletedResponse> deleteCredential(@PathVariable String vaultId,
                                                              @PathVariable String credentialId) {
        applicationService.deleteCredential(vaultId, credentialId);
        return ApiResponse.success(VaultDeletedResponse.vaultCredential(credentialId));
    }

    /**
     * 校验凭证（仅 active 的 {@code mcp_oauth}）：先尝试刷新并持久化轮换令牌，再以当前访问令牌
     * 发起最小 MCP 握手探测；结论三态且响应不含任何明文（类型不符 / 已归档 → 409）。
     *
     * @param vaultId 保管库 ID
     * @param credentialId 凭证 ID
     * @return 校验结论与刷新 / 探测诊断
     */
    @PostMapping("/{vaultId}/credentials/{credentialId}/mcp_oauth_validate")
    public ApiResponse<VaultCredentialValidationResponse> validate(@PathVariable String vaultId,
                                                                   @PathVariable String credentialId) {
        return ApiResponse.success(VaultCredentialValidationResponseConvert.INSTANCE.toResponse(
                applicationService.validateCredential(vaultId, credentialId)));
    }

    /**
     * 凭证领域对象 → 脱敏响应：先经应用层装配脱敏视图（丢弃密文成分）再转协议 DTO，
     * 协议层不接触 {@code ciphertext}。
     */
    private VaultCredentialResponse toResponse(VaultCredential credential) {
        VaultCredentialViewDTO view = applicationService.toView(credential);
        return VaultCredentialResponseConvert.INSTANCE.toResponse(view);
    }
}