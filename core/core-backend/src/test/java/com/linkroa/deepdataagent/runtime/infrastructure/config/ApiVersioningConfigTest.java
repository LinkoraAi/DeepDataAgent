package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ApiVersioningConfig} 版本化路径判断的单元测试。
 * <p>聚焦 {@link ApiVersioningConfig#isVersionedRequestPath(String)}：
 * 仅 {@code /api/v{数字}} 形态的路径参与版本化，其余路径（datasource 接口等）
 * 必须回落默认版本，避免误解析业务段导致既有接口 400。</p>
 */
class ApiVersioningConfigTest {

    @Test
    @DisplayName("版本化路径 /api/v1/agent/sessions 应判定为版本化")
    void should_returnTrue_when_isVersionedRequestPath_given_VersionedV1AgentPath() {
        // given
        String path = "/api/v1/agent/sessions";

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
            "/api/agent/sessions",
            "/api/datasource/list",
            "/api/v1x/agent/sessions",
            "/api/v",
    })
    void should_returnFalse_when_isVersionedRequestPath_given_UnversionedPath(String path) {
        // given 上述参数化路径

        // when
        boolean versioned = ApiVersioningConfig.isVersionedRequestPath(path);

        // then
        assertEquals(false, versioned);
    }
}