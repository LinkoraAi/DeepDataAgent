package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Agent 模块配置
 */
@Component
public class AgentConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 注册 Java 8 时间模块，支持 LocalDateTime 序列化（dialogue.messages JSON 字段需要）
        mapper.registerModule(new JavaTimeModule());
        // 时间类型以 ISO-8601 字符串输出（如 2026-08-03T12:00:00），而非时间戳数组
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
