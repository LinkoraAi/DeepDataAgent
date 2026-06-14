package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.controller.response.TestConnectionResult;
import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.LlmModelConfigMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * LLM 客户端
 * <p>封装 AgentScope ChatModel，根据 modelConfigId 动态获取对应的 ChatModel 实例，
 * 提供 prompt 构建和调用能力，支持 Text-to-SQL、分析结论生成、图表配置生成等场景。</p>
 */
@Component
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final LlmModelConfigMapper configMapper;
    private final PasswordEncryptionUtil encryptionUtil;
    private final Map<Long, ChatModelBase> chatModelCache = new ConcurrentHashMap<>();

    public LLMClient(LlmModelConfigMapper configMapper,
                     PasswordEncryptionUtil encryptionUtil) {
        this.configMapper = configMapper;
        this.encryptionUtil = encryptionUtil;
    }

    /**
     * 根据配置 ID 获取 ChatModel 实例（带缓存）
     * <p>公开方法，供 Agent 构建和工具类使用。</p>
     */
    public ChatModelBase getChatModel(Long modelConfigId) {
        return chatModelCache.computeIfAbsent(modelConfigId, id -> {
            LlmModelConfigEntity config = configMapper.selectByIdAndNotDeleted(id);
            if (config == null) {
                throw new DataAnalysisException("模型配置不存在: " + id);
            }
            String apiKey = encryptionUtil.decrypt(config.getApiKey());
            return createChatModel(config, apiKey);
        });
    }

    private ChatModelBase createChatModel(LlmModelConfigEntity config, String apiKey) {
        double temperature = config.getTemperature() != null ? config.getTemperature() : 0.1;
        GenerateOptions options = GenerateOptions.builder().temperature(temperature).build();

        return switch (config.getProvider().toLowerCase()) {
            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(config.getModelName())
                    .defaultOptions(options)
                    .build();
            case "deepseek", "openai" -> {
                var builder = OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModelName())
                        .generateOptions(options);
                if (StringUtils.hasText(config.getBaseUrl())) {
                    builder.baseUrl(config.getBaseUrl());
                }
                yield builder.build();
            }
            default -> throw new DataAnalysisException("不支持的 LLM 提供商: " + config.getProvider());
        };
    }

    /**
     * 清除指定模型的缓存（配置修改/删除时调用）
     */
    public void evictCache(Long modelConfigId) {
        chatModelCache.remove(modelConfigId);
    }

    /**
     * 创建临时 ChatModel（不缓存，用于连接测试）
     *
     * @param config 模型配置实体
     * @param apiKey 解密后的 API Key
     * @return 临时 ChatModel 实例
     */
    public ChatModelBase createTempChatModel(LlmModelConfigEntity config, String apiKey) {
        return createChatModel(config, apiKey);
    }

    /**
     * 测试模型连接
     * <p>创建临时 ChatModel 并发送测试消息，测量响应时间</p>
     *
     * @param modelConfigId 模型配置 ID
     * @return 连接测试结果
     */
    public TestConnectionResult testConnection(Long modelConfigId) {
        long startTime = System.currentTimeMillis();
        try {
            LlmModelConfigEntity config = configMapper.selectByIdAndNotDeleted(modelConfigId);
            if (config == null) {
                return new TestConnectionResult(false, "模型配置不存在", 0L);
            }
            String apiKey = encryptionUtil.decrypt(config.getApiKey());
            ChatModelBase model = createTempChatModel(config, apiKey);

            List<Msg> messages = List.of(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("你好，请回复OK")
                            .build()
            );

            List<ChatResponse> responses = model.stream(messages, null, null)
                    .timeout(Duration.ofSeconds(10))
                    .collectList().block();

            long responseTime = System.currentTimeMillis() - startTime;

            if (responses == null || responses.isEmpty()) {
                return new TestConnectionResult(false, "模型未响应", responseTime);
            }

            return new TestConnectionResult(true, "连接成功，模型可用", responseTime);
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            String message = mapConnectionError(e);
            return new TestConnectionResult(false, message, responseTime);
        }
    }

    /**
     * 将异常信息映射为用户友好的错误提示
     */
    private String mapConnectionError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api")) {
            return "API Key 无效，请检查后重新输入";
        }
        if (msg.contains("404") || msg.contains("model not found")) {
            return "模型名称不正确，请检查配置";
        }
        if (msg.contains("timeout") || e instanceof java.util.concurrent.TimeoutException) {
            return "请求超时，请检查网络连接";
        }
        return "连接失败: " + LogMasker.mask(e.getMessage());
    }

    /**
     * 生成 SQL 查询
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     */
    public String generateSQL(Long modelConfigId, String userQuestion, String schemaInfo, String sqlDialect) {
        String systemPrompt = """
                你是一个精通 %s 的 SQL 专家。根据用户问题和数据库 schema，生成对应的 SQL 查询。

                ## 规则
                1. 只生成 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP/ALTER 等操作
                2. 直接输出纯 SQL 语句，不要添加任何解释、注释或 markdown 代码块标记
                3. 使用 %s 语法
                4. 表名和字段名使用反引号包裹（MySQL）或双引号（ClickHouse）
                5. 对于聚合查询，使用有意义的别名（AS）
                6. 考虑 NULL 值处理，必要时使用 COALESCE 或 IFNULL
                7. 如果涉及时间范围，使用 BETWEEN 或 >= <= 比较符

                ## 示例
                用户问题：查询每个部门的员工数量
                输出：SELECT department, COUNT(*) AS employee_count FROM employees GROUP BY department ORDER BY employee_count DESC

                用户问题：最近一个月的销售额趋势
                输出：SELECT DATE(order_date) AS date, SUM(amount) AS total_sales FROM orders WHERE order_date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH) GROUP BY DATE(order_date) ORDER BY date
                """.formatted(sqlDialect, sqlDialect);

        String userPrompt = """
                数据库 schema：
                %s

                用户问题：%s
                请生成对应的 SQL 查询：
                """.formatted(schemaInfo, userQuestion);

        return callLLM(modelConfigId, systemPrompt, userPrompt);
    }

    /**
     * 生成分析报告
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param sqlQuery      执行的 SQL 语句
     * @param dataSummary   数据统计摘要
     * @param chartSummary  图表描述或配置
     */
    public String generateAnalysis(Long modelConfigId, String userQuestion, String sqlQuery, String dataSummary, String chartSummary) {
        String systemPrompt = """
                你是一个资深数据分析师，擅长从数据中提取洞察并生成结构化分析报告。

                ## 报告结构要求
                请生成一份完整的分析报告，必须包含以下章节：

                ### 一、分析概述
                - 用一句话总结分析的核心结论
                - 说明分析的数据范围

                ### 二、关键发现
                - 使用 Markdown 表格呈现核心指标
                - 对每个关键指标进行解读
                - 使用**加粗**突出重要数据

                ### 三、详细分析
                - 趋势分析：数据随时间的变化规律
                - 对比分析：不同维度的数据对比
                - 异常识别：数据中的异常值或特殊现象
                - 结合图表信息进行解读

                ### 四、结论与建议
                - 基于数据得出明确结论
                - 给出 2-3 条可操作的建议
                - 标注潜在风险或需关注的事项

                ## 格式要求
                1. 严格使用 Markdown 格式
                2. 使用二级标题(##)分隔主要章节
                3. 关键数据使用表格呈现
                4. 数据精确到原始数据的精度，不要编造数据
                5. 语言简洁专业，避免空话套话
                6. 如果数据不足以支撑某章节，可简要说明或省略该章节
                """;

        String userPrompt = """
                ## 用户问题
                %s

                ## 执行的 SQL
                %s

                ## 数据统计摘要
                %s

                ## 可视化图表信息
                %s

                请根据以上信息生成完整的分析报告。
                """.formatted(userQuestion, sqlQuery, dataSummary, chartSummary);

        return callLLM(modelConfigId, systemPrompt, userPrompt);
    }

    /**
     * 生成图表配置（ECharts JSON）
     */
    public String generateChartConfig(Long modelConfigId, String dataDescription, String userQuestion) {
        String systemPrompt = """
                你是一个数据可视化专家。根据数据结构和用户意图，生成 ECharts 配置 JSON。
                要求：
                1. 只输出 JSON，不要添加其他内容或 markdown 代码块标记
                2. 根据数据类型自动选择最合适的图表（时间趋势用 line，分类对比用 bar，占比用 pie）
                3. JSON 格式必须符合 ECharts 规范
                """;

        String userPrompt = """
                数据结构：%s
                用户意图：%s
                请生成 ECharts 配置 JSON：
                """.formatted(dataDescription, userQuestion);

        return callLLM(modelConfigId, systemPrompt, userPrompt);
    }

    /**
     * 生成会话标题
     * <p>调用 LLM 根据用户问题生成简洁的会话标题。如果 LLM 调用失败，返回 null，由调用方使用降级标题。</p>
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @return 生成的标题，失败时返回 null
     */
    public String generateTitle(Long modelConfigId, String userQuestion) {
        String systemPrompt = """
                你是一个标题生成专家。根据用户的问题，生成一个简洁的会话标题。
                要求：
                1. 标题不超过 15 个字
                2. 使用中文
                3. 只输出标题，不要添加任何解释或标记
                """;

        String userPrompt = "用户问题：%s\n请生成会话标题：".formatted(userQuestion);

        try {
            return callLLM(modelConfigId, systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("生成标题失败，将使用降级标题: {}", LogMasker.mask(e.getMessage()));
            return null;
        }
    }

    private String callLLM(Long modelConfigId, String systemPrompt, String userPrompt) {
        ChatModelBase model = getChatModel(modelConfigId);

        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(systemPrompt)
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userPrompt)
                        .build()
        );

        try {
            List<ChatResponse> responses = model.stream(messages, null, null)
                    .collectList().block();
            if (responses == null || responses.isEmpty()) {
                throw new DataAnalysisException("LLM 返回空响应");
            }
            String result = responses.stream()
                    .flatMap(r -> r.getContent().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining())
                    .strip();
            // 清理可能的 markdown 代码块标记
            if (result.startsWith("```")) {
                result = result.replaceAll("^```[a-zA-Z]*\\n?", "")
                               .replaceAll("\\n?```$", "")
                               .strip();
            }
            return result;
        } catch (DataAnalysisException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", LogMasker.mask(e.getMessage()), e);
            throw new DataAnalysisException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
