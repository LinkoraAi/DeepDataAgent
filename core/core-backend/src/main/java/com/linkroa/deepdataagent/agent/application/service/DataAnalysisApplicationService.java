package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.application.adapter.BatchFlushManager;
import com.linkroa.deepdataagent.agent.application.adapter.EventAdapter;
import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.repository.AgentSessionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DialogueRepository;
import com.linkroa.deepdataagent.agent.domain.valueobject.DialogueStatus;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.agent.HarnessAgentFactory;
import com.linkroa.deepdataagent.agent.infrastructure.config.AgentProperties;
import com.linkroa.deepdataagent.agent.infrastructure.config.SessionProperties;
import com.linkroa.deepdataagent.agent.application.context.SessionToolContext;
import com.linkroa.deepdataagent.agent.infrastructure.middleware.SearchResultsMiddleware;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.MessagePersistenceService;
import com.linkroa.deepdataagent.agent.infrastructure.sse.agent.AgentExecutionPool;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SSEConnectionPool;
import com.linkroa.deepdataagent.agent.infrastructure.sse.SessionEventBus;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import com.linkroa.deepdataagent.shared.exception.SSENotConnectedException;
import com.linkroa.deepdataagent.shared.exception.SystemBusyException;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据分析应用服务
 * <p>基于 AgentScope ReActAgent 架构，由 LLM 自主决策工具调用顺序完成数据分析。
 * 同时保留同步 Pipeline 方法用于向后兼容和测试。</p>
 */
@Service
public class DataAnalysisApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisApplicationService.class);

    /** RuntimeContext 中存储 modelConfigId 的键名，供 Tool 通过 ToolCallParam 获取 */
    public static final String CTX_KEY_MODEL_CONFIG_ID = "model_config_id";

    /** 日志前缀 */
    private static final String CONV_LOG_PREFIX = "[DataAnalysis]";

    private final DatasourceGateway datasourceGateway;
    private final LLMClient llmClient;
    private final HarnessAgentFactory agentFactory;
    private final EventAdapter eventAdapter;
    private final MessagePersistenceService messagePersistenceService;
    private final AgentSessionRepository sessionRepository;
    private final DialogueRepository dialogueRepository;
    private final SessionProperties sessionProperties;
    private final AgentProperties agentProperties;
    private final SessionToolContext sessionToolContext;
    private final SSEConnectionPool sseConnectionPool;
    private final SessionEventBus sessionEventBus;
    private final AgentExecutionPool agentExecutionPool;

    public DataAnalysisApplicationService(
            DatasourceGateway datasourceGateway,
            LLMClient llmClient,
            HarnessAgentFactory agentFactory,
            EventAdapter eventAdapter,
            MessagePersistenceService messagePersistenceService,
            AgentSessionRepository sessionRepository,
            DialogueRepository dialogueRepository,
            SessionProperties sessionProperties,
            AgentProperties agentProperties,
            SessionToolContext sessionToolContext,
            SSEConnectionPool sseConnectionPool,
            SessionEventBus sessionEventBus,
            AgentExecutionPool agentExecutionPool) {
        this.datasourceGateway = datasourceGateway;
        this.llmClient = llmClient;
        this.agentFactory = agentFactory;
        this.eventAdapter = eventAdapter;
        this.messagePersistenceService = messagePersistenceService;
        this.sessionRepository = sessionRepository;
        this.dialogueRepository = dialogueRepository;
        this.sessionProperties = sessionProperties;
        this.agentProperties = agentProperties;
        this.sessionToolContext = sessionToolContext;
        this.sseConnectionPool = sseConnectionPool;
        this.sessionEventBus = sessionEventBus;
        this.agentExecutionPool = agentExecutionPool;
    }

    /**
     * 构建 HarnessAgent 实例
     * <p>使用 HarnessAgentFactory 创建 Agent，集成 Session、SessionMemory、Compaction 等特性。</p>
     *
     * @param sessionId 会话 ID
     * @param modelConfigId 模型配置 ID
     * @param category 数据源类型
     * @param enableWebSearch 是否启用网络搜索工具
     * @return HarnessAgent 实例
     */
    private HarnessAgent buildAgent(String sessionId, Long modelConfigId, DatasourceCategory category, boolean enableWebSearch) {
        // 构造请求特定 middleware（仅 enableWebSearch 时添加 SearchResultsMiddleware）
        List<MiddlewareBase> extraMiddlewares = new ArrayList<>();
        if (enableWebSearch) {
            extraMiddlewares.add(new SearchResultsMiddleware());
        }

        return agentFactory.getOrCreateAgent(sessionId, modelConfigId, category, enableWebSearch, extraMiddlewares);
    }

    /**
     * 流式执行数据分析（SSE），返回 Flux<AgentEvent>
     * <p>使用 AgentScope 的 streamEvents() API 订阅事件流，直接推送 AgentEvent。</p>
     * <p>通过 EventAdapter 将 AgentEvent 适配为 DialogueMessage 用于持久化。</p>
     */
    public Flux<AgentEvent> executeStream(DataAnalysisCommand command) {
        String sessionId = command.sessionId();

        // 异步执行
        return Mono.fromCallable(() -> {
            // 1. 校验会话是否存在且处于活跃状态
            Optional<AgentSession> sessionOpt = sessionRepository.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                throw new DeepDataAgentException("会话不存在");
            }

            AgentSession session = sessionOpt.get();
            if (!session.canStartDialogue()) {
                throw new DeepDataAgentException("会话已关闭");
            }

            // 2. 校验数据源
            DatasourceInfo datasource = datasourceGateway.findDatasource(
                            Long.valueOf(command.connectionId()))
                    .orElseThrow(() -> new DeepDataAgentException("数据源不存在: " + command.connectionId()));
            if (!datasource.enabled()) {
                throw new DeepDataAgentException("数据源未启用");
            }

            long analysisStartTime = System.currentTimeMillis();
            log.info("{} start sessionId={} userQuestion='{}' modelConfigId={} connectionId={} category={} enableWebSearch={}",
                    CONV_LOG_PREFIX, sessionId, LogMasker.mask(command.userQuestion()),
                    command.modelConfigId(), command.connectionId(), datasource.category(), command.enableWebSearch());

            // 注册 modelConfigId 到 SessionToolContext，供工具获取
            sessionToolContext.register(sessionId, command.modelConfigId());

            // 关键修复：立即持久化用户消息，确保切换会话回来时 loadMessages 能获取到
            // 事务边界由 MessagePersistenceService#persistUserMessageSync 内部编程式事务自包含
            Long dialogueId = messagePersistenceService.persistUserMessageSync(sessionId, command.userQuestion());

            // 3. 判断是否为首次分析
            boolean isFirstAnalysis = session.getLastMessageTime() == null;

            // 3.1 首次分析时尽早异步生成标题
            if (isFirstAnalysis) {
                Mono.fromCallable(() -> llmClient.generateTitle(command.modelConfigId(), command.userQuestion()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(title -> {
                            if (title != null && !title.isBlank()) {
                                sessionRepository.updateTitle(sessionId, title);
                            }
                        });
            }

            // 4. 构建 HarnessAgent 并执行（支持上下文恢复）
            DatasourceCategory category = datasource.category();
            HarnessAgent agent = buildAgent(sessionId, command.modelConfigId(), category, command.enableWebSearch());

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent("会话ID: " + sessionId
                            + "\n数据源ID: " + command.connectionId()
                            + "\n模型配置ID: " + command.modelConfigId()
                            + "\n用户问题: " + command.userQuestion())
                    .build();

            RuntimeContext rc = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .put("clean_user_question", command.userQuestion())
                    .put(CTX_KEY_MODEL_CONFIG_ID, command.modelConfigId())
                    .build();

            // 5. 注册 EventAdapter 上下文，用于聚合 AgentEvent 为 DialogueMessage
            EventAdapter.CollectorContext collectorContext = eventAdapter.registerContext(sessionId, command.userQuestion());

            // 6. 创建攒批持久化管理器（每 5 秒 flush 一次，终态时 final flush）
            BatchFlushManager batchFlushManager = new BatchFlushManager(dialogueRepository);
            batchFlushManager.start(dialogueId, sessionId, collectorContext);

            // 7. 使用 streamEvents() 订阅事件流，直接返回 Flux<AgentEvent>
            return agent.streamEvents(List.of(userMsg), rc)
                    .doOnNext(event -> {
                        // 使用 EventAdapter 处理 AgentEvent，追加 DialogueMessage 到消息列表
                        eventAdapter.handleEvent(sessionId, event);

                        // 日志记录
                        if (log.isDebugEnabled()) {
                            log.debug("{} agentEvent sessionId={} type={}", CONV_LOG_PREFIX, sessionId, event.getType());
                        }
                    })
                    .doOnComplete(() -> {
                        long duration = System.currentTimeMillis() - analysisStartTime;
                        log.info("{} complete sessionId={} durationMs={}", CONV_LOG_PREFIX, sessionId, duration);

                        // 终态 flush：以 COMPLETED 状态持久化全部消息
                        batchFlushManager.finalFlush(dialogueId, sessionId, collectorContext, DialogueStatus.COMPLETED);
                        batchFlushManager.close();

                        // 注销 SessionToolContext
                        sessionToolContext.unregister(sessionId);

                        // 注销 EventAdapter 上下文
                        eventAdapter.unregisterContext(sessionId);
                    })
                    .doOnError(e -> {
                        log.error("数据分析失败", e);
                        long duration = System.currentTimeMillis() - analysisStartTime;
                        log.info("{} error sessionId={} durationMs={} error='{}'",
                                CONV_LOG_PREFIX, sessionId, duration, LogMasker.mask(e.getMessage()));

                        // 追加错误消息并以 FAILED 状态终态 flush
                        eventAdapter.addError(sessionId, e.getMessage());
                        batchFlushManager.finalFlush(dialogueId, sessionId, collectorContext, DialogueStatus.FAILED);
                        batchFlushManager.close();

                        // 注销 SessionToolContext
                        sessionToolContext.unregister(sessionId);

                        // 注销 EventAdapter 上下文
                        eventAdapter.unregisterContext(sessionId);
                    })
                    .doOnCancel(() -> {
                        log.warn("数据分析被取消，持久化已输出的部分内容 sessionId={}", sessionId);

                        // 以 CANCELLED 状态终态 flush
                        batchFlushManager.finalFlush(dialogueId, sessionId, collectorContext, DialogueStatus.CANCELLED);
                        batchFlushManager.close();

                        // 注销 SessionToolContext
                        sessionToolContext.unregister(sessionId);

                        // 注销 EventAdapter 上下文
                        eventAdapter.unregisterContext(sessionId);
                    })
                    .doFinally(signal -> {
                        // 关闭临时 agent（非缓存）
                        agentFactory.closeAgent(agent);
                    });
        })
        .flatMapMany(flux -> flux)
        .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 执行数据分析（编排层）
     * <p>接收分析命令，检查 SSE 连接与会话状态，注册会话事件流，通过 AgentExecutionPool 异步执行分析，
     * 并将事件路由到对应的 SSE 连接及 SessionEventBus 广播。</p>
     * <p>支持重连后重新提交：如果会话已有分析在运行，则只更新 clientId 映射并返回成功。</p>
     *
     * @param command  分析命令
     * @param clientId 客户端 ID
     * @return 分析执行结果
     * @throws SSENotConnectedException 如果客户端未连接 SSE
     * @throws SystemBusyException      如果事件总线或执行池达到上限
     */
    public AnalysisExecutionResult executeAnalysis(DataAnalysisCommand command, String clientId) {
        String sessionId = command.sessionId();

        // 检查客户端是否已连接
        if (!sseConnectionPool.isConnected(clientId)) {
            throw new SSENotConnectedException("客户端未连接 SSE，请先建立 SSE 连接");
        }

        // 检查会话是否已有分析在运行（通过 sessionClientIdMap 判断）
        String existingClientId = sseConnectionPool.getClientIdForSession(sessionId);
        if (existingClientId != null) {
            // 会话已有分析在运行，只更新 clientId 映射
            log.info("Session {} already has analysis running, updating clientId from {} to {}",
                    sessionId, existingClientId, clientId);
            sseConnectionPool.updateSessionClientId(sessionId, clientId);
            return new AnalysisExecutionResult(sessionId, "clientId 已更新");
        }

        // 注册会话事件流到 SessionEventBus
        Sinks.Many<AgentEvent> sink = sessionEventBus.register(sessionId);
        if (sink == null) {
            throw new SystemBusyException("系统繁忙，请稍后重试");
        }

        // 在分析开始前注册 clientId 映射（用于后续事件推送时的动态查找）
        sseConnectionPool.updateSessionClientId(sessionId, clientId);

        // 通过 AgentExecutionPool 异步执行分析
        boolean accepted = agentExecutionPool.execute(sessionId, () -> {
            try {
                Flux<AgentEvent> eventFlux = executeStream(command);
                eventFlux.subscribe(
                    event -> {
                        // 动态查找最新的 clientId，支持重连后更新
                        String currentClientId = sseConnectionPool.getClientIdForSession(sessionId);
                        if (currentClientId != null) {
                            sseConnectionPool.sendEvent(currentClientId, sessionId, event);
                        } else {
                            log.warn("No clientId mapping found for sessionId={}, event dropped", sessionId);
                        }
                        // 通过 SessionEventBus 广播事件
                        sink.tryEmitNext(event);
                    },
                    error -> {
                        log.error("Data analysis error for sessionId={}", sessionId, error);
                        AgentEvent errorEvent = createErrorEvent(error);
                        String currentClientId = sseConnectionPool.getClientIdForSession(sessionId);
                        if (currentClientId != null) {
                            sseConnectionPool.sendEvent(currentClientId, sessionId, errorEvent);
                        }
                        sink.tryEmitNext(errorEvent);
                        sink.tryEmitComplete();
                        sessionEventBus.unregister(sessionId);
                        sseConnectionPool.removeSessionClientId(sessionId);
                    },
                    () -> {
                        log.info("Data analysis complete for sessionId={}", sessionId);
                        AgentEvent completeEvent = createCompleteEvent();
                        String currentClientId = sseConnectionPool.getClientIdForSession(sessionId);
                        if (currentClientId != null) {
                            sseConnectionPool.sendEvent(currentClientId, sessionId, completeEvent);
                        }
                        sink.tryEmitNext(completeEvent);
                        sink.tryEmitComplete();
                        sessionEventBus.unregister(sessionId);
                        sseConnectionPool.removeSessionClientId(sessionId);
                    }
                );
            } catch (Exception e) {
                log.error("Failed to execute analysis for sessionId={}", sessionId, e);
                sessionEventBus.unregister(sessionId);
                sseConnectionPool.removeSessionClientId(sessionId);
            }
        });

        if (!accepted) {
            sessionEventBus.unregister(sessionId);
            sseConnectionPool.removeSessionClientId(sessionId);
            throw new SystemBusyException("系统繁忙，请稍后重试");
        }

        return new AnalysisExecutionResult(sessionId, "分析已开始");
    }

    /**
     * 创建错误事件
     * <p>使用 {@link CustomEvent} 封装错误信息，事件类型为 CUSTOM。</p>
     *
     * @param error 异常对象
     * @return AgentEvent 事件
     */
    private AgentEvent createErrorEvent(Throwable error) {
        Map<String, Object> value = new HashMap<>();
        value.put("type", "ERROR");
        value.put("message", error.getMessage() != null ? error.getMessage() : "未知错误");
        return new CustomEvent("error", value);
    }

    /**
     * 创建完成事件
     * <p>使用 {@link CustomEvent} 封装完成信息，事件类型为 CUSTOM。</p>
     *
     * @return AgentEvent 事件
     */
    private AgentEvent createCompleteEvent() {
        Map<String, Object> value = new HashMap<>();
        value.put("type", "COMPLETE");
        value.put("message", "分析完成");
        return new CustomEvent("complete", value);
    }

    /**
     * 分析执行结果
     *
     * @param sessionId 会话 ID
     * @param message   结果消息
     */
    public record AnalysisExecutionResult(String sessionId, String message) {
    }

    private String resolveSqlDialect(DatasourceInfo datasource) {
        if (datasource.jdbcCategory() == null) return "MySQL";
        return switch (datasource.jdbcCategory()) {
            case MYSQL -> "MySQL";
            case CLICKHOUSE -> "ClickHouse";
            case POSTGRESQL -> "PostgreSQL";
        };
    }

}
