package com.linkroa.deepdataagent.rag.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ChunkStrategyRegistry} 单元测试。
 * <p>覆盖 7 种分块模式到策略方法名的全量路由、null / 空白 / 未知模式回退 general 的防御路径
 * （回退必须输出 WARN，不再静默）以及同方法名重复注册的构造期 fail-fast。
 * 不使用 Mockito：全部注入真实无参策略实例，
 * 与生产注册表行为逐字节一致；WARN 断言用 Logback ListAppender 临时挂载捕获日志事件。</p>
 */
class ChunkStrategyRegistryTest {

    /** 被测对象：注入全部 7 个真实策略实例（与生产 bean 集合等价）。 */
    private final ChunkStrategyRegistry registry = new ChunkStrategyRegistry(strategies());

    /**
     * 场景：7 种分块模式逐一解析（全量映射）。
     * 预期：GENERAL→general、QA→qa、BOOK→book、LAWS→laws、TABLE→table、
     * PRESENTATION→presentation、ONE→one 全部命中且解析结果恒非 null。
     */
    @Test
    void should_routeAllSevenModes_when_resolve_given_knownModes() {
        // given / when
        ChunkStrategy general = registry.resolve(DocumentChunkMode.GENERAL);
        ChunkStrategy qa = registry.resolve(DocumentChunkMode.QA);
        ChunkStrategy book = registry.resolve(DocumentChunkMode.BOOK);
        ChunkStrategy laws = registry.resolve(DocumentChunkMode.LAWS);
        ChunkStrategy table = registry.resolve(DocumentChunkMode.TABLE);
        ChunkStrategy presentation = registry.resolve(DocumentChunkMode.PRESENTATION);
        ChunkStrategy one = registry.resolve(DocumentChunkMode.ONE);

        // then
        assertEquals("general", general.method());
        assertEquals("qa", qa.method());
        assertEquals("book", book.method());
        assertEquals("laws", laws.method());
        assertEquals("table", table.method());
        assertEquals("presentation", presentation.method());
        assertEquals("one", one.method());
    }

    /**
     * 场景：null 模式解析。
     * 预期：回退 general（防御兜底），恒非 null。
     */
    @Test
    void should_fallbackToGeneral_when_resolve_given_nullMode() {
        // given / when
        ChunkStrategy strategy = registry.resolve((DocumentChunkMode) null);

        // then
        assertNotNull(strategy, "兜底结果恒非 null");
        assertEquals("general", strategy.method());
    }

    /**
     * 场景：未知方法名 / null 方法名 / 空白方法名解析（防御条款）。
     * 预期：全部回退 general 策略，且每次回退各输出一行 WARN，不再静默。
     */
    @Test
    void should_fallbackToGeneralWithWarn_when_resolve_given_unknownOrBlankMethod() {
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ChunkStrategyRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
        ChunkStrategy unknown;
        ChunkStrategy nullMethod;
        ChunkStrategy blankMethod;
        try {
            // when
            unknown = registry.resolve("unknown-method");
            nullMethod = registry.resolve((String) null);
            blankMethod = registry.resolve("  ");
        } finally {
            registryLogger.detachAppender(appender);
        }

        // then：三条回退均命中 general 策略
        assertEquals("general", unknown.method());
        assertEquals("general", nullMethod.method());
        assertEquals("general", blankMethod.method());
        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> Level.WARN.equals(event.getLevel()))
                .filter(event -> event.getFormattedMessage().contains("回退 general 策略"))
                .toList();
        assertEquals(3, warnings.size(), "三次非法方法名回退必须各输出一行 WARN");
    }

    /**
     * 场景：未知分块模式（如扩展引入新枚举值但映射缺失）。
     * 预期：indirectly 经 MODE_TO_METHOD.get 返回 null 后回退 general（通过 null 模式等价路径验证）。
     */
    @Test
    void should_returnSameFallbackInstance_when_resolve_given_missingModeMapping() {
        // given / when
        ChunkStrategy viaNull = registry.resolve((DocumentChunkMode) null);
        ChunkStrategy viaUnknown = registry.resolve("no-such-method");

        // then
        assertSame(viaNull, viaUnknown, "不同兜底入口必须解析到同一 general 实例");
    }

    /**
     * 场景：同一方法名注册两个策略（配置错误）。
     * 预期：构造注册表时立即抛出 {@link IllegalStateException}（fail-fast），
     * 避免路由期出现不确定行为。
     */
    @Test
    void should_throwException_when_constructRegistry_given_duplicateMethodNames() {
        // given（两个匿名策略返回相同方法名）
        ChunkStrategy first = dummyStrategy("dup");
        ChunkStrategy second = dummyStrategy("dup");
        List<ChunkStrategy> duplicated = List.of(first, second);

        // when / then
        assertThrows(IllegalStateException.class, () -> new ChunkStrategyRegistry(duplicated));
    }

    /**
     * 构造 7 个真实策略实例的完整列表（与生产 bean 集合等价）。
     *
     * @return 策略实例列表
     */
    private static List<ChunkStrategy> strategies() {
        return List.of(
                new GeneralChunkStrategy(),
                new QaChunkStrategy(),
                new BookChunkStrategy(),
                new LawsChunkStrategy(),
                new TableChunkStrategy(),
                new PresentationChunkStrategy(),
                new OneChunkStrategy()
        );
    }

    /**
     * 构造一个仅用于注册表测试的哑策略（返回指定方法名，不参与实际切分）。
     *
     * @param methodName 方法名
     * @return 哑策略实例
     */
    private static ChunkStrategy dummyStrategy(String methodName) {
        return new ChunkStrategy() {
            @Override
            public String method() {
                return methodName;
            }

            @Override
            public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
                return List.of();
            }

            @Override
            public boolean supports(ContentBlockVO block) {
                return false;
            }
        };
    }
}