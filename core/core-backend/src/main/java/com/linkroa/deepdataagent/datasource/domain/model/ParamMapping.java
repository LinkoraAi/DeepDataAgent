package com.linkroa.deepdataagent.datasource.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 动态参数映射值对象
 */
public record ParamMapping(
        String paramName,
        String paramLocation,
        String jsonPath
) {
    public ParamMapping {
        if (StringUtils.isBlank(paramName)) {
            throw new IllegalArgumentException("参数名称不能为空");
        }
        if (StringUtils.isBlank(paramLocation)) {
            throw new IllegalArgumentException("参数位置不能为空");
        }
        if (!"header".equalsIgnoreCase(paramLocation)
                && !"query".equalsIgnoreCase(paramLocation)
                && !"body".equalsIgnoreCase(paramLocation)) {
            throw new IllegalArgumentException("参数位置必须为header、query或body");
        }
        if (StringUtils.isBlank(jsonPath)) {
            throw new IllegalArgumentException("参数获取路径不能为空");
        }
    }
}
