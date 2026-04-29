package com.linkroa.deepdataagent.datasource.infrastructure.config;

import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourceSchemaInitializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 数据源模块配置
 * <p>在应用启动后自动初始化数据库表结构。</p>
 */
@Component
public class DatasourceConfig {

    private final DatasourceSchemaInitializer schemaInitializer;

    public DatasourceConfig(DatasourceSchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        schemaInitializer.initialize();
    }
}
