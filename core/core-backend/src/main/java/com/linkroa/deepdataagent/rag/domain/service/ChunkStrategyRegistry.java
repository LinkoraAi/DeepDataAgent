package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分块策略注册表。
 * <p>构造期注入全部策略 bean，以策略方法名建立索引；按 7 种分块模式映射到方法名并解析出
 * 策略实例。非法/未知模式串的拒绝由保存侧校验承担；本注册表保留「未知模式 / 未知方法名 /
 * 空输入一律回退 {@code general}」的防御路径，且回退<b>必须输出 WARN</b>，不再静默。</p>
 */
@Component
public class ChunkStrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyRegistry.class);

    /** 兜底策略方法名 {@code general}。 */
    public static final String DEFAULT_METHOD = GeneralChunkStrategy.METHOD;

    /** 分块模式 → 策略方法名映射（7 模式全量对齐）。 */
    private static final Map<DocumentChunkMode, String> MODE_TO_METHOD = Map.of(
            DocumentChunkMode.GENERAL, "general",
            DocumentChunkMode.QA, "qa",
            DocumentChunkMode.BOOK, "book",
            DocumentChunkMode.LAWS, "laws",
            DocumentChunkMode.TABLE, "table",
            DocumentChunkMode.PRESENTATION, "presentation",
            DocumentChunkMode.ONE, "one"
    );

    /** 已注册策略索引（方法名 → 策略实例）。 */
    private final Map<String, ChunkStrategy> strategies;

    /**
     * 构造注册表：注入全部策略 bean 并建立方法名索引。
     * <p>同一方法名重复注册视为配置错误，构造期立即抛异常（fail-fast）。</p>
     *
     * @param strategyList 全部策略 bean（Spring 自动注入，顺序不影响路由）
     */
    public ChunkStrategyRegistry(List<ChunkStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ChunkStrategy::method, Function.identity()));
    }

    /**
     * 按分块模式解析策略：先走模式映射，命中失败统一回退 general（WARN 留痕）。
     *
     * @param mode 分块模式，可为 null（null 与未知模式等价，均回退 general）
     * @return 该模式对应的策略实例，恒非 null
     */
    public ChunkStrategy resolve(DocumentChunkMode mode) {
        if (ObjectUtils.isEmpty(mode)) {
            return fallback(StringUtils.EMPTY);
        }
        return resolve(MODE_TO_METHOD.get(mode));
    }

    /**
     * 按方法名解析策略：未知方法名（含 null / 空白）回退 general 并输出 WARN。
     *
     * @param method 策略方法名，可为 null 或空白
     * @return 匹配的策略实例，恒非 null
     */
    public ChunkStrategy resolve(String method) {
        ChunkStrategy strategy = StringUtils.isBlank(method) ? null : strategies.get(method);
        if (ObjectUtils.isNotEmpty(strategy)) {
            return strategy;
        }
        return fallback(method);
    }

    /**
     * 防御回退出口：返回 general 策略并输出 WARN（非法方法名经保存侧拒绝后理论不可达，
     * 出现即说明配置面或注册装配存在缺陷，必须留痕不得静默）。
     *
     * @param method 触发回退的方法名（空白表示未提供），仅用于日志定位
     * @return general 策略实例
     */
    private ChunkStrategy fallback(String method) {
        log.warn("分块策略方法名未注册, method={}, 回退 {} 策略", method, DEFAULT_METHOD);
        return strategies.get(DEFAULT_METHOD);
    }
}