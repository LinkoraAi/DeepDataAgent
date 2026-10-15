package com.linkroa.deepdataagent.runtime.infrastructure.config;

import com.linkroa.deepdataagent.auth.controller.rest.AuthController;
import com.linkroa.deepdataagent.datasource.controller.rest.DatasourceController;
import com.linkroa.deepdataagent.knowledgebase.controller.rest.ChunkController;
import com.linkroa.deepdataagent.knowledgebase.controller.rest.DocumentController;
import com.linkroa.deepdataagent.knowledgebase.controller.rest.KnowledgeBaseController;
import com.linkroa.deepdataagent.rag.controller.rest.RetrievalController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ApiVersioningConfig} 版本化路径判断与控制器包归属的单元测试。
 * <p>覆盖两类判定：</p>
 * <ul>
 *     <li>{@link ApiVersioningConfig#isVersionedRequestPath(String)}：仅 {@code /api/v{数字}}
 *     形态的路径参与版本化，其余路径必须回落默认版本，避免误解析业务段导致既有接口 400；</li>
 *     <li>{@link ApiVersioningConfig#isVersionedController(Class)}：知识库 / RAG / 云资源等上下文
 *     的控制器必须命中版本化包，auth 等其余上下文不得命中。</li>
 * </ul>
 */
class ApiVersioningConfigTest {

    @Test
    @DisplayName("版本化路径 /api/v1/cloud/sessions 应判定为版本化")
    void should_returnTrue_when_isVersionedRequestPath_given_VersionedV1CloudPath() {
        // given
        String path = "/api/v1/cloud/sessions";

        // when
        boolean versioned = ApiVersioningConfig.isVersionedRequestPath(path);

        // then
        assertEquals(true, versioned);
    }

    @Test
    @DisplayName("裸版本路径 /api/v1 应判定为版本化")
    void should_returnTrue_when_isVersionedRequestPath_given_BareVersionPath() {
        // given
        String path = "/api/v1";

        // when
        boolean versioned = ApiVersioningConfig.isVersionedRequestPath(path);

        // then
        assertEquals(true, versioned);
    }

    @ParameterizedTest(name = "路径 {0} 应判定为非版本化")
    @CsvSource({
            "/api/cloud/sessions",
            "/api/datasource/list",
            "/api/v1x/cloud/sessions",
            "/api/v",
    })
    void should_returnFalse_when_isVersionedRequestPath_given_UnversionedPath(String path) {
        // given 上述参数化路径

        // when
        boolean versioned = ApiVersioningConfig.isVersionedRequestPath(path);

        // then
        assertEquals(false, versioned);
    }

    @ParameterizedTest(name = "知识库控制器 {0} 应命中版本化包")
    @ValueSource(classes = {KnowledgeBaseController.class, DocumentController.class,
            ChunkController.class, RetrievalController.class, DatasourceController.class})
    void should_returnTrue_when_isVersionedController_given_KnowledgeBaseControllers(Class<?> controllerType) {
        // given 参数化控制器类型

        // when
        boolean versioned = ApiVersioningConfig.isVersionedController(controllerType);

        // then
        assertEquals(true, versioned);
    }

    @Test
    @DisplayName("认证控制器不命中版本化包")
    void should_returnFalse_when_isVersionedController_given_AuthController() {
        // given
        Class<?> controllerType = AuthController.class;

        // when
        boolean versioned = ApiVersioningConfig.isVersionedController(controllerType);

        // then
        assertEquals(false, versioned);
    }
}