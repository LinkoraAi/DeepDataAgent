package com.linkroa.deepdataagent.datasource.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * 连接测试请求体（两种场景共用）。
 * <p>{@code id} 非空时按已保存数据源测试（其余字段忽略）；{@code id} 为空时按临时配置测试，
 * 依据 {@code type} 决定读取 JDBC 字段还是 API 字段。本请求不产生任何持久化副作用。</p>
 *
 * @param id              已保存数据源 id（可空）
 * @param type            临时配置主类型（{@code JDBC} / {@code API}）
 * @param subType         JDBC 子类型
 * @param host            JDBC 主机名
 * @param port            JDBC 端口（1-65535）
 * @param database        JDBC 数据库名
 * @param username        JDBC 用户名
 * @param password        JDBC 密码
 * @param schema          JDBC schema（PostgreSQL 适用）
 * @param apiUrl          API 请求地址
 * @param apiMethod       API 请求方法（{@code GET} / {@code POST}）
 * @param apiHeaders      API 请求头
 * @param apiParams       API 查询参数
 * @param apiBody         API 请求体
 * @param apiBodyType     API 请求体类型（{@code JSON} / {@code FORM_URLENCODED} / {@code RAW}）
 * @param apiAuthType     API 鉴权类型（{@code NO_AUTH} / {@code BASIC_AUTH}）
 * @param apiAuthUsername API 鉴权用户名
 * @param apiAuthPassword API 鉴权密码
 * @param apiTimeout      API 超时（秒）
 * @param apiJsonPath     API 根 JsonPath
 * @author DeepDataAgent
 */
public record TestConnectionRequest(
        Long id,

        String type,
        String subType,
        String host,

        @Min(value = 1, message = "端口必须在1-65535之间")
        @Max(value = 65535, message = "端口必须在1-65535之间")
        Integer port,

        String database,
        String username,
        String password,
        String schema,

        String apiUrl,
        String apiMethod,
        Map<String, String> apiHeaders,
        Map<String, String> apiParams,
        String apiBody,
        String apiBodyType,
        String apiAuthType,
        String apiAuthUsername,
        String apiAuthPassword,
        Integer apiTimeout,
        String apiJsonPath
) {
}