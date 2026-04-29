package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.sqlite")
public class SqliteProperties {

    private static final String DEFAULT_PATH = System.getProperty("user.dir") + "/data/sqlite/deepdataagent.db";

    private String path = DEFAULT_PATH;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = StringUtils.hasText(path) ? path : DEFAULT_PATH;
    }
}
