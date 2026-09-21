package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 描述摘要器（上游三段触发形态 + 分级 Map-Reduce，实体与关系统一入口）。
 * <p>分支策略（token 口径全部走 {@link TokenCounter} 真实 encode，触发语义对齐上游
 * LightRAG {@code summarize_descriptions}）：</p>
 * <ol>
 *   <li>空描述列表：返回 {@code null}，由调用方兜底（实体 {@code Entity {name}}、关系合并时已抛错）；</li>
 *   <li>单条描述：原样透传，不产生任何写放大与远程调用；</li>
 *   <li>总 token 不超过 {@code summary_context_size}（拼接上限）时——
 *       条数小于 {@code force_llm_summary_on_merge} 且总 token 小于 {@code summary_max_tokens}
 *       （输出上限）才直接换行 join；否则发起<b>单次全量 LLM 摘要</b>（不分批）；</li>
 *   <li>仅当总 token 超拼接上限才进 Map-Reduce 分级摘要——条数超 {@code GROUP_MERGE_HI}
 *       先切超级批逐批摘要，再按「组 token ≤ {@code summary_max_tokens} 且组条数 ≤
 *       {@code GROUP_MERGE_LO}」贪心分组逐组摘要，结果递归归并直至收敛
 *       （贪心切批以输出上限为组内总额约束，比上游按拼接上限切批更保守，保留本项目形态）。</li>
 * </ol>
 * <p>终止保证：贪心分组数不小于待摘要条数时（分组零压缩）兜底为单次 LLM 摘要，
 * 递归输入规模在每轮严格收缩后必然收敛。所有远程调用发生在数据库事务之外，
 * 经 {@link LlmClient} 端口命中 {@code llm_cache}（重放幂等，幂等规则）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DescriptionSummarizer {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DescriptionSummarizer.class);

    /** 描述摘要模板名（第 8 项，集中定义见 PromptTemplates） */
    static final String SUMMARY_TEMPLATE = PromptTemplates.SUMMARIZE_ENTITY_DESCRIPTIONS;

    /** Map-Reduce 单组最大描述条数（LightRAG GROUP_MERGE_LO 同名口径） */
    static final int GROUP_MERGE_LO = 32;

    /** 超级批切分的单批最大描述条数（LightRAG GROUP_MERGE_HI 同名口径） */
    static final int GROUP_MERGE_HI = 1024;

    /** Token 计数器（真实 encode） */
    private final TokenCounter tokenCounter;

    /** LLM 对话端口（内嵌缓存回放） */
    private final LlmClient llmClient;

    /** JSON 序列化器（描述列表逐条转义为单行 JSON 字符串） */
    private final ObjectMapper objectMapper;

    /**
     * 构造摘要器。
     *
     * @param tokenCounter   Token 计数器
     * @param llmClient      LLM 对话端口
     * @param objectMapper   JSON 序列化器
     */
    public DescriptionSummarizer(TokenCounter tokenCounter,
                                 LlmClient llmClient, ObjectMapper objectMapper) {
        this.tokenCounter = tokenCounter;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 摘要一组描述（实体或关系的描述原文累积）。
     * <p>上游三段触发形态：单条透传 →（总 token ≤ 拼接上限时：条数 &lt; force 且
     * 总 token &lt; 输出上限才直接拼接，否则单次全量 LLM 摘要）→ 仅总 token 超拼接上限
     * 进 Map-Reduce 分级。</p>
     *
     * @param ctx          图合并上下文（提供模型引用、语言、限额与取消检查）
     * @param itemName     实体/关系名称（注入模板 {@code {description_name}}）
     * @param itemType     实体类型/关系关键词标签（注入模板 {@code {description_type}}）
     * @param descriptions 描述原文列表（已精确去重）
     * @return 收敛后的单一描述；入参为空列表时返回 {@code null}（调用方兜底）
     * @throws IllegalStateException 调用已取消或远程摘要持续失败
     */
    public String summarize(GraphMergeContext ctx, String itemName, String itemType,
                            List<String> descriptions) {
        if (ObjectUtils.isEmpty(descriptions)) {
            return null;
        }
        if (descriptions.size() == 1) {
            return descriptions.get(0);
        }
        GraphMergeParams params = ctx.params();
        int totalTokens = countTotal(descriptions);
        if (totalTokens <= params.summaryContextSize()) {
            if (descriptions.size() < params.forceLlmSummaryOnMerge()
                    && totalTokens < params.summaryMaxTokens()) {
                return String.join("\n", descriptions);
            }
            return invokeLlm(ctx, itemName, itemType, descriptions);
        }
        return mapReduce(ctx, itemName, itemType, descriptions);
    }

    /**
     * Map-Reduce 摘要：超级批切分 → 贪心分组 Map → 结果递归 Reduce。
     *
     * @param ctx          图合并上下文
     * @param itemName     名称
     * @param itemType     类型标签
     * @param descriptions 描述列表（规模已超单次摘要上限）
     * @return 收敛后的单一描述
     */
    private String mapReduce(GraphMergeContext ctx, String itemName, String itemType,
                             List<String> descriptions) {
        List<String> units = new ArrayList<>(descriptions);
        if (units.size() > GROUP_MERGE_HI) {
            List<String> superBatchResults = new ArrayList<>();
            for (int start = 0; start < units.size(); start += GROUP_MERGE_HI) {
                int end = Math.min(start + GROUP_MERGE_HI, units.size());
                superBatchResults.add(invokeLlm(ctx, itemName, itemType, units.subList(start, end)));
            }
            units = superBatchResults;
        }
        List<List<String>> groups = greedyGroup(ctx, units);
        // 终止保护：分组数未收缩（零压缩）时兜底单次摘要，避免递归不收敛
        if (groups.size() >= units.size()) {
            return invokeLlm(ctx, itemName, itemType, units);
        }
        List<String> partials = new ArrayList<>();
        for (List<String> group : groups) {
            if (group.size() == 1) {
                partials.add(group.get(0));
            } else {
                partials.add(invokeLlm(ctx, itemName, itemType, group));
            }
        }
        if (partials.size() == 1) {
            return partials.get(0);
        }
        return summarize(ctx, itemName, itemType, partials);
    }

    /**
     * 贪心分组：顺序累积，组内总 token 超 {@code summary_max_tokens}
     * 或组条数达 {@code GROUP_MERGE_LO} 时切组（单条超限的单元独立成组）。
     *
     * @param ctx   图合并上下文
     * @param units 待分组单元
     * @return 分组结果（保持输入顺序）
     */
    private List<List<String>> greedyGroup(GraphMergeContext ctx, List<String> units) {
        int maxGroupTokens = ctx.params().summaryMaxTokens();
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String unit : units) {
            int unitTokens = tokenCounter.count(unit);
            if (ObjectUtils.isNotEmpty(current)
                    && (currentTokens + unitTokens > maxGroupTokens || current.size() >= GROUP_MERGE_LO)) {
                groups.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(unit);
            currentTokens += unitTokens;
        }
        if (ObjectUtils.isNotEmpty(current)) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 单次 LLM 摘要调用（模板 {@code summarize_entity_descriptions}，描述逐条转义为单行 JSON）。
     *
     * @param ctx          图合并上下文
     * @param itemName     名称
     * @param itemType     类型标签
     * @param descriptions 本次批摘要的描述
     * @return 摘要文本；模型返回空时回退为换行拼接（不丢信息）
     * @throws IllegalStateException 调用已取消或 JSON 序列化失败
     */
    private String invokeLlm(GraphMergeContext ctx, String itemName, String itemType,
                             List<String> descriptions) {
        if (ctx.isCancelled()) {
            throw new IllegalStateException("摄入已取消，中断描述摘要");
        }
        String prompt = PromptCatalog.render(SUMMARY_TEMPLATE, ctx.language(), Map.of(
                "summary_length", ctx.params().summaryMaxTokens(),
                // {language} 一律注入知识库语言全名（兼容历史裸码），
                // 不再映射「中文 / English」标签
                "language", StringUtils.trim(ctx.language()),
                "description_type", StringUtils.defaultString(itemType),
                "description_name", StringUtils.defaultString(itemName),
                "description_list", toJsonLines(descriptions)));
        LlmChatResult result = llmClient.chat(new LlmChatRequest(
                ctx.kbId(), ctx.summaryModelProfileId(), null, prompt, null, CacheType.ENTITY_DESC));
        if (result.cacheHit() && ObjectUtils.isNotEmpty(ctx.llmCacheHits())) {
            ctx.llmCacheHits().incrementAndGet();
        }
        String summary = StringUtils.trimToNull(result.text());
        if (StringUtils.isBlank(summary)) {
            log.warn("描述摘要返回空，回退为换行拼接：name=[{}]", itemName);
            return String.join("\n", descriptions);
        }
        return summary;
    }

    /**
     * 将描述列表转义为多行 JSON 字符串文本（每条独立一行，符合模板输入格式约定）。
     *
     * @param descriptions 描述列表
     * @return 逐行 JSON 字符串拼接文本
     */
    private String toJsonLines(List<String> descriptions) {
        StringBuilder builder = new StringBuilder();
        for (String description : descriptions) {
            try {
                builder.append(objectMapper.writeValueAsString(description)).append('\n');
            } catch (JacksonException e) {
                throw new IllegalStateException("描述列表 JSON 序列化失败", e);
            }
        }
        return builder.toString();
    }

    /**
     * 统计描述列表总 token 数。
     *
     * @param descriptions 描述列表
     * @return 总 token 数
     */
    private int countTotal(List<String> descriptions) {
        int total = 0;
        for (String description : descriptions) {
            total += tokenCounter.count(description);
        }
        return total;
    }
}
