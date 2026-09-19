package com.linkroa.deepdataagent.runtime.infrastructure.config;

import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.RequestPath;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 运行时与 Agent 管理 API 版本化配置（基于 Spring Framework 7 一等公民 API Versioning，
 * WebMvcConfigurer#configureApiVersioning 覆盖由 Spring Boot 语言服务器识别为已配置版本化）。
 * <p>采用<b>路径版本化</b>策略：URL 形态为 {@code /api/v{主版本}/...}（如
 * {@code GET /api/v1/cloud/sessions}、{@code GET /api/v1/cloud/agents}），版本段
 * {@code v1} 由 {@link org.springframework.web.accept.PathApiVersionResolver} 从请求路径
 * 第 2 段（index 1）解析，并经语义化解析器归一为版本号 {@code 1.0.0}，与控制器
 * {@code @RequestMapping(version = "1")} 声明匹配。</p>
 * <p>范围控制：对 {@code runtime.controller}、{@code agent.controller}、
 * {@code memory.controller}、{@code vault.controller}、{@code skill.controller}、
 * {@code file.controller} 包下的
 * REST 控制器挂载 {@code /api/{version}} 路径前缀（6.1 起 memory/vault/skill 管理面
 * 对齐对外统一 {@code /api/v1/...} 形态；align-cloud-agents-session-platform 起全部云
 * Agent 资源端点统一挂 {@code /api/v1/cloud/*}）；auth 等其余上下文控制器路径
 * 形态保持不变，版本解析谓词返回 false 时回落到默认版本，
 * 避免误解析非版本段而影响既有接口。</p>
 * <p><b>保管库面的基线声明（design D9）</b>：路径版本策略对 {@code /api/v{n}/...} 全体友好（解析器只按
 * 第 2 段形态提取主版本），故新增版本段无需在本类登记版本号。Spring Framework 7 的
 * {@code @RequestMapping#version} 为<b>单值</b>属性，无法一次声明多个版本，保管库面控制器据此声明
 * {@code @RequestMapping(version = ApiVersionConstants.BASELINE_API_VERSION)}（即 {@code "1+"}）：
 * 项目未上线只交付 v1 接口，该声明的固有语义为「本版本及所有更高版本可达」，因此未单独声明版本号时
 * {@code /api/v2/vaults} 亦落入同一处理器——属基线匹配结果，而非并行维护的 v2 契约；后续若需为更高版本
 * 冻结独立形状，声明固定版本 {@code "2"} 的控制器会按「高版本上浮」自动优先命中。</p>
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    /** 版本化路径前缀：{@code version} 以 URI 变量形式声明，供版本段解析与路径匹配共用。 */
    private static final String VERSIONED_PATH_PREFIX = "/api/{version}";

    /** 启用版本化的控制器包前缀（运行时 + Agent 管理 + 记忆库 / 保管库 / 技能资产 / 文件）。 */
    private static final String[] VERSIONED_CONTROLLER_PACKAGES = {
            "com.linkroa.deepdataagent.runtime.controller",
            "com.linkroa.deepdataagent.agent.controller",
            "com.linkroa.deepdataagent.memory.controller",
            "com.linkroa.deepdataagent.vault.controller",
            "com.linkroa.deepdataagent.skill.controller",
            "com.linkroa.deepdataagent.file.controller"
    };

    /**
     * 注册版本解析器：从请求路径第 2 段（index 1）提取版本，格式须为 {@code v{主版本}}
     * （如 {@code /api/v1/cloud/sessions} 中提取 {@code v1}）；非该形态的路径
     * （如 auth 接口 {@code /api/auth/...}）不参与版本化，回落到默认版本。
     */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .usePathSegment(1, this::isVersionedRequestPath)
                .setDefaultVersion(ApiVersionConstants.CURRENT_API_VERSION);
    }

    /**
     * 为版本化控制器统一挂载 {@code /api/{version}} 路径前缀，控制器内只需声明
     * 业务路径（如 {@code /cloud/sessions}、{@code /cloud/model-profiles}），
     * 最终映射形如 {@code /api/{version}/cloud/sessions}。
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(VERSIONED_PATH_PREFIX, this::isVersionedController);
    }

    /**
     * 判断控制器类型是否属于启用版本化的上下文（runtime / agent / memory / vault / skill / file）。
     * <p>其余上下文（auth 等）接口路径形态保持不变。</p>
     */
    private boolean isVersionedController(Class<?> controllerType) {
        if (!controllerType.isAnnotationPresent(RestController.class)) {
            return false;
        }
        String packageName = controllerType.getPackageName();
        for (String pkg : VERSIONED_CONTROLLER_PACKAGES) {
            if (packageName.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断请求路径是否为版本化路径（形态 {@code /api/v{主版本}(/...)?}）。
     * <p>非版本化路径（如 auth 接口、静态资源、错误转发）返回 false，
     * 版本解析回落到默认版本，避免把业务段误当版本段解析导致 400。</p>
     */
    boolean isVersionedRequestPath(RequestPath path) {
        return isVersionedRequestPath(path.pathWithinApplication().value());
    }

    /**
     * 路径形态匹配：以 {@code /api/v{数字}} 开头。
     * <p>独立方法便于单元测试（见 ApiVersioningConfigTest）。</p>
     */
    static boolean isVersionedRequestPath(String pathWithinApplication) {
        return pathWithinApplication.matches("^/api/v\\d+(/.*)?$");
    }
}