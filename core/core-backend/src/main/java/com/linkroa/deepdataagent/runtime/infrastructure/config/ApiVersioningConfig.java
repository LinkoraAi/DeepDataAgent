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
 * {@code skill.controller}、{@code file.controller}、{@code knowledgebase.controller}、
 * {@code rag.controller} 包下的
 * REST 控制器挂载 {@code /api/{version}} 路径前缀（skill 管理面对齐对外统一
 * {@code /api/v1/...} 形态；全部云 Agent 资源端点统一挂 {@code /api/v1/cloud/*}；
 * 知识库与 RAG 域统一挂 {@code /api/v1/knowledge-base/*}、{@code /api/v1/rag/*}，
 * 与控制器自身拼装的切片媒体相对 URL 及网关转发规则保持一致）；
 * auth 等其余上下文控制器路径形态保持不变，版本解析谓词返回 false 时回落到默认版本，
 * 避免误解析非版本段而影响既有接口。</p>
 * <p>本期只交付 v1：所有版本化控制器固定声明 {@code CURRENT_API_VERSION}；更高主版本请求
 * （如 {@code /api/v2/...}）无匹配处理器，经框架默认语义以 400 拒绝。</p>
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    /** 版本化路径前缀：{@code version} 以 URI 变量形式声明，供版本段解析与路径匹配共用。 */
    private static final String VERSIONED_PATH_PREFIX = "/api/{version}";

    /** 启用版本化的控制器包前缀（运行时 + Agent 管理 + 技能资产 / 文件 + 知识库 / RAG / 数据源）。 */
    private static final String[] VERSIONED_CONTROLLER_PACKAGES = {
            "com.linkroa.deepdataagent.runtime.controller",
            "com.linkroa.deepdataagent.agent.controller",
            "com.linkroa.deepdataagent.skill.controller",
            "com.linkroa.deepdataagent.file.controller",
            "com.linkroa.deepdataagent.knowledgebase.controller",
            "com.linkroa.deepdataagent.rag.controller",
            "com.linkroa.deepdataagent.datasource.controller"
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
        configurer.addPathPrefix(VERSIONED_PATH_PREFIX, ApiVersioningConfig::isVersionedController);
    }

    /**
     * 判断控制器类型是否属于启用版本化的上下文（runtime / agent / skill / file / knowledgebase / rag）。
     * <p>其余上下文（auth 等）接口路径形态保持不变。</p>
     * <p>包可见静态方法，便于单元测试直接断言包归属，无需启动上下文。</p>
     */
    static boolean isVersionedController(Class<?> controllerType) {
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