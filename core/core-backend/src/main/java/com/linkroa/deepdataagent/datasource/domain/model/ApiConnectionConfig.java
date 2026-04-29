package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * API连接配置值对象
 */
public record ApiConnectionConfig(
        String url,
        HttpMethod method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        ApiPaginationConfig paginationConfig,
        int timeout,
        String jsonPath
) {
    private static final int TIMEOUT_MIN = 1;
    private static final int TIMEOUT_MAX = 300;

    public ApiConnectionConfig {
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("接口URL不能为空");
        }
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("接口URL格式不正确");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("接口URL格式不正确");
        }
        if (ObjectUtils.isEmpty(method)) {
            throw new IllegalArgumentException("请求类型不能为空");
        }
        if (timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
            throw new IllegalArgumentException("超时时间必须在" + TIMEOUT_MIN + "到" + TIMEOUT_MAX + "秒之间");
        }
    }
}
