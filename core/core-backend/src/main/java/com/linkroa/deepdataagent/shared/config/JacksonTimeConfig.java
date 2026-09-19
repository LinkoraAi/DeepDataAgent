package com.linkroa.deepdataagent.shared.config;

import com.linkroa.deepdataagent.shared.util.OffsetDateTimeUtcSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

/**
 * API 时间序列化配置（shared/api-conventions：响应时间戳统一 RFC 3339 UTC {@code Z}）。
 * <p>向 Spring MVC 消息转换使用的 Jackson 3 ObjectMapper 全局注册
 * {@link OffsetDateTimeUtcSerializer}，覆盖默认的 ISO offset 输出
 * （{@code +08:00} 形态），HTTP 响应中的 {@code OffsetDateTime} 一律按 UTC
 * 归零偏移序列化；DB 时区链路（TIMESTAMPTZ / JDBC Asia/Shanghai）不变。</p>
 */
@Configuration
public class JacksonTimeConfig {

    /**
     * 全局时间序列化模块：OffsetDateTime → RFC 3339 UTC（Z 后缀）。
     *
     * @return Jackson 3 模块（Spring Boot 自动装配进 MVC ObjectMapper）
     */
    @Bean
    public JacksonModule offsetDateTimeUtcModule() {
        SimpleModule module = new SimpleModule("api-time-utc");
        module.addSerializer(java.time.OffsetDateTime.class, new OffsetDateTimeUtcSerializer());
        return module;
    }
}
