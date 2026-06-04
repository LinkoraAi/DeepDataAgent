package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.application.service.ModelConfigApplicationService;
import com.linkroa.deepdataagent.agent.controller.response.TestConnectionResult;
import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
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

    private final ModelConfigApplicationService modelConfigService;
    private final Map<Long, ChatModelBase> chatModelCache = new ConcurrentHashMap<>();

    public LLMClient(ModelConfigApplicationService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    /**
     * 根据配置 ID 获取 ChatModel 实例（带缓存）
     */
    private ChatModelBase getChatModel(Long modelConfigId) {
        return chatModelCache.computeIfAbsent(modelConfigId, id -> {
            LlmModelConfigEntity config = modelConfigService.getConfigById(id);
            if (config == null) {
                throw new DataAnalysisException("模型配置不存在: " + id);
            }
            String apiKey = modelConfigService.decryptApiKey(config.getApiKey());
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
            LlmModelConfigEntity config = modelConfigService.getConfigById(modelConfigId);
            if (config == null) {
                return new TestConnectionResult(false, "模型配置不存在", 0L);
            }
            String apiKey = modelConfigService.decryptApiKey(config.getApiKey());
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
                你是一个 SQL 专家。根据用户问题和数据库 schema，生成对应的 SQL 查询。
                要求：
                1. 只生成 SELECT 语句，不允许其他操作
                2. 直接输出 SQL，不要添加任何解释或 markdown 代码块标记
                3. 使用 %s 语法
                """.formatted(sqlDialect);

        String userPrompt = """
                数据库 schema：
                %s

                用户问题：%s
                请生成对应的 SQL 查询：
                """.formatted(schemaInfo, userQuestion);

        return callLLM(modelConfigId, systemPrompt, userPrompt);
    }

    /**
     * 生成分析结论
     */
    public String generateAnalysis(Long modelConfigId, String userQuestion, String queryResult, String chartSummary) {
        String systemPrompt = """
                你是一个数据分析师。根据用户问题、查询结果和图表信息，生成分析结论。
                要求：
                1. 使用 Markdown 格式
                2. 包含关键发现、趋势分析、建议
                3. 语言简洁专业
                4. 不超过 300 字
                """;

        String userPrompt = """
                用户问题：%s
                查询结果（JSON）：%s
                图表：%s
                请生成分析结论：
                """.formatted(userQuestion, queryResult, chartSummary);

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
