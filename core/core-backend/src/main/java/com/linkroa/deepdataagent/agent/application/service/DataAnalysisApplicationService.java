package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.ApiConnectionInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.event.AnalysisEvent;
import com.linkroa.deepdataagent.agent.controller.response.DataAnalysisResponse;
import com.linkroa.deepdataagent.agent.domain.model.ChartConfig;
import com.linkroa.deepdataagent.agent.domain.model.ChartType;
import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisQuery;
import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisResult;
import com.linkroa.deepdataagent.agent.domain.repository.SessionRepository;
import com.linkroa.deepdataagent.agent.domain.service.DataAnalysisDomainService;
import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.executor.QueryExecutor;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.MessagePersistenceService;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ConversationMsgEntity;
import com.linkroa.deepdataagent.agent.infrastructure.tool.AnalysisGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ApiDataFetcherTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ChartGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SchemaRetrieverTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SqlExecutorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.TextToSqlTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.WebSearchTool;
import com.linkroa.deepdataagent.agent.infrastructure.hook.SearchResultsHook;
import com.linkroa.deepdataagent.memory.DeepLongMemory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据分析应用服务
 * <p>基于 AgentScope ReActAgent 架构，由 LLM 自主决策工具调用顺序完成数据分析。
 * 同时保留同步 Pipeline 方法用于向后兼容和测试。</p>
 */
@Service
public class DataAnalysisApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisApplicationService.class);

    private static final String SYS_PROMPT = """
            你是一个数据分析专家 Agent（DeepDataAnalyst）。
            你的任务是根据用户的自然语言问题，通过调用工具完成数据分析。

            ## 工作流程
            1. 调用 retrieve_schema 获取数据库表结构
               - 了解可用表和字段含义
               - 根据用户问题，可使用 keyword 参数过滤相关表
            2. 调用 generate_sql 将用户问题转换为 SQL
               - 确保 SQL 能正确回答用户问题
               - 使用 retrieve_schema 获取的 schema 信息
            3. 调用 execute_sql 执行 SQL 查询
               - 如果执行失败，分析错误原因并修正 SQL
               - 最多重试 2 次，仍失败则报告错误
            4. 调用 generate_chart 生成可视化图表
               - 根据数据类型选择最合适的图表
               - 传入查询结果和用户问题
            5. 调用 generate_analysis 生成完整分析报告
               - 报告应包含：分析概述、关键发现、详细分析、结论建议
               - 传入用户问题、SQL、数据摘要、图表信息

            ## 联网搜索使用指南
            ### 何时使用 web_search
            - 用户问题涉及时间敏感性信息（如"最新政策"、"2025年数据"、"近期趋势"）
            - 用户问题涉及数据库无法回答的外部知识（如"行业趋势"、"竞品分析"、"市场规模"）
            - 用户明确要求搜索互联网或引用外部来源
            - 搜索关键词应简洁明确，避免过长句子

            ### 何时不使用 web_search
            - 用户问题仅涉及数据库内部数据（如"上个月销售额"、"用户增长率"、"各区域对比"）
            - 数据库已有足够信息回答用户问题

            ### 搜索时机与综合分析
            - 当问题需要结合数据库数据和外部知识时（如"对比我司销售额与行业平均"），先调用数据库查询工具获取内部数据，再调用 web_search 获取外部知识，最后在报告中综合分析
            - 在最终报告中引用搜索到的信息来源（URL）

            ### 重要约束
            - 仅当用户在前端界面明确启用"联网搜索"选项时，才可以使用 web_search 工具
            - 如果用户未启用联网搜索，即使问题需要外部知识，也不得调用 web_search 工具
            - 工具列表中是否包含 web_search 工具由系统根据用户选择动态决定，请根据实际可用工具进行判断

            ## 重要规则
            - 工具应按顺序调用，但可根据需要调整
            - SQL 执行失败时，先分析错误再修正，不要盲目重试
            - 生成分析报告前，确保已完成数据查询和图表生成
            - 使用中文回复用户
            - 最终输出应是一份结构化的 Markdown 分析报告
            - **联网搜索工具使用约束**：web_search 工具仅在用户明确启用"联网搜索"选项时才会被注册到可用工具集中。如果当前可用工具列表中没有 web_search 工具，说明用户未启用联网搜索功能，此时不得尝试调用该工具。请根据实际可用的工具列表来决定是否使用 web_search
            """;

    private static final String API_SYS_PROMPT = """
            你是一个数据分析专家 Agent（DeepDataAnalyst）。
            你的任务是根据用户的自然语言问题，通过调用工具完成数据分析。

            ## 工作流程（API 数据源）
            1. 调用 retrieve_schema 获取 API 数据源的结构信息
               - 了解可用的 API 表（Schema）和字段含义
               - 根据用户问题，选择相关的 API 表
            2. 调用 execute_api_query 获取 API 数据
               - 根据 retrieve_schema 返回的表名，指定要查询的 API Schema
               - 可设置 limit 参数控制返回数据量
            3. 调用 generate_chart 生成可视化图表
               - 根据数据类型选择最合适的图表
               - 传入查询结果和用户问题
            4. 调用 generate_analysis 生成完整分析报告
               - 报告应包含：分析概述、关键发现、详细分析、结论建议
               - 传入用户问题、数据摘要、图表信息

            ## 联网搜索使用指南
            ### 何时使用 web_search
            - 用户问题涉及时间敏感性信息（如"最新政策"、"2025年数据"、"近期趋势"）
            - 用户问题涉及 API 数据无法回答的外部知识（如"行业趋势"、"竞品分析"、"市场规模"）
            - 用户明确要求搜索互联网或引用外部来源
            - 搜索关键词应简洁明确，避免过长句子

            ### 何时不使用 web_search
            - 用户问题仅涉及 API 内部数据（如"接口调用量"、"响应时间统计"）
            - API 数据已有足够信息回答用户问题

            ### 搜索时机与综合分析
            - 当问题需要结合 API 数据和外部知识时（如"对比我司 API 调用量与行业平均"），先调用 API 数据获取工具获取内部数据，再调用 web_search 获取外部知识，最后在报告中综合分析
            - 在最终报告中引用搜索到的信息来源（URL）

            ### 重要约束
            - 仅当用户在前端界面明确启用"联网搜索"选项时，才可以使用 web_search 工具
            - 如果用户未启用联网搜索，即使问题需要外部知识，也不得调用 web_search 工具
            - 工具列表中是否包含 web_search 工具由系统根据用户选择动态决定，请根据实际可用工具进行判断

            ## 重要规则
            - API 数据源不支持 SQL，请直接使用 execute_api_query 获取数据
            - 生成分析报告前，确保已完成数据获取和图表生成
            - 使用中文回复用户
            - 最终输出应是一份结构化的 Markdown 分析报告
            - **联网搜索工具使用约束**：web_search 工具仅在用户明确启用"联网搜索"选项时才会被注册到可用工具集中。如果当前可用工具列表中没有 web_search 工具，说明用户未启用联网搜索功能，此时不得尝试调用该工具。请根据实际可用的工具列表来决定是否使用 web_search
            """;

    private final DataAnalysisDomainService domainService;
    private final DatasourceGateway datasourceGateway;
    private final List<QueryExecutor> queryExecutors;
    private final LLMClient llmClient;
    private final AgentSessionManager sessionManager;
    private final MessagePersistenceService messagePersistenceService;
    private final SessionRepository sessionRepository;
    private final SessionProperties sessionProperties;
    private final SchemaRetrieverTool schemaRetrieverTool;
    private final TextToSqlTool textToSqlTool;
    private final SqlExecutorTool sqlExecutorTool;
    private final ApiDataFetcherTool apiDataFetcherTool;
    private final ChartGeneratorTool chartGeneratorTool;
    private final AnalysisGeneratorTool analysisGeneratorTool;
    private final WebSearchTool webSearchTool;

    public DataAnalysisApplicationService(
            DataAnalysisDomainService domainService,
            DatasourceGateway datasourceGateway,
            List<QueryExecutor> queryExecutors,
            LLMClient llmClient,
            AgentSessionManager sessionManager,
            MessagePersistenceService messagePersistenceService,
            SessionRepository sessionRepository,
            SessionProperties sessionProperties,
            SchemaRetrieverTool schemaRetrieverTool,
            TextToSqlTool textToSqlTool,
            SqlExecutorTool sqlExecutorTool,
            ApiDataFetcherTool apiDataFetcherTool,
            ChartGeneratorTool chartGeneratorTool,
            AnalysisGeneratorTool analysisGeneratorTool,
            WebSearchTool webSearchTool) {
        this.domainService = domainService;
        this.datasourceGateway = datasourceGateway;
        this.queryExecutors = queryExecutors;
        this.llmClient = llmClient;
        this.sessionManager = sessionManager;
        this.messagePersistenceService = messagePersistenceService;
        this.sessionRepository = sessionRepository;
        this.sessionProperties = sessionProperties;
        this.schemaRetrieverTool = schemaRetrieverTool;
        this.textToSqlTool = textToSqlTool;
        this.sqlExecutorTool = sqlExecutorTool;
        this.apiDataFetcherTool = apiDataFetcherTool;
        this.chartGeneratorTool = chartGeneratorTool;
        this.analysisGeneratorTool = analysisGeneratorTool;
        this.webSearchTool = webSearchTool;
    }

    /**
     * 构建 ReActAgent 实例
     * <p>支持上下文恢复：当创建新 Agent 时，从数据库加载历史消息并注入到短期记忆中。
     * 根据数据源类型条件化注册工具集和选择系统提示词。</p>
     *
     * @param sessionId 会话 ID
     * @param modelConfigId 模型配置 ID
     * @param userQuestion 用户问题，用于长期记忆检索
     * @param category 数据源类型
     * @param enableWebSearch 是否启用网络搜索工具
     * @param hooks Hook 列表，用于拦截 Agent 事件
     * @return ReActAgent 实例
     */
    private ReActAgent buildAgent(String sessionId, Long modelConfigId, String userQuestion, DatasourceCategory category, boolean enableWebSearch, List<Hook> hooks) {
        ChatModelBase chatModel = llmClient.getChatModel(modelConfigId);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(schemaRetrieverTool);

        if (category == DatasourceCategory.JDBC) {
            toolkit.registerTool(textToSqlTool);
            toolkit.registerTool(sqlExecutorTool);
        } else {
            toolkit.registerTool(apiDataFetcherTool);
        }

        toolkit.registerTool(chartGeneratorTool);
        toolkit.registerTool(analysisGeneratorTool);
        
        // 条件化注册网络搜索工具
        if (enableWebSearch) {
            toolkit.registerTool(webSearchTool);
        }

        String prompt = resolvePrompt(category);
        ReActAgent agent = sessionManager.getOrCreateAgent(sessionId, chatModel, prompt, toolkit, hooks);

        // 如果 Agent 是从缓存中获取的，短期记忆可能已有消息，不需要恢复
        // 如果是新创建的 Agent，短期记忆为空，需要注入历史上下文
        Memory memory = agent.getMemory();
        if (memory instanceof InMemoryMemory inMemMemory && inMemMemory.getMessages().isEmpty()) {
            injectRecoveryContext(inMemMemory, sessionId, userQuestion, modelConfigId);
        }

        return agent;
    }

    /**
     * 注入恢复上下文到短期记忆中
     * <p>从数据库加载最近的历史消息并注入到 InMemoryMemory，
     * 同时尝试从长期记忆中检索相关内容。</p>
     *
     * @param memory 短期记忆实例
     * @param sessionId 会话 ID
     * @param userQuestion 当前用户问题
     * @param modelConfigId 模型配置 ID
     */
    private void injectRecoveryContext(InMemoryMemory memory, String sessionId,
                                       String userQuestion, Long modelConfigId) {
        // 加载最近的历史消息
        int contextLoadSize = sessionProperties.getContextLoadSize();
        List<ConversationMsgEntity> recentMessages = sessionRepository.findRecentMessages(sessionId, contextLoadSize);

        if (!CollectionUtils.isEmpty(recentMessages)) {
            for (ConversationMsgEntity entity : recentMessages) {
                String role = entity.getRole();
                String content = entity.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }

                MsgRole msgRole = resolveMsgRole(role);
                Msg msg = Msg.builder()
                        .role(msgRole)
                        .textContent(content)
                        .build();
                memory.addMessage(msg);
            }
            log.info("DataAnalysisApplicationService: restored {} messages for session={}",
                    recentMessages.size(), sessionId);
        }

        // 尝试检索长期记忆
        try {
            DeepLongMemory longTermMemory = sessionManager.getOrCreateMemory(sessionId);
            Msg queryMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(userQuestion)
                    .build();
            String retrievedMemories = longTermMemory.retrieve(queryMsg).block();
            if (!ObjectUtils.isEmpty(retrievedMemories)) {
                Msg memoryMsg = Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent("## 长期记忆参考\n" + retrievedMemories)
                        .build();
                memory.addMessage(memoryMsg);
                log.info("DataAnalysisApplicationService: retrieved long-term memories for session={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("DataAnalysisApplicationService: failed to retrieve long-term memories for session={}: {}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 将数据库中的角色字符串转换为 MsgRole 枚举
     */
    private MsgRole resolveMsgRole(String role) {
        if (role == null) {
            return MsgRole.USER;
        }
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }

    /**
     * 流式执行数据分析（SSE），返回 Flux<AnalysisEvent>
     */
    public Flux<AnalysisEvent> executeStream(DataAnalysisCommand command) {
        String sessionId = command.sessionId();
        Sinks.Many<AnalysisEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 异步执行
        Mono.fromCallable(() -> {
            try {
                // 1. 校验会话是否存在且处于活跃状态
                Optional<AgentSessionEntity> sessionOpt = sessionRepository.findById(sessionId);
                if (sessionOpt.isEmpty()) {
                    sink.tryEmitNext(AnalysisEvent.error("会话不存在"));
                    sink.tryEmitComplete();
                    return null;
                }

                AgentSessionEntity session = sessionOpt.get();
                if (!"active".equals(session.getStatus())) {
                    sink.tryEmitNext(AnalysisEvent.error("会话已关闭"));
                    sink.tryEmitComplete();
                    return null;
                }

                // 2. 校验数据源
                DatasourceInfo datasource = datasourceGateway.findDatasource(
                                Long.valueOf(command.connectionId()))
                        .orElseThrow(() -> new DataAnalysisException("数据源不存在: " + command.connectionId()));
                if (!datasource.enabled()) {
                    throw new DataAnalysisException("数据源未启用");
                }

                sink.tryEmitNext(AnalysisEvent.thinking("正在分析您的问题..."));

                // 3. 判断是否为首次分析
                boolean isFirstAnalysis = session.getMessageCount() == null || session.getMessageCount() == 0;

                // 4. 构建 Agent 并执行（支持上下文恢复）
                DatasourceCategory category = datasource.category();
                List<Hook> hooks = new java.util.ArrayList<>();
                if (command.enableWebSearch()) {
                    hooks.add(new SearchResultsHook());
                }
                ReActAgent agent = buildAgent(sessionId, command.modelConfigId(), command.userQuestion(), category, command.enableWebSearch(), hooks);

                Msg userMsg = Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("数据源ID: " + command.connectionId() + "\n用户问题: " + command.userQuestion())
                        .build();

                Msg result = agent.call(List.of(userMsg)).block();
                String responseText = result != null ? result.getTextContent() : "";

                sink.tryEmitNext(AnalysisEvent.analysis(responseText));
                sink.tryEmitNext(AnalysisEvent.done(new DataAnalysisResponse("", List.of(), "TABLE", "{}", responseText, true)));
                sink.tryEmitComplete();

                // 5. 持久化消息和更新会话元数据
                messagePersistenceService.persistUserMessage(sessionId, command.userQuestion());
                messagePersistenceService.persistAssistantMessage(sessionId, responseText);
                messagePersistenceService.updateSessionMetadata(sessionId);
                messagePersistenceService.generateAndSetTitle(sessionId, command.modelConfigId(), command.userQuestion(), isFirstAnalysis);
            } catch (Exception e) {
                log.error("数据分析失败", e);
                sink.tryEmitNext(AnalysisEvent.error(e.getMessage()));
                sink.tryEmitComplete();
            }
            return null;
        }).subscribe();

        return sink.asFlux();
    }

    /**
     * 同步执行数据分析（向后兼容，用于测试和简单集成）
     */
    public DataAnalysisResult execute(DataAnalysisCommand command) {
        long startTime = System.currentTimeMillis();

        // 1. 获取数据源
        DatasourceInfo datasource = datasourceGateway.findDatasource(Long.valueOf(command.connectionId()))
                .orElseThrow(() -> new DataAnalysisException("数据源不存在: " + command.connectionId()));

        // 2. 校验数据源启用状态
        if (!datasource.enabled()) {
            throw new DataAnalysisException("数据源未启用，请先启用数据源");
        }

        // 3. 提取 schema
        String schemaInfo = datasourceGateway.extractSchema(datasource.id());

        DataAnalysisQuery query = new DataAnalysisQuery(command.modelConfigId(), command.connectionId(), command.userQuestion());

        if (datasource.category() == DatasourceCategory.API) {
            // API 流程：直接获取数据，跳过 SQL 生成
            ApiConnectionInfo apiConfig = datasource.apiConfig();
            if (apiConfig == null || apiConfig.apiSchemaNames().isEmpty()) {
                throw new DataAnalysisException("API 数据源未配置 Schema");
            }
            String defaultSchemaName = apiConfig.apiSchemaNames().get(0);
            List<Map<String, Object>> queryData = datasourceGateway.executeApiQuery(datasource.id(), defaultSchemaName, 1000);
            DataAnalysisResult result = domainService.analyze(query, schemaInfo, "API: " + defaultSchemaName, queryData);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("API 数据分析完成，耗时: {}ms，用户问题: {}", elapsed, command.userQuestion());
            return result;
        }

        // JDBC 流程
        // 4. 生成 SQL
        String sqlDialect = resolveSqlDialect(datasource);
        String sql = domainService.generateSql(query.modelConfigId(), query.userQuestion(), schemaInfo, sqlDialect);

        // 5. 执行查询
        QueryExecutor executor = queryExecutors.stream()
                .filter(e -> e.supports(datasource))
                .findFirst()
                .orElseThrow(() -> new DataAnalysisException("不支持的数据源类型: " + datasource.category()));
        List<Map<String, Object>> queryData = executor.execute(datasource, sql);

        // 6. 生成图表和分析结论
        DataAnalysisResult result = domainService.analyze(query, schemaInfo, sql, queryData);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("数据分析完成，耗时: {}ms，用户问题: {}", elapsed, command.userQuestion());

        return result;
    }

    private String resolvePrompt(DatasourceCategory category) {
        return category == DatasourceCategory.API ? API_SYS_PROMPT : SYS_PROMPT;
    }

    private String resolveSqlDialect(DatasourceInfo datasource) {
        if (datasource.jdbcCategory() == null) return "MySQL";
        return switch (datasource.jdbcCategory()) {
            case MYSQL -> "MySQL";
            case CLICKHOUSE -> "ClickHouse";
        };
    }
}
