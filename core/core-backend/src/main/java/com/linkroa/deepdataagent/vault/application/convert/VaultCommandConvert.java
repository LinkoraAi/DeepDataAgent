package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.vault.application.command.AddVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.command.CreateVaultCommand;
import com.linkroa.deepdataagent.vault.application.command.UpdateVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.query.ListVaultCredentialQuery;
import com.linkroa.deepdataagent.vault.application.query.ListVaultQuery;
import com.linkroa.deepdataagent.vault.application.validation.VaultMetadataValidator;
import com.linkroa.deepdataagent.vault.controller.request.AddVaultCredentialRequest;
import com.linkroa.deepdataagent.vault.controller.request.CreateVaultRequest;
import com.linkroa.deepdataagent.vault.controller.request.SearchVaultsRequest;
import com.linkroa.deepdataagent.vault.controller.request.UpdateVaultCredentialRequest;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 保管库请求转换器（Request → Command / Query）。
 */
@Mapper
public interface VaultCommandConvert {

    VaultCommandConvert INSTANCE = Mappers.getMapper(VaultCommandConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 创建保管库允许的顶层键（{@code credentials} 不在其中：携带即 400） */
    Set<String> CREATE_VAULT_FIELDS = Set.of("display_name", "metadata");
    /** 创建请求 MUST NOT 接受的字段（凭证只能经 credentials 子资源逐个添加） */
    Set<String> CREATE_VAULT_FORBIDDEN_FIELDS = Set.of("credentials");
    /** 添加凭证允许的顶层键（秘密材料嵌套在 {@code auth} 子树内） */
    Set<String> ADD_CREDENTIAL_FIELDS = Set.of("auth");
    /** {@code static_bearer} 创建时可提供的 auth 键 */
    Set<String> STATIC_BEARER_CREATE_FIELDS = Set.of("type", "mcp_server_url", "token");
    /** {@code mcp_oauth} 创建时可提供的 auth 键（导入访问令牌） */
    Set<String> MCP_OAUTH_CREATE_FIELDS = Set.of("type", "mcp_server_url", "access_token");
    /** 搜索请求允许的请求体键（{@code after_id} / {@code before_id} 不在其中：搜索只认 {@code page}） */
    Set<String> SEARCH_VAULT_FIELDS = Set.of("metadata", "name", "limit", "page", "include_archived");
    /** 更新补丁允许的顶层键（{@code auth} 与 {@code metadata} 两棵子树） */
    Set<String> UPDATE_TOP_LEVEL_FIELDS = Set.of("auth", "metadata");
    /** {@code static_bearer} 允许更新的 auth 键（跨类型误用由本白名单拒绝） */
    Set<String> STATIC_BEARER_AUTH_FIELDS = Set.of("type", "token");
    /** {@code mcp_oauth} 允许更新的 auth 键（跨类型误用由本白名单拒绝） */
    Set<String> MCP_OAUTH_AUTH_FIELDS = Set.of("type", "access_token", "expires_at", "refresh");
    /** auth 下不可修改的身份字段（{@code auth.type} 由一致性裁决拒绝变更） */
    Set<String> AUTH_IDENTITY_FIELDS = Set.of("mcp_server_url");
    /** 刷新配置允许修补的键（身份字段 client_id / token_endpoint 不在其中） */
    Set<String> REFRESH_PATCH_FIELDS = Set.of("refresh_token", "scope", "token_endpoint_auth");
    /** 刷新配置中不可修改的身份字段 */
    Set<String> REFRESH_IDENTITY_FIELDS = Set.of("client_id", "token_endpoint");
    /** 令牌端点鉴权允许的键 */
    Set<String> TOKEN_ENDPOINT_AUTH_FIELDS = Set.of("type", "client_secret");
    /** 显示名称长度上限（与公开契约及 V1 列宽 VARCHAR(255) 一致） */
    int MAX_DISPLAY_NAME_LENGTH = 255;
    /** MCP 服务器 URL 长度上限（与公开契约及 V1 列宽 VARCHAR(2048) 一致） */
    int MAX_MCP_SERVER_URL_LENGTH = 2048;

    /**
     * 创建保管库请求 → 命令。
     *
     * <p>协议层即拒的违规（400）：顶层白名单外的键（含契约明令不接受的 {@code credentials}）、
     * 缺失 / 超长的 {@code display_name}、非对象或越界的 {@code metadata}
     * （key 1–64 / value ≤512，与搜索筛选共用同一判定）。</p>
     */
    default CreateVaultCommand toCreateCommand(CreateVaultRequest request) {
        Map<String, Object> fields = request == null ? Map.of() : request.fields();
        rejectUnknownKeys(fields.keySet(), CREATE_VAULT_FIELDS, "创建请求",
                CREATE_VAULT_FORBIDDEN_FIELDS, "凭证请经 credentials 子资源逐个添加");
        String displayName = requireText(fields.get("display_name"), "display_name",
                MAX_DISPLAY_NAME_LENGTH);
        return new CreateVaultCommand(displayName, toMetadataJson(fields.get("metadata"), "metadata"));
    }

    /**
     * 添加凭证请求 → 命令：秘密材料嵌套在 {@code auth} 子树内
     * （即 {@code auth.type} / {@code auth.mcp_server_url} / {@code auth.token|access_token}）。
     *
     * <p>按类型限定提供的键：{@code static_bearer} 取 {@code token}、{@code mcp_oauth} 取
     * {@code access_token}；跨类型字段与未知键一律 400。命令以统一的秘密材料字段承载明文，
     * 由应用层按类型加密落库。</p>
     */
    default AddVaultCredentialCommand toAddCredentialCommand(String vaultId, AddVaultCredentialRequest request) {
        Map<String, Object> fields = request == null ? Map.of() : request.fields();
        rejectUnknownKeys(fields.keySet(), ADD_CREDENTIAL_FIELDS, "创建请求");
        Map<String, Object> auth = requireObject(fields.get("auth"), "auth");
        String authType = VaultCredentialAuthType.fromValue(requiredText(auth.get("type"), "auth.type"))
                .getValue();
        String mcpServerUrl = requireText(auth.get("mcp_server_url"), "auth.mcp_server_url",
                MAX_MCP_SERVER_URL_LENGTH);
        String secretField = VaultCredentialAuthType.STATIC_BEARER.getValue().equals(authType)
                ? "token" : "access_token";
        Set<String> allowed = VaultCredentialAuthType.STATIC_BEARER.getValue().equals(authType)
                ? STATIC_BEARER_CREATE_FIELDS : MCP_OAUTH_CREATE_FIELDS;
        rejectUnknownKeys(auth.keySet(), allowed, "auth");
        String secret = requireNonBlankText(auth.get(secretField), "auth." + secretField);
        return new AddVaultCredentialCommand(vaultId, authType, mcpServerUrl, secret);
    }

    /**
     * 更新凭证补丁体 → 命令（merge-patch 三态裁决，见 design D3 / D3.1）。
     *
     * <p>以 {@code containsKey} 区分「缺省不改」与「显式提供」：present 且值为 null 装配为
     * 值字段 null + present=true（应用层解释为清除）。协议层即拒的违规（400）：</p>
     * <ul>
     *   <li>未知键（顶层 / auth / refresh / token_endpoint_auth 各层白名单外）；</li>
     *   <li>{@code auth} 提供但 {@code auth.type} 缺失或值域外；</li>
     *   <li>身份字段出现：{@code auth.mcp_server_url}、{@code refresh.client_id}、
     *       {@code refresh.token_endpoint}；</li>
     *   <li>跨类型误用：对 {@code static_bearer} 提交 {@code access_token} / {@code expires_at} /
     *       {@code refresh}，或对 {@code mcp_oauth} 提交 {@code token}（白名单外即拒）。</li>
     * </ul>
     *
     * <p>{@code auth.type} 与凭证既有类型的一致性由应用层裁决（协议层不感知既有状态）；
     * 「无 refresh 配置不可补加」同理由应用层裁决。</p>
     */
    default UpdateVaultCredentialCommand toUpdateCredentialCommand(String vaultId, String credentialId,
                                                                   UpdateVaultCredentialRequest request) {
        Map<String, Object> fields = request == null ? Map.of() : request.fields();
        rejectUnknownKeys(fields.keySet(), UPDATE_TOP_LEVEL_FIELDS, "更新补丁");

        String authType = null;
        String token = null;
        boolean tokenPresent = false;
        String accessToken = null;
        boolean accessTokenPresent = false;
        OffsetDateTime expiresAt = null;
        boolean expiresAtPresent = false;
        String refreshToken = null;
        boolean refreshTokenPresent = false;
        String refreshScope = null;
        boolean refreshScopePresent = false;
        VaultCredentialTokenEndpointAuth refreshTokenEndpointAuth = null;
        boolean refreshTokenEndpointAuthPresent = false;

        if (fields.containsKey("auth")) {
            Map<String, Object> auth = requireObject(fields.get("auth"), "auth");
            if (!auth.containsKey("type")) {
                throw new IllegalArgumentException("auth.type 必填（用于识别本次提交的凭证类型）");
            }
            authType = VaultCredentialAuthType.fromValue(requiredText(auth.get("type"), "auth.type")).getValue();
            rejectIdentityFields(auth.keySet(), AUTH_IDENTITY_FIELDS);
            if (VaultCredentialAuthType.STATIC_BEARER.getValue().equals(authType)) {
                rejectUnknownKeys(auth.keySet(), STATIC_BEARER_AUTH_FIELDS, "static_bearer 的 auth");
                tokenPresent = auth.containsKey("token");
                if (tokenPresent) {
                    token = requireNonBlankText(auth.get("token"), "auth.token");
                }
            } else {
                rejectUnknownKeys(auth.keySet(), MCP_OAUTH_AUTH_FIELDS, "mcp_oauth 的 auth");
                accessTokenPresent = auth.containsKey("access_token");
                if (accessTokenPresent) {
                    accessToken = requireNonBlankText(auth.get("access_token"), "auth.access_token");
                }
                expiresAtPresent = auth.containsKey("expires_at");
                if (expiresAtPresent) {
                    expiresAt = parseTime(auth.get("expires_at"), "auth.expires_at");
                }
                if (auth.containsKey("refresh")) {
                    Map<String, Object> refresh = requireObject(auth.get("refresh"), "auth.refresh");
                    rejectIdentityFields(refresh.keySet(), REFRESH_IDENTITY_FIELDS);
                    rejectUnknownKeys(refresh.keySet(), REFRESH_PATCH_FIELDS, "auth.refresh");
                    refreshTokenPresent = refresh.containsKey("refresh_token");
                    if (refreshTokenPresent) {
                        refreshToken = requireNonBlankText(refresh.get("refresh_token"),
                                "auth.refresh.refresh_token");
                    }
                    refreshScopePresent = refresh.containsKey("scope");
                    if (refreshScopePresent) {
                        refreshScope = optionalText(refresh.get("scope"), "auth.refresh.scope");
                    }
                    refreshTokenEndpointAuthPresent = refresh.containsKey("token_endpoint_auth");
                    if (refreshTokenEndpointAuthPresent) {
                        refreshTokenEndpointAuth = toTokenEndpointAuth(refresh.get("token_endpoint_auth"));
                    }
                }
            }
        }

        boolean metadataPresent = fields.containsKey("metadata");
        String metadataMerge = null;
        if (metadataPresent) {
            Object metadata = fields.get("metadata");
            // 整体显式 null = 重置为 {}（增量文本 null 承载该语义）
            metadataMerge = metadata == null
                    ? null : toMergeJsonText(requireObject(metadata, "metadata"));
        }

        return new UpdateVaultCredentialCommand(
                vaultId, credentialId, authType,
                token, tokenPresent,
                accessToken, accessTokenPresent,
                expiresAt, expiresAtPresent,
                refreshToken, refreshTokenPresent,
                refreshScope, refreshScopePresent,
                refreshTokenEndpointAuth, refreshTokenEndpointAuthPresent,
                metadataMerge, metadataPresent
        );
    }

    /**
     * 保管库列表 HTTP 原始参数 → 查询对象（6.6 管理面 Cursor 约定）。
     *
     * <p>{@code name} 为显示名称模糊过滤（不区分大小写）；归档态由 {@code include_archived}
     * 收敛（缺省 / {@code false} → 仅未归档，{@code true} → 不限含归档）；
     * 结构性筛选（metadata / 归档态词汇）归独立搜索端点（design D13），列表不再接受
     * {@code metadata} / {@code status} 查询参数。</p>
     */
    default ListVaultQuery toListQuery(Long ownerId, String name, String includeArchived,
                                       String limit, String afterId, String beforeId, String page) {
        return new ListVaultQuery(
                ownerId,
                StringUtils.trimToNull(name),
                null,
                resolveIncludeArchived(includeArchived),
                resolveCursor(limit, afterId, beforeId, page)
        );
    }

    /**
     * 保管库搜索请求体 → 查询对象（design D13）：参数只从 JSON Body 取（同请求 URL Query 被忽略）。
     *
     * <p>{@code metadata} 为精确匹配条件集合（多条件 AND，JSONB 包含判定）且 key 1–64 /
     * value ≤512 与写入路径共用 {@link VaultMetadataValidator}；{@code name} 为模糊条件；
     * {@code limit} / {@code page} / {@code include_archived} 语义同列表端点。</p>
     */
    default ListVaultQuery toSearchQuery(Long ownerId, SearchVaultsRequest request) {
        Map<String, Object> fields = request == null ? Map.of() : request.fields();
        rejectUnknownKeys(fields.keySet(), SEARCH_VAULT_FIELDS, "搜索请求");
        return new ListVaultQuery(
                ownerId,
                StringUtils.trimToNull(scalarText(fields.get("name"), "name")),
                toMetadataJson(fields.get("metadata"), "metadata"),
                resolveIncludeArchived(scalarText(fields.get("include_archived"), "include_archived")),
                resolveCursor(scalarText(fields.get("limit"), "limit"), null, null,
                        scalarText(fields.get("page"), "page"))
        );
    }

    /**
     * 凭证列表 HTTP 原始参数 → 查询对象：{@code name} 按绑定的 MCP 服务器 URL 模糊过滤
     * （契约以 {@code name} 承载该搜索，见 tasks 5.2），归档态与游标语义同 Vault 列表。
     */
    default ListVaultCredentialQuery toListCredentialQuery(String vaultId, String name, String includeArchived,
                                                           String limit, String afterId, String beforeId,
                                                           String page) {
        return new ListVaultCredentialQuery(
                vaultId,
                StringUtils.trimToNull(name),
                resolveIncludeArchived(includeArchived),
                resolveCursor(limit, afterId, beforeId, page)
        );
    }

    /**
     * 游标参数装配：{@code page} 为向后翻页游标（等价 {@code after_id}），与
     * {@code after_id} / {@code before_id} 互斥——同时传入多个返回 400。
     */
    private static CursorPageParams resolveCursor(String limit, String afterId, String beforeId, String page) {
        if (StringUtils.isNotBlank(page)) {
            if (StringUtils.isNotBlank(afterId) || StringUtils.isNotBlank(beforeId)) {
                throw new IllegalArgumentException("page 与 after_id / before_id 互斥，不能同时传入");
            }
            return CursorPageParams.parse(limit, page, null);
        }
        return CursorPageParams.parse(limit, afterId, beforeId);
    }

    /** {@code include_archived} → 归档态过滤（缺省 / false → 仅未归档；true → 不限含归档）。 */
    private static Boolean resolveIncludeArchived(String includeArchived) {
        return Boolean.TRUE.equals(parseBoolean(includeArchived, "include_archived")) ? null : Boolean.FALSE;
    }

    /**
     * 令牌端点鉴权对象 → 领域值对象（{@code type} 必填且值域内；{@code client_secret} 必要性由值对象裁决）。
     */
    private static VaultCredentialTokenEndpointAuth toTokenEndpointAuth(Object node) {
        Map<String, Object> auth = requireObject(node, "auth.refresh.token_endpoint_auth");
        rejectUnknownKeys(auth.keySet(), TOKEN_ENDPOINT_AUTH_FIELDS, "auth.refresh.token_endpoint_auth");
        return new VaultCredentialTokenEndpointAuth(
                VaultCredentialTokenEndpointAuthType.fromValue(
                        requiredText(auth.get("type"), "auth.refresh.token_endpoint_auth.type")),
                optionalText(auth.get("client_secret"), "auth.refresh.token_endpoint_auth.client_secret"));
    }

    /**
     * metadata 键值对象 → 紧凑 JSON 文本（可空 / 缺失 = 无 metadata）。
     * <p>与搜索筛选共用 {@link VaultMetadataValidator}（key 1–64 / value ≤512），
     * 避免「只在搜索时校验、写入时放行」造出搜索永远命中不了的脏值。</p>
     */
    private static String toMetadataJson(Object node, String field) {
        if (node == null) {
            return null;
        }
        Map<String, Object> metadata = requireObject(node, field);
        if (metadata.isEmpty()) {
            return null;
        }
        VaultMetadataValidator.validate(metadata);
        return toMergeJsonText(metadata);
    }

    /** 未知 / 禁用键拒绝（400）。 */
    private static void rejectUnknownKeys(Set<String> keys, Set<String> allowed, String scope) {
        rejectUnknownKeys(keys, allowed, scope, Set.of(), null);
    }

    /**
     * 未知 / 禁用键拒绝（400）：白名单外的键一律拒绝；命中禁用集时采用该字段的专用说明
     * （契约明令 MUST NOT 接受的字段须给出可归因的拒绝理由）。
     */
    private static void rejectUnknownKeys(Set<String> keys, Set<String> allowed, String scope,
                                          Set<String> forbidden, String forbiddenHint) {
        for (String key : keys) {
            if (forbidden.contains(key)) {
                throw new IllegalArgumentException("不支持 " + scope + " 的字段: " + scope + "." + key
                        + (StringUtils.isBlank(forbiddenHint) ? "" : "（" + forbiddenHint + "）"));
            }
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("不支持 " + scope + " 的字段: " + scope + "." + key);
            }
        }
    }

    /** 身份字段出现即拒（400，身份字段不可修改）。 */
    private static void rejectIdentityFields(Set<String> keys, Set<String> identityFields) {
        for (String key : keys) {
            if (identityFields.contains(key)) {
                throw new IllegalArgumentException(key + " 为身份字段，不可修改");
            }
        }
    }

    /** JSON 对象形态校验（非对象 → 400）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object node, String field) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(field + " 必须为 JSON 对象");
    }

    /** 必填文本且长度受限（缺失 / 空白 / 非字符串 / 超长 → 400，裁剪首尾空白）。 */
    private static String requireText(Object node, String field, int maxLength) {
        String text = requiredText(node, field);
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " 长度不能超过" + maxLength + "个字符");
        }
        return text;
    }

    /** 必填非空文本（缺失 / 空白 / 非字符串 → 400；<b>原样返回不裁剪</b>——秘密值不得改写）。 */
    private static String requireNonBlankText(Object node, String field) {
        String text = optionalText(node, field);
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return text;
    }

    /** 必填标识文本（缺失 / 空白 / 非字符串 → 400，裁剪首尾空白）。 */
    private static String requiredText(Object node, String field) {
        String text = optionalText(node, field);
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return text.trim();
    }

    /** 可空文本（null → null；非字符串 → 400）。 */
    private static String optionalText(Object node, String field) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof String text)) {
            throw new IllegalArgumentException(field + " 必须为字符串");
        }
        return text;
    }

    /**
     * 可空标量 → 文本（JSON Body 中 {@code limit} / {@code include_archived} 可能是数字 /
     * 布尔 / 字符串三种形态，统一收敛为文本后交由严格解析器裁决）。
     */
    private static String scalarText(Object node, String field) {
        if (node == null) {
            return null;
        }
        if (node instanceof String text) {
            return text;
        }
        if (node instanceof Number || node instanceof Boolean) {
            return node.toString();
        }
        throw new IllegalArgumentException(field + " 必须为字符串或标量");
    }

    /** 可空时间（null=清除；空白 / 非字符串 / 非法格式 → 400）。 */
    private static OffsetDateTime parseTime(Object node, String field) {
        String raw = optionalText(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必须为 ISO-8601 时间: " + raw, e);
        }
    }

    /** 元数据键值对象 → JSON 文本（保留 null 值键 = 键级删除标记）。 */
    private static String toMergeJsonText(Map<String, Object> merge) {
        try {
            return OBJECT_MAPPER.writeValueAsString(merge);
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 布尔查询参数严格解析（空白=缺省 null，仅接受 true/false，其余 → 400）。 */
    private static Boolean parseBoolean(String raw, String paramName) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(paramName + " 仅接受 true / false: " + raw);
    }
}