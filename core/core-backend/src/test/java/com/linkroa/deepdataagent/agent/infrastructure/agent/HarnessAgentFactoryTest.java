package com.linkroa.deepdataagent.agent.infrastructure.agent;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentMemoryProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ApiDataFetcherTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.ChartGeneratorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.NL2SqlTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SchemaRetrieverTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.SqlExecutorTool;
import com.linkroa.deepdataagent.agent.infrastructure.tool.WebSearchTool;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HarnessAgentFactory 单元测试
 * <p>覆盖 getOrCreateAgent、createAgent（含 buildToolkit、resolvePrompt）、getAgent、evictSession、closeAgent
 * 等方法及所有可测分支。通过 {@code MockedStatic} 对 AgentScope 的 {@link HarnessAgent#builder()} 静态方法进行桩化，
 * 避免真实框架初始化。</p>
 */
@ExtendWith(MockitoExtension.class)
class HarnessAgentFactoryTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private SessionProperties sessionProperties;

    @Mock
    private AgentProperties agentProperties;

    @Mock
    private AgentMemoryProperties agentMemoryProperties;

    @Mock
    private SchemaRetrieverTool schemaRetrieverTool;

    @Mock
    private NL2SqlTool nl2SqlTool;

    @Mock
    private SqlExecutorTool sqlExecutorTool;

    @Mock
    private ApiDataFetcherTool apiDataFetcherTool;

    @Mock
    private ChartGeneratorTool chartGeneratorTool;

    @Mock
    private WebSearchTool webSearchTool;

    private HarnessAgentFactory factory;

    @BeforeEach
    void setUp() {
        lenient().when(llmClient.getChatModel(any())).thenReturn(mock(ChatModelBase.class));
        // 默认禁用记忆，避免现有测试受记忆接线影响；记忆相关测试单独开启
        lenient().when(agentMemoryProperties.isEnabled()).thenReturn(false);
        factory = createFactory(10);
    }

    /**
     * 按指定最大活跃会话数创建工厂实例
     *
     * @param maxActiveSessions 最大活跃会话数
     * @return 工厂实例
     */
    private HarnessAgentFactory createFactory(int maxActiveSessions) {
        lenient().when(sessionProperties.getMaxActiveSessions()).thenReturn(maxActiveSessions);
        return new HarnessAgentFactory(
                llmClient, sessionProperties, agentProperties, agentMemoryProperties,
                schemaRetrieverTool, nl2SqlTool, sqlExecutorTool,
                apiDataFetcherTool, chartGeneratorTool, webSearchTool);
    }

    /**
     * 桩化 HarnessAgent.Builder 的链式调用，使 build() 返回指定 agent
     *
     * @param agent 期望 build() 返回的 agent
     * @return builder mock
     */
    private HarnessAgent.Builder mockBuilderChain(HarnessAgent agent) {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.description(anyString())).thenReturn(builder);
        when(builder.sysPrompt(anyString())).thenReturn(builder);
        when(builder.model(any(ChatModelBase.class))).thenReturn(builder);
        when(builder.compaction(any())).thenReturn(builder);
        when(builder.toolkit(any())).thenReturn(builder);
        when(builder.middlewares(anyList())).thenReturn(builder);
        when(builder.maxIters(anyInt())).thenReturn(builder);
        // 记忆相关桩使用 lenient：默认记忆禁用，仅记忆开启测试使用
        lenient().when(builder.memory(any(MemoryConfig.class))).thenReturn(builder);
        lenient().when(builder.workspace(any(Path.class))).thenReturn(builder);
        lenient().when(builder.disableMemoryTools()).thenReturn(builder);
        when(builder.build()).thenReturn(agent);
        return builder;
    }

    /**
     * 在静态 mock 作用域内调用 getOrCreateAgent 并执行创建流程
     *
     * @param agent            期望创建的 agent
     * @param sessionId        会话 ID
     * @param category         数据源类型
     * @param enableWebSearch  是否启用联网搜索
     * @param extraMiddlewares 额外中间件
     * @return builder mock
     */
    private HarnessAgent.Builder createViaFactory(
            HarnessAgent agent, String sessionId, DatasourceCategory category,
            boolean enableWebSearch, List<MiddlewareBase> extraMiddlewares) {
        HarnessAgent.Builder builder = mockBuilderChain(agent);
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            factory.getOrCreateAgent(sessionId, 1L, category, enableWebSearch, extraMiddlewares);
        }
        return builder;
    }

    /**
     * 捕获实际传给 builder.toolkit 的 Toolkit 实例
     */
    private Toolkit captureToolkit(HarnessAgent.Builder builder) {
        ArgumentCaptor<Toolkit> captor = ArgumentCaptor.forClass(Toolkit.class);
        verify(builder).toolkit(captor.capture());
        return captor.getValue();
    }

    /**
     * 捕获实际传给 builder.sysPrompt 的系统提示词
     */
    private String captureSysPrompt(HarnessAgent.Builder builder) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(builder).sysPrompt(captor.capture());
        return captor.getValue();
    }

    /**
     * 捕获实际传给 builder.middlewares 的中间件列表
     */
    private List<MiddlewareBase> captureMiddlewares(HarnessAgent.Builder builder) {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(builder).middlewares(captor.capture());
        return captor.getValue();
    }

    @Test
    void should_throwIllegalStateException_when_getOrCreateAgent_given_cacheExceedsLimit() {
        // given
        HarnessAgentFactory limitFactory = createFactory(1);
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);

        // when
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            limitFactory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);

            // then: 缓存已满 1 个，再次创建应抛异常
            assertThatThrownBy(() ->
                    limitFactory.getOrCreateAgent("s2", 1L, DatasourceCategory.JDBC, false, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("活跃会话数已达上限");
        }
    }

    @Test
    void should_createTempAgent_when_getOrCreateAgent_given_nonEmptyExtraMiddlewares() {
        // given
        MiddlewareBase middleware = mock(MiddlewareBase.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);

        // when
        HarnessAgent result;
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            result = factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, List.of(middleware));
        }

        // then: 返回创建的 agent，且不作为缓存
        assertThat(result).isSameAs(agent);
        assertThat(factory.getAgent("s1")).isNull();
        verify(builder, times(1)).build();
    }

    @Test
    void should_includeExtraMiddlewares_when_getOrCreateAgent_given_nonEmptyExtraMiddlewares() {
        // given
        MiddlewareBase middleware = mock(MiddlewareBase.class);
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, List.of(middleware));

        // then
        List<MiddlewareBase> middlewares = captureMiddlewares(builder);
        assertThat(middlewares).containsExactly(middleware);
    }

    @Test
    void should_returnSameCachedAgent_when_getOrCreateAgent_given_sameSessionId() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);

        // when
        HarnessAgent first;
        HarnessAgent second;
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            first = factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
            second = factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
        }

        // then: 第二次调用命中缓存，返回同一实例
        assertThat(first).isSameAs(second);
        assertThat(factory.getAgent("s1")).isSameAs(first);
        verify(builder, times(1)).build();
    }

    @Test
    void should_createSeparateAgents_when_getOrCreateAgent_given_differentSessionIds() {
        // given
        HarnessAgent agent1 = mock(HarnessAgent.class);
        HarnessAgent agent2 = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent1);
        when(builder.build()).thenReturn(agent1, agent2);

        // when
        HarnessAgent first;
        HarnessAgent second;
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            first = factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
            second = factory.getOrCreateAgent("s2", 1L, DatasourceCategory.JDBC, false, null);
        }

        // then: 不同会话分别创建并缓存
        assertThat(first).isSameAs(agent1);
        assertThat(second).isSameAs(agent2);
        assertThat(factory.getAgent("s1")).isSameAs(agent1);
        assertThat(factory.getAgent("s2")).isSameAs(agent2);
        verify(builder, times(2)).build();
    }

    @Test
    void should_registerJdbcTools_when_getOrCreateAgent_given_jdbcCategory() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then
        Toolkit toolkit = captureToolkit(builder);
        assertThat(toolkit.getToolNames()).contains("retrieve_schema", "generate_sql", "execute_sql",
                "generate_chart");
        assertThat(toolkit.getToolNames()).doesNotContain("execute_api_query");
    }

    @Test
    void should_registerApiTools_when_getOrCreateAgent_given_apiCategory() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.API, false, null);

        // then
        Toolkit toolkit = captureToolkit(builder);
        assertThat(toolkit.getToolNames()).contains("retrieve_schema", "execute_api_query",
                "generate_chart");
        assertThat(toolkit.getToolNames()).doesNotContain("generate_sql", "execute_sql");
    }

    @Test
    void should_registerWebSearchTool_when_getOrCreateAgent_given_enableWebSearchTrue() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, true, null);

        // then
        Toolkit toolkit = captureToolkit(builder);
        assertThat(toolkit.getToolNames()).contains("web_search");
    }

    @Test
    void should_notRegisterWebSearchTool_when_getOrCreateAgent_given_enableWebSearchFalse() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then
        Toolkit toolkit = captureToolkit(builder);
        assertThat(toolkit.getToolNames()).doesNotContain("web_search");
    }

    @Test
    void should_useCustomApiPrompt_when_getOrCreateAgent_given_apiCategoryAndCustomPrompt() {
        // given
        when(agentProperties.getApiSysPrompt()).thenReturn("自定义 API 系统提示词");
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.API, false, null);

        // then
        assertThat(captureSysPrompt(builder)).isEqualTo("自定义 API 系统提示词");
    }

    @Test
    void should_useDefaultApiPrompt_when_getOrCreateAgent_given_apiCategoryAndBlankApiPrompt() {
        // given
        when(agentProperties.getApiSysPrompt()).thenReturn("   ");
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.API, false, null);

        // then
        assertThat(captureSysPrompt(builder)).contains("数据分析专家 Agent");
    }

    @Test
    void should_useCustomSysPrompt_when_getOrCreateAgent_given_jdbcCategoryAndCustomPrompt() {
        // given
        when(agentProperties.getSysPrompt()).thenReturn("自定义 JDBC 系统提示词");
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then
        assertThat(captureSysPrompt(builder)).isEqualTo("自定义 JDBC 系统提示词");
    }

    @Test
    void should_useDefaultSysPrompt_when_getOrCreateAgent_given_jdbcCategoryAndBlankPrompt() {
        // given
        when(agentProperties.getSysPrompt()).thenReturn("");
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then
        assertThat(captureSysPrompt(builder)).contains("DeepDataAnalyst");
    }

    @Test
    void should_returnCachedAgent_when_getAgent_given_existingSession() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
        }

        // when
        HarnessAgent cached = factory.getAgent("s1");

        // then
        assertThat(cached).isSameAs(agent);
    }

    @Test
    void should_returnNull_when_getAgent_given_unknownSession() {
        // when
        HarnessAgent cached = factory.getAgent("unknown");

        // then
        assertThat(cached).isNull();
    }

    @Test
    void should_closeAndRemoveAgent_when_evictSession_given_existingSession() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
        }

        // when
        factory.evictSession("s1");

        // then
        verify(agent).close();
        assertThat(factory.getAgent("s1")).isNull();
    }

    @Test
    void should_doNothing_when_evictSession_given_unknownSession() {
        // when
        factory.evictSession("unknown");

        // then: 无异常即可
        assertThat(factory.getAgent("unknown")).isNull();
    }

    @Test
    void should_closeTempAgent_when_closeAgent_given_notCachedAgent() {
        // given
        HarnessAgent tempAgent = mock(HarnessAgent.class);

        // when
        factory.closeAgent(tempAgent);

        // then
        verify(tempAgent).close();
    }

    @Test
    void should_notCloseCachedAgent_when_closeAgent_given_cachedAgent() {
        // given
        HarnessAgent agent = mock(HarnessAgent.class);
        HarnessAgent.Builder builder = mockBuilderChain(agent);
        try (MockedStatic<HarnessAgent> mocked = mockStatic(HarnessAgent.class)) {
            mocked.when(HarnessAgent::builder).thenReturn(builder);
            factory.getOrCreateAgent("s1", 1L, DatasourceCategory.JDBC, false, null);
        }

        // when
        factory.closeAgent(agent);

        // then: 缓存中的 agent 不应被 closeAgent 关闭
        verify(agent, never()).close();
        assertThat(factory.getAgent("s1")).isSameAs(agent);
    }

    @Test
    void should_doNothing_when_closeAgent_given_null() {
        // when
        factory.closeAgent(null);

        // then: 无异常即可
        assertThat(factory.getAgent("s1")).isNull();
    }

    @Test
    void should_wireMemory_when_getOrCreateAgent_given_memoryEnabled() {
        // given
        when(agentMemoryProperties.isEnabled()).thenReturn(true);
        when(agentMemoryProperties.getWorkspace()).thenReturn("data/agentscope/test");
        when(agentMemoryProperties.getConsolidationMaxTokens()).thenReturn(4000);
        when(agentMemoryProperties.getConsolidationMinGap()).thenReturn(java.time.Duration.ofHours(1));
        when(agentMemoryProperties.getFlushTrigger()).thenReturn("throttled");
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then: 记忆开启时调用 memory/workspace/disableMemoryTools
        verify(builder).memory(any(MemoryConfig.class));
        verify(builder).workspace(any(Path.class));
        verify(builder).disableMemoryTools();
    }

    @Test
    void should_notWireMemory_when_getOrCreateAgent_given_memoryDisabled() {
        // given
        when(agentMemoryProperties.isEnabled()).thenReturn(false);
        HarnessAgent agent = mock(HarnessAgent.class);

        // when
        HarnessAgent.Builder builder = createViaFactory(agent, "s1", DatasourceCategory.JDBC, false, null);

        // then: 记忆关闭时不调用 memory/workspace/disableMemoryTools
        verify(builder, never()).memory(any(MemoryConfig.class));
        verify(builder, never()).workspace(any(Path.class));
        verify(builder, never()).disableMemoryTools();
    }
}