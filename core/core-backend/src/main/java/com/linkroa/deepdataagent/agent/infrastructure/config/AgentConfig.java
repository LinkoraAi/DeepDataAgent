package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.linkroa.deepdataagent.agent.infrastructure.persistence.AgentModelSchemaInitializer;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.AgentSessionSchemaInitializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Agent 模块配置
 * <p>在应用启动后自动初始化模型配置和会话相关的数据库表结构。</p>
 */
@Component
public class AgentConfig {

    private final AgentModelSchemaInitializer modelSchemaInitializer;
    private final AgentSessionSchemaInitializer sessionSchemaInitializer;

    public AgentConfig(AgentModelSchemaInitializer modelSchemaInitializer,
                       AgentSessionSchemaInitializer sessionSchemaInitializer) {
        this.modelSchemaInitializer = modelSchemaInitializer;
        this.sessionSchemaInitializer = sessionSchemaInitializer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        modelSchemaInitializer.initialize();
        sessionSchemaInitializer.initialize();
    }
}
