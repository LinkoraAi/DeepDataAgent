package com.linkroa.deepdataagent.agent.infrastructure.agent;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.infrastructure.client.ChatModelManager;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentMemoryProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ApiDataFetcherTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ChartGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.NL2SqlTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SchemaRetrieverTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SqlExecutorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.WebSearchTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HarnessAgent 工厂
 * <p>负责创建和配置 AgentScope 2.0 的 {@link HarnessAgent} 实例，
 * 集成 Compaction 等新特性。AgentScope 2.0 内部管理 Session 和 Workspace 生命周期。</p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>按需创建 HarnessAgent 实例，不缓存：Agent 使用完毕后由调用方关闭，
 *       会话上下文由 AgentScope 2.0 按 sessionId 自动关联恢复</li>
 *   <li>配置 Compaction（阈值设为模型上下文窗口的 80%）</li>
 *   <li>根据数据源类型条件化注册工具集和选择系统提示词</li>
 * </ul>
 */
@Component
public class HarnessAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentFactory.class);

    private final ChatModelManager chatModelManager;
    private final AgentProperties agentProperties;
    private final AgentMemoryProperties agentMemoryProperties;
    private final SchemaRetrieverTool schemaRetrieverTool;
    private final NL2SqlTool nl2SqlTool;
    private final SqlExecutorTool sqlExecutorTool;
    private final ApiDataFetcherTool apiDataFetcherTool;
    private final ChartGeneratorTool chartGeneratorTool;
    private final WebSearchTool webSearchTool;

    /** 系统提示词（JDBC 数据源） */
    private static final String SYS_PROMPT = """
            你是一个数据分析专家 Agent（DeepDataAnalyst）。
            你的任务是根据用户的自然语言问题，通过调用工具完成数据分析。

            ## 工作流程
            1. 调用 retrieve_schema 获取数据库表结构
               - 了解可用表和字段含义
               - 根据用户问题，可使用 keyword 参数过滤相关表
            2. 调用 generate_sql 将用户问题转换为 SQL
               - 确保 SQL 能正确回答用户问题
               - 必须将 retrieve_schema 返回的 schema 信息作为 schemaInfo 参数传入 generate_sql
               - 若 schemaInfo 为空，说明尚未调用 retrieve_schema，请先调用
            3. 调用 execute_sql 执行 SQL 查询
               - 如果执行失败，分析错误原因并修正 SQL
               - 最多重试 2 次，仍失败则报告错误
            4. 条件性调用 generate_chart 生成可视化图表
               - 仅在数据适合可视化且用户期望图表时调用
               - 判断依据：用户问题是否包含"图表/可视化/趋势/对比"等关键词
               - 数据特征：多维度对比、时间趋势、占比分布等适合图表展示
               - 如果数据为单一指标、纯文本结果或表格已足够清晰，可不调用
            5. 按问题复杂度直接输出最终回答（最终输出即答案本身）
               - 简单数据问答（如"某指标是多少""有多少条记录""表格已清晰呈现"）：直接给出简洁结论并附关键数据，不展开四段式，不调用图表
               - 复杂分析（涉及趋势、对比、归因、多维度）：输出结构化 Markdown 分析报告，包含分析概述、关键发现、详细分析、结论建议

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
            - 生成最终回答前，确保已完成数据查询
            - 图表生成（generate_chart）是条件性调用，不是必须调用
            - 使用中文回复用户
            - **禁止在工具调用之间输出叙述性文本**（如"我来帮您分析...""现在让我生成SQL...""让我补充查询..."等），这些内容会干扰最终回答的展示。所有分析内容应在最终回答时输出，不要在工具调用之间输出
            - 最终输出即答案本身，按问题复杂度选择简洁结论或四段式报告，禁止复述执行过程、禁止再对答案做额外概括
            - **联网搜索工具使用约束**：web_search 工具仅在用户明确启用"联网搜索"选项时才会被注册到可用工具集中。如果当前可用工具列表中没有 web_search 工具，说明用户未启用联网搜索功能，此时不得尝试调用该工具。请根据实际可用的工具列表来决定是否使用 web_search
            - **会话ID传递规则**：调用 generate_sql、generate_chart 工具时，必须将会话ID（从用户消息中的"会话ID"字段获取）作为 sessionId 参数传入
            """;

    /** 系统提示词（API 数据源） */
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
            3. 条件性调用 generate_chart 生成可视化图表
               - 仅在数据适合可视化且用户期望图表时调用
               - 判断依据：用户问题是否包含"图表/可视化/趋势/对比"等关键词
               - 数据特征：多维度对比、时间趋势、占比分布等适合图表展示
               - 如果数据为单一指标、纯文本结果或表格已足够清晰，可不调用
            4. 按问题复杂度直接输出最终回答（最终输出即答案本身）
               - 简单数据问答：直接给出简洁结论并附关键数据，不展开四段式，不调用图表
               - 复杂分析：输出结构化 Markdown 分析报告，包含分析概述、关键发现、详细分析、结论建议

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
            - 生成最终回答前，确保已完成数据获取
            - 图表生成（generate_chart）是条件性调用，不是必须调用
            - 使用中文回复用户
            - **禁止在工具调用之间输出叙述性文本**（如"我来帮您分析...""现在让我查询...""让我补充查询..."等），这些内容会干扰最终回答的展示。所有分析内容应在最终回答时输出，不要在工具调用之间输出
            - 最终输出即答案本身，按问题复杂度选择简洁结论或四段式报告，禁止复述执行过程、禁止再对答案做额外概括
            - **联网搜索工具使用约束**：web_search 工具仅在用户明确启用"联网搜索"选项时才会被注册到可用工具集中。如果当前可用工具列表中没有 web_search 工具，说明用户未启用联网搜索功能，此时不得尝试调用该工具。请根据实际可用的工具列表来决定是否使用 web_search
            - **会话ID传递规则**：调用 generate_chart 工具时，必须将会话ID（从用户消息中的"会话ID"字段获取）作为 sessionId 参数传入
            """;

    /**
     * 构造方法
     *
     * @param chatModelManager ChatModel 实例管理器
     * @param agentProperties Agent 配置
     * @param agentMemoryProperties Agent 记忆配置
     * @param schemaRetrieverTool Schema 检索工具
     * @param nl2SqlTool NL2SQL 工具
     * @param sqlExecutorTool SQL 执行工具
     * @param apiDataFetcherTool API 数据获取工具
     * @param chartGeneratorTool 图表生成工具
     * @param webSearchTool 网络搜索工具
     */
    public HarnessAgentFactory(
            ChatModelManager chatModelManager,
            AgentProperties agentProperties,
            AgentMemoryProperties agentMemoryProperties,
            SchemaRetrieverTool schemaRetrieverTool,
            NL2SqlTool nl2SqlTool,
            SqlExecutorTool sqlExecutorTool,
            ApiDataFetcherTool apiDataFetcherTool,
            ChartGeneratorTool chartGeneratorTool,
            WebSearchTool webSearchTool) {
        this.chatModelManager = chatModelManager;
        this.agentProperties = agentProperties;
        this.agentMemoryProperties = agentMemoryProperties;
        this.schemaRetrieverTool = schemaRetrieverTool;
        this.nl2SqlTool = nl2SqlTool;
        this.sqlExecutorTool = sqlExecutorTool;
        this.apiDataFetcherTool = apiDataFetcherTool;
        this.chartGeneratorTool = chartGeneratorTool;
        this.webSearchTool = webSearchTool;
    }

    /**
     * 创建 HarnessAgent 实例
     * <p>每次调用都创建新的 Agent，不缓存实例：分析请求结束后由调用方通过
     * {@link #closeAgent(HarnessAgent)} 关闭，会话上下文由 AgentScope 2.0
     * 框架按 sessionId 自动关联恢复。</p>
     *
     * @param sessionId 会话 ID
     * @param modelConfigId 模型配置 ID
     * @param category 数据源类型
     * @param enableWebSearch 是否启用网络搜索工具
     * @param extraMiddlewares 请求特定的中间件列表（如 SearchResultsMiddleware）
     * @return HarnessAgent 实例
     */
    public HarnessAgent getOrCreateAgent(
            String sessionId,
            Long modelConfigId,
            DatasourceCategory category,
            boolean enableWebSearch,
            List<MiddlewareBase> extraMiddlewares) {

        return createAgent(sessionId, modelConfigId, category, enableWebSearch, extraMiddlewares);
    }

    /**
     * 创建 HarnessAgent 实例
     * <p>集成 Compaction 等特性。Session 和 Workspace 由 AgentScope 2.0 框架内部管理。</p>
     *
     * @param sessionId 会话 ID
     * @param modelConfigId 模型配置 ID
     * @param category 数据源类型
     * @param enableWebSearch 是否启用网络搜索工具
     * @param extraMiddlewares 请求特定的中间件列表
     * @return HarnessAgent 实例
     */
    private HarnessAgent createAgent(
            String sessionId,
            Long modelConfigId,
            DatasourceCategory category,
            boolean enableWebSearch,
            List<MiddlewareBase> extraMiddlewares) {

        // 1. 获取 ChatModel
        ChatModelBase chatModel = chatModelManager.getChatModel(modelConfigId);

        // 2. 构建工具集
        Toolkit toolkit = buildToolkit(category, enableWebSearch);

        // 3. 选择系统提示词
        String sysPrompt = resolvePrompt(category);

        // 4. 构建中间件列表
        List<MiddlewareBase> middlewares = new ArrayList<>();
        if (extraMiddlewares != null) {
            middlewares.addAll(extraMiddlewares);
        }

        // 5. 配置 Compaction（阈值设为模型上下文窗口的 80%）
        CompactionConfig compactionConfig = CompactionConfig.builder()
                .triggerMessages(30)     // 30 条消息触发压缩
                .keepMessages(10)        // 压缩后保留最近 10 条
                .build();

        // 6. 构建 HarnessAgent
        // 注意：Session 和 Workspace 由 AgentScope 2.0 框架内部管理，
        // 通过 agentId 和 sessionId 自动关联
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("DeepDataAnalyst")
                .description("数据分析专家 Agent，能够通过工具调用完成 SQL 生成、执行和数据分析")
                .sysPrompt(sysPrompt)
                .model(chatModel)
                .compaction(compactionConfig)
                .toolkit(toolkit)
                .middlewares(middlewares)
                .maxIters(10);

        // 7. 启用框架原生记忆（自动落盘 + 长期记忆整合）
        if (agentMemoryProperties.isEnabled()) {
            MemoryConfig memoryConfig = buildMemoryConfig(chatModel);
            builder.memory(memoryConfig)
                    .workspace(ensureMemoryWorkspace())
                    .disableMemoryTools();
            log.info("HarnessAgentFactory: enabled framework memory for sessionId={}, workspace={}",
                    sessionId, agentMemoryProperties.getWorkspace());
        }

        HarnessAgent agent = builder.build();

        log.info("HarnessAgentFactory: created HarnessAgent for sessionId={}", sessionId);
        return agent;
    }

    /**
     * 构建框架记忆配置。
     * <p>复用会话模型作为记忆整合模型，按配置设置整合的 token 上限、最小间隔与落盘触发策略。</p>
     *
     * @param chatModel 会话模型，作为记忆整合模型
     * @return MemoryConfig 实例
     */
    private MemoryConfig buildMemoryConfig(ChatModelBase chatModel) {
        return MemoryConfig.builder()
                .model(chatModel)
                .consolidationMaxTokens(agentMemoryProperties.getConsolidationMaxTokens())
                .consolidationMinGap(agentMemoryProperties.getConsolidationMinGap())
                .flushTrigger(resolveFlushTrigger())
                .build();
    }

    /**
     * 根据配置字符串解析记忆落盘触发策略。
     * <p>支持 always / never / throttled，默认 throttled（限流，避免每次对话都触发额外 LLM 调用）。</p>
     *
     * @return FlushTrigger 实例
     */
    private MemoryConfig.FlushTrigger resolveFlushTrigger() {
        String trigger = agentMemoryProperties.getFlushTrigger();
        if (trigger == null) {
            return MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5));
        }
        return switch (trigger.trim().toLowerCase()) {
            case "always" -> MemoryConfig.FlushTrigger.always();
            case "never" -> MemoryConfig.FlushTrigger.never();
            default -> MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5));
        };
    }

    /**
     * 确保记忆工作目录存在，并返回其路径。
     * <p>工作目录不存在时创建，避免框架写入记忆文件时失败。</p>
     *
     * @return 记忆工作目录 Path
     */
    private Path ensureMemoryWorkspace() {
        Path workspace = Path.of(agentMemoryProperties.getWorkspace());
        try {
            Files.createDirectories(workspace);
        } catch (Exception e) {
            log.warn("HarnessAgentFactory: failed to create memory workspace={}, error={}",
                    workspace, e.getMessage());
        }
        return workspace;
    }

    /**
     * 构建工具集
     * <p>根据数据源类型条件化注册工具集。</p>
     *
     * @param category 数据源类型
     * @param enableWebSearch 是否启用网络搜索工具
     * @return Toolkit 实例
     */
    private Toolkit buildToolkit(DatasourceCategory category, boolean enableWebSearch) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(schemaRetrieverTool);

        if (category == DatasourceCategory.JDBC) {
            toolkit.registerTool(nl2SqlTool);
            toolkit.registerTool(sqlExecutorTool);
        } else {
            toolkit.registerTool(apiDataFetcherTool);
        }

        toolkit.registerTool(chartGeneratorTool);

        // 条件化注册网络搜索工具
        if (enableWebSearch) {
            toolkit.registerTool(webSearchTool);
        }

        return toolkit;
    }

    /**
     * 解析系统提示词
     * <p>根据数据源类型选择对应的系统提示词。</p>
     *
     * @param category 数据源类型
     * @return 系统提示词
     */
    private String resolvePrompt(DatasourceCategory category) {
        if (category == DatasourceCategory.API) {
            String apiPrompt = agentProperties.getApiSysPrompt();
            return (apiPrompt != null && !apiPrompt.isBlank()) ? apiPrompt : API_SYS_PROMPT;
        }
        String sysPrompt = agentProperties.getSysPrompt();
        return (sysPrompt != null && !sysPrompt.isBlank()) ? sysPrompt : SYS_PROMPT;
    }

    /**
     * 关闭 HarnessAgent 实例
     * <p>关闭 Agent 并释放其持有的资源（模型客户端、工具集、记忆等）。
     * 工厂不缓存 Agent，所有实例均需在使用完毕后显式关闭。</p>
     *
     * @param agent HarnessAgent 实例（可能为 null）
     */
    public void closeAgent(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        agent.close();
        log.info("HarnessAgentFactory: closed HarnessAgent");
    }
}