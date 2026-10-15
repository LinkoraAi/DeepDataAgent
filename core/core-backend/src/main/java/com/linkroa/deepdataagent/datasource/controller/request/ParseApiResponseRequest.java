package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/**
 * API 响应试解析请求体（不产生任何持久化）。
 * <p>{@code connectionId} 非空时以该连接首张已存表为基线做覆盖合并；为空则使用本次临时配置。</p>
 *
 * @param connectionId         基线数据源 id（可空）
 * @param url                  请求地址
 * @param method               请求方法
 * @param headers              请求头
 * @param params               查询参数
 * @param body                 请求体内容
 * @param bodyType             请求体类型
 * @param authType             鉴权类型
 * @param authUsername         鉴权用户名
 * @param authPassword         鉴权密码
 * @param timeout              超时（秒）
 * @param retryCount           重试次数
 * @param rootPath             根 JsonPath（缺省 {@code $}）
 * @param paginationType       分页类型
 * @param pageParamName        页码参数名
 * @param sizeParamName        页大小参数名
 * @param totalCountJsonPath   总条数 JsonPath
 * @param pageSize             每页大小
 * @param maxPages             最大页数
 * @param preOperationConfigs  前置操作列表
 * @author DeepDataAgent
 */
public record ParseApiResponseRequest(
        Long connectionId,
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        String bodyType,
        String authType,
        String authUsername,
        String authPassword,
        Integer timeout,
        Integer retryCount,
        String rootPath,
        String paginationType,
        String pageParamName,
        String sizeParamName,
        String totalCountJsonPath,
        Integer pageSize,
        Integer maxPages,

        List<@Valid PreOperationConfigRequest> preOperationConfigs
) {
}