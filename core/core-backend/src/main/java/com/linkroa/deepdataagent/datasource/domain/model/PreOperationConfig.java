package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * 前置操作配置值对象
 */
public record PreOperationConfig(
        boolean enabled,
        String url,
        HttpMethod method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        BodyType bodyType,
        List<ParamMapping> paramMappings
) {
    public PreOperationConfig {
        if (enabled) {
            if (StringUtils.isBlank(url)) {
                throw new IllegalArgumentException("前置操作URL不能为空");
            }
            try {
                URI uri = new URI(url);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    throw new IllegalArgumentException("前置操作URL格式不正确");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("前置操作URL格式不正确");
            }
            if (ObjectUtils.isEmpty(method)) {
                throw new IllegalArgumentException("前置操作请求方式不能为空");
            }
        }
    }
}
