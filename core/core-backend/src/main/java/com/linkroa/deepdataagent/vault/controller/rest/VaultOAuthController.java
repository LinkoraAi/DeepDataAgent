package com.linkroa.deepdataagent.vault.controller.rest;

import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.vault.application.convert.VaultOAuthCommandConvert;
import com.linkroa.deepdataagent.vault.application.dto.OAuthCallbackResultDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthStartResultDTO;
import com.linkroa.deepdataagent.vault.application.service.VaultOAuthApplicationService;
import com.linkroa.deepdataagent.vault.controller.convert.VaultOAuthResponseConvert;
import com.linkroa.deepdataagent.vault.controller.request.StartVaultOAuthRequest;
import com.linkroa.deepdataagent.vault.controller.response.OAuthCallbackPage;
import com.linkroa.deepdataagent.vault.controller.response.OAuthStartResponse;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP OAuth 授权流转 REST 控制器（统一前缀 {@code /cloud/vaults/oauth}）。
 *
 * <p>三个端点构成一条完整链路：</p>
 * <ul>
 *   <li>{@code POST /start}：发起授权（discovery + PKCE S256 + 动态客户端注册），返回
 *       {@code authorization_url} / {@code state} / {@code callback_origin}；请求体不可携带
 *       {@code protocol} / {@code scope} / {@code redirect_uri}（端点与 scopes 由服务端 metadata
 *       发现、回调地址取服务端配置，出现即 400）。</li>
 *   <li>{@code GET /authorize}：浏览器跳转快捷端点（同一条流程的 query 参数形态），成功即
 *       {@code 302} 跳转至授权服务器；不接受 {@code client_secret}——需要密钥时必须走 POST 形态，
 *       URL 中不得携带密文。认证口径与 {@code /start} 一致：本仓库为 Bearer 无状态认证、无 Cookie
 *       会话，故纯浏览器直呼须由具备认证态的调用方发起（SPA 场景用 {@code /start} 取
 *       {@code authorization_url} 后自行打开）。</li>
 *   <li>{@code GET /callback}：授权服务器回调的浏览器跳转终点（<b>免认证</b>，授权约束由一次性
 *       state 承担：TTL + owner 绑定 + origin 记录）。成功后返回 HTML 页面，向 opener 发送
 *       {@code oauth_callback} 消息；失败态经统一错误信封返回（400 / 404 / 409），不产生凭证。</li>
 * </ul>
 *
 * <p>本控制器的任何响应都不含令牌密文与客户端密钥（明文只在服务端换码调用栈内出现）。</p>
 *
 * <p><b>版本承载（design D9）</b>：与 {@code VaultController} 一致以基线版本 {@code "1+"} 声明
 * （只交付 v1 接口，不并行维护第二套形状）；基线声明的固有语义是「本版本及所有更高版本可达」，
 * 故未单独声明版本号时 {@code /api/v2/cloud/vaults/oauth/...} 亦落到本处理器。</p>
 */
@RestController
@RequestMapping(path = "/cloud/vaults/oauth", version = ApiVersionConstants.BASELINE_API_VERSION)
public class VaultOAuthController {

    /** 回调页面响应类型（页面含中文文案，须显式声明 UTF-8）。 */
    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    @Resource
    private VaultOAuthApplicationService applicationService;

    /**
     * 发起授权：返回授权地址与一次性 state，由前端在浏览器中打开授权地址。
     *
     * @param request 请求体（{@code vault_id} / {@code mcp_server_url} / 可选 {@code client_id}、
     *                {@code client_secret}）
     * @param origin  发起授权的前端来源（取 {@code Origin} 头；缺失回落服务端回调地址来源）
     * @return 授权地址、一次性 state 与回调地址来源
     */
    @PostMapping("/start")
    public ApiResponse<OAuthStartResponse> start(@RequestBody StartVaultOAuthRequest request,
                                                 @RequestHeader(name = "Origin", required = false) String origin) {
        return ApiResponse.success(VaultOAuthResponseConvert.INSTANCE.toResponse(
                applicationService.start(VaultOAuthCommandConvert.INSTANCE.toStartCommand(request.fields(), origin))));
    }

    /**
     * 浏览器跳转快捷端点：以 query 参数发起同一条授权流程并跳转至授权服务器。
     *
     * @param vaultId      目标保管库业务 ID
     * @param mcpServerUrl 目标 MCP 服务器 URL
     * @param clientId     客户端 ID（可空 = 走动态客户端注册）
     * @param origin       发起来源（取 {@code Origin} 头；缺失回落服务端回调地址来源）
     * @return {@code 302} 重定向至授权服务器授权地址
     */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam(name = "vault_id", required = false) String vaultId,
            @RequestParam(name = "mcp_server_url", required = false) String mcpServerUrl,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestHeader(name = "Origin", required = false) String origin
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vault_id", vaultId);
        fields.put("mcp_server_url", mcpServerUrl);
        fields.put("client_id", clientId);
        OAuthStartResultDTO result = applicationService.start(
                VaultOAuthCommandConvert.INSTANCE.toStartCommand(fields, origin));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(result.authorizationUrl()))
                .build();
    }

    /**
     * 授权回调终点：一次性消费 state → 授权码换令牌 → 落库为 {@code mcp_oauth} 凭证。
     *
     * @param code  授权服务器携带的授权码
     * @param state 发起授权时下发的一次性 state
     * @return 授权完成页面（向 opener 发送 {@code oauth_callback} 消息）
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam(name = "code", required = false) String code,
                                           @RequestParam(name = "state", required = false) String state) {
        OAuthCallbackResultDTO result = applicationService.callback(code, state);
        return ResponseEntity.ok().contentType(HTML_UTF8).body(OAuthCallbackPage.render(result));
    }
}