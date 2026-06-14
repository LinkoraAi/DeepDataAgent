package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.sqlite")
public class SqliteProperties {

    /**
     * 默认 SQLite 数据库路径。
     * <p>基于项目根目录构建绝对路径，确保无论从何处启动应用，数据库文件都统一存储在项目根目录的 data/sqlite 目录下。</p>
     */
    private static final String DEFAULT_PATH = resolveProjectBasePath() + "/data/sqlite/deepdataagent.db";

    private String path = DEFAULT_PATH;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = StringUtils.hasText(path) ? path : DEFAULT_PATH;
    }

    /**
     * 解析项目根目录路径。
     * <p>优先通过环境变量 APP_BASE_DIR 获取，否则从当前工作目录向上查找最顶层包含 pom.xml 的目录作为项目根目录。
     * 之所以查找最顶层而非第一个，是因为 Maven 多模块项目中子模块目录也包含 pom.xml，
     * 需要跳过子模块目录，定位到真正的项目根目录。</p>
     */
    private static String resolveProjectBasePath() {
        String envPath = System.getenv("APP_BASE_DIR");
        if (envPath != null && !envPath.isBlank()) {
            return envPath;
        }

        // 从当前工作目录向上查找最顶层包含 pom.xml 的目录（即项目根目录）
        Path currentDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path searchDir = currentDir;
        Path topmostProjectDir = null;
        while (searchDir != null) {
            if (Files.exists(searchDir.resolve("pom.xml"))) {
                topmostProjectDir = searchDir;
            }
            searchDir = searchDir.getParent();
        }

        // 如果找到包含 pom.xml 的目录，返回最顶层那个；否则回退到当前工作目录
        return topmostProjectDir != null ? topmostProjectDir.toString() : currentDir.toString();
    }
}
