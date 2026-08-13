package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.linkroa.deepdataagent.agent.domain.service.NL2SqlService;
import com.linkroa.deepdataagent.agent.domain.service.port.NL2SqlPort;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 领域服务装配配置
 * <p>领域服务（{@link NL2SqlService}）保持纯净，不依赖 Spring 注解与基础设施配置类，
 * 由本配置类在基础设施层（组合根）通过 {@code @Bean} 方法装配，并注入所需的配置值。</p>
 */
@Configuration
public class DomainServiceConfig {

    /**
     * 装配 NL2SQL 领域服务
     *
     * @param nl2SqlPort       NL2SQL 生成端口
     * @param sqlValidationPort  SQL 校验端口
     * @param properties        数据分析配置（用于读取最大重试次数）
     * @return NL2SQL 领域服务
     */
    @Bean
    public NL2SqlService nl2SqlService(NL2SqlPort nl2SqlPort,
                                       SqlValidationPort sqlValidationPort,
                                       DataAnalysisProperties properties) {
        return new NL2SqlService(nl2SqlPort, sqlValidationPort, properties.getMaxRetryCount());
    }
}