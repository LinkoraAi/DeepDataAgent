package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱本地 SQLite 库配置（{@code app.sqlite}）：承载沙箱内本地数据文件的落盘路径，
 * 仅由沙箱侧访问，不参与主库事务。
 */
@ConfigurationProperties(prefix = "app.sqlite")
public class SqliteProperties {

    /** 沙箱本地库文件路径（默认落 {@code ./data/sqlite} 目录）。 */
    private String path = "./data/sqlite/deepdataagent.db";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
