package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * API 数据表配置请求体（创建与更新共用）。
 * <p>更新时采用增量合并语义：字段为 {@code null} 表示沿用原值；{@code fields} 非空则全量重建字段映射。</p>
 *
 * @param name                  表名称（必填）
 * @param method                请求方法（{@code GET} / {@code POST}，缺省 GET）
 * @param url                   请求地址（必填）
 * @param headers               请求头
 * @param params                查询参数
 * @param body                  请求体内容
 * @param bodyType              请求体类型（{@code JSON} / {@code FORM_URLENCODED} / {@code RAW}）
 * @param jsonPathConfig        根 JsonPath（数组须以 {@code .*} 结尾）
 * @param timeout               超时（秒，缺省 180）
 * @param retryCount            重试次数（0-5）
 * @param authType              鉴权类型（{@code NO_AUTH} / {@code BASIC_AUTH}）
 * @param authUsername          鉴权用户名
 * @param authPassword          鉴权密码
 * @param paginationType        分页类型（{@code NONE} / {@code PAGE_BASED}）
 * @param pageSizeParamName     页大小参数名（PAGE_BASED 必填）
 * @param pageNumberParamName   页码参数名（PAGE_BASED 必填）
 * @param totalCountJsonPath    总条数 JsonPath
 * @param pageSize              每页大小（1-500）
 * @param maxPages              最大页数（1-10000）
 * @param preOperationConfigs   前置操作列表
 * @param fields                字段映射列表（非空则全量重建）
 * @author DeepDataAgent
 */
public record ApiSchemaRequest(
        @NotBlank(message = "API表名称不能为空")
        String name,

        String method,

        @NotBlank(message = "API请求地址不能为空")
        String url,

        Map<String, String> headers,
        Map<String, String> params,
        String body,
        String bodyType,
        String jsonPathConfig,
        Integer timeout,

        @Min(value = 0, message = "重试次数不能小于0")
        @Max(value = 5, message = "重试次数不能超过5")
        Integer retryCount,

        String authType,
        String authUsername,
        String authPassword,
        String paginationType,
        String pageSizeParamName,
        String pageNumberParamName,
        String totalCountJsonPath,

        @Min(value = 1, message = "每页大小必须在1-500之间")
        @Max(value = 500, message = "每页大小必须在1-500之间")
        Integer pageSize,

        @Min(value = 1, message = "最大页数必须在1-10000之间")
        @Max(value = 10000, message = "最大页数必须在1-10000之间")
        Integer maxPages,

        List<@Valid PreOperationConfigRequest> preOperationConfigs,

        List<@Valid ApiFieldRequest> fields
) {
}