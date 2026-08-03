package com.linkroa.deepdataagent.agent.infrastructure.config;

import com.linkroa.deepdataagent.agent.domain.service.DataAnalysisDomainService;
import com.linkroa.deepdataagent.agent.domain.service.TextToSqlService;
import com.linkroa.deepdataagent.agent.domain.service.port.ChartConfigGenerator;
import com.linkroa.deepdataagent.agent.domain.service.port.LLMGenerationPort;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlGenerationPort;
import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 领域服务装配配置
 * <p>领域服务（{@link TextToSqlService}、{@link DataAnalysisDomainService}）保持纯净，不依赖 Spring 注解与基础设施配置类，
 * 由本配置类在基础设施层（组合根）通过 {@code @Bean} 方法装配，并注入所需的配置值。</p>
 */
@Configuration
public class DomainServiceConfig {

    /**
     * 装配 Text-to-SQL 领域服务
     *
     * @param sqlGenerationPort  SQL 生成端口
     * @param sqlValidationPort  SQL 校验端口
     * @param properties        数据分析配置（用于读取最大重试次数）
     * @return Text-to-SQL 领域服务
     */
    @Bean
    public TextToSqlService textToSqlService(SqlGenerationPort sqlGenerationPort,
                                             SqlValidationPort sqlValidationPort,
                                             DataAnalysisProperties properties) {
        return new TextToSqlService(sqlGenerationPort, sqlValidationPort, properties.getMaxRetryCount());
    }

    /**
     * 装配数据分析领域服务
     *
     * @param textToSqlService   Text-to-SQL 领域服务
     * @param chartGenerator     图表配置生成端口
     * @param llmGenerationPort  LLM 生成端口
     * @param dataSummaryBuilder 数据摘要构建器
     * @return 数据分析领域服务
     */
    @Bean
    public DataAnalysisDomainService dataAnalysisDomainService(TextToSqlService textToSqlService,
                                                               ChartConfigGenerator chartGenerator,
                                                               LLMGenerationPort llmGenerationPort,
                                                               DataSummaryBuilder dataSummaryBuilder) {
        return new DataAnalysisDomainService(textToSqlService, chartGenerator, llmGenerationPort, dataSummaryBuilder);
    }
}