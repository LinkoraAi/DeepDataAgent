package com.linkroa.deepdataagent;

import com.linkroa.deepdataagent.agent.infrastructure.config.AgentMemoryProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SSEConnectionPoolProperties;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SessionEventBusProperties;
import com.linkroa.deepdataagent.agent.infrastructure.sse.agent.AgentExecutionPoolProperties;
import com.linkroa.deepdataagent.datasource.infrastructure.config.EncryptionProperties;
import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import com.linkroa.deepdataagent.shared.config.SqliteProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
// 显式列出所有 @ConfigurationProperties 配置类（替代原 @ConfigurationPropertiesScan 全包扫描），
// 以排除已停用的 memory 模块配置类 MemoryProperties。新增配置类时需手动加入此列表。
@EnableConfigurationProperties({
        SqliteProperties.class,
        OpenSandboxProperties.class,
        EncryptionProperties.class,
        SSEConnectionPoolProperties.class,
        SessionEventBusProperties.class,
        WebSearchProperties.class,
        AgentExecutionPoolProperties.class,
        SessionProperties.class,
        AgentProperties.class,
        AgentMemoryProperties.class,
        DataAnalysisProperties.class
})
@MapperScan("com.linkroa.deepdataagent.**.infrastructure.persistence.mapper")
public class DeepDataAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepDataAgentApplication.class, args);
    }
}
