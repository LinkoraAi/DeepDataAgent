package com.linkroa.deepdataagent.datasource.controller.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * API 数据表响应体（**鉴权密码不回显**，嵌套配置不复用领域 record）。
 *
 * @param id                  表 id
 * @param connectionId        所属连接 id
 * @param name                表名称
 * @param url                 请求地址
 * @param method              请求方法
 * @param headers             请求头
 * @param params              查询参数
 * @param body                请求体内容
 * @param bodyType            请求体类型
 * @param jsonPathConfig      根 JsonPath
 * @param timeout             超时（秒）
 * @param retryCount          重试次数
 * @param authConfig          鉴权配置（脱敏）
 * @param paginationConfig    分页配置（null 表示不分页）
 * @param preOperationConfigs 前置操作列表
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 * @author DeepDataAgent
 */
public record ApiSchemaResponse(
        Long id,
        Long connectionId,
        String name,
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        String bodyType,
        String jsonPathConfig,
        Integer timeout,
        Integer retryCount,
        ApiAuthResponse authConfig,
        PaginationConfig paginationConfig,
        List<PreOperationConfig> preOperationConfigs,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * 分页配置响应体。
     *
     * @param paginationType      分页类型（{@code NONE} / {@code PAGE_BASED}）
     * @param pageParamName       页码参数名
     * @param sizeParamName       页大小参数名
     * @param totalCountJsonPath  总条数 JsonPath
     * @param pageSize            每页大小
     * @param maxPages            最大页数
     */
    public record PaginationConfig(
            String paginationType,
            String pageParamName,
            String sizeParamName,
            String totalCountJsonPath,
            Integer pageSize,
            Integer maxPages
    ) {
    }

    /**
     * 前置操作响应体。
     *
     * @param enabled       是否启用
     * @param url           请求地址
     * @param method        请求方法
     * @param headers       请求头
     * @param params        查询参数
     * @param body          请求体内容
     * @param bodyType      请求体类型
     * @param paramMappings 参数映射列表
     */
    public record PreOperationConfig(
            boolean enabled,
            String url,
            String method,
            Map<String, String> headers,
            Map<String, String> params,
            String body,
            String bodyType,
            List<ParamMapping> paramMappings
    ) {
    }

    /**
     * 参数映射响应体。
     *
     * @param paramName     参数名
     * @param paramLocation 注入位置（{@code header} / {@code query} / {@code body}）
     * @param jsonPath      取值 JsonPath
     */
    public record ParamMapping(
            String paramName,
            String paramLocation,
            String jsonPath
    ) {
    }
}