package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.runtime.api.SchedulerSessionApi;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.api.dto.SchedulerLaunchDTO;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnExecutionService;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultSchedulerSessionApi} 单测：调度器触发新建打标 Session（透传载荷解释装配：
 * resources JSON ⇄ 挂载资源、环境变量 / 元数据 / 保管库透传）并驱动首个 turn
 * （消息回退链：显式输入 → initial_events 合成 → 默认调度提示）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultSchedulerSessionApiTest {

    /** 会话生命周期入口 mock（decompose-command-facade 4.4 改线：建会话直连目标服务）。 */
    @Mock private SessionLifecycleService sessionLifecycleService;
    /** turn 执行链 mock（decompose-command-facade 4.4 改线：驱动首个 turn 直连目标服务）。 */
    @Mock private TurnExecutionService turnExecutionService;

    private SchedulerSessionApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultSchedulerSessionApi();
        ReflectionTestUtils.setField(api, "sessionLifecycleService", sessionLifecycleService);
        ReflectionTestUtils.setField(api, "turnExecutionService", turnExecutionService);
    }

    private SchedulerLaunchDTO dto(String input, String resourcesJson, List<String> vaultIds,
                                   String initialEvents) {
        return new SchedulerLaunchDTO("1", "agent-1", "3", "env-1", input,
                "webhook", "dep-1", "{\"TZ\":\"UTC\"}", resourcesJson, vaultIds,
                initialEvents, "{\"biz\":\"x\"}");
    }

    private AgentSession buildSession(String triggerType) {
        return AgentSession.createWithTrigger("1", "agent-1", "3", "{}", null, triggerType, "dep-1");
    }

    @Test
    void should_createTriggeredSessionWithMountsAndSendFirstTurn_when_launch_given_validRequest() {
        // given（显式输入优先；resources 为会话存储 snake_case 形态 JSON）
        SchedulerLaunchDTO request = dto("开始", "[{\"type\":\"file\",\"file_id\":\"file_abc\"}]",
                List.of("vault-a"), null);
        AgentSession session = buildSession("webhook");
        when(sessionLifecycleService.createSession(any())).thenReturn(session);

        // when
        String sessionId = api.launch(request);

        // then（打标会话装配全量透传 + 首个 turn 携带显式输入）
        assertEquals(session.sessionId(), sessionId);
        ArgumentCaptor<CreateSessionCommand> sessionCaptor = ArgumentCaptor.forClass(CreateSessionCommand.class);
        verify(sessionLifecycleService).createSession(sessionCaptor.capture());
        CreateSessionCommand command = sessionCaptor.getValue();
        assertEquals("1", command.userId());
        assertEquals("agent-1", command.agentId());
        assertEquals("3", command.agentVersion());
        assertEquals("env-1", command.environmentId());
        assertEquals("webhook", command.triggerType());
        assertEquals("dep-1", command.triggerId());
        assertEquals("{\"TZ\":\"UTC\"}", command.environmentVariables());
        assertEquals("{\"biz\":\"x\"}", command.metadata());
        assertEquals(List.of("vault-a"), command.vaultIds());
        assertEquals(1, command.resources().size());
        SessionResource resource = command.resources().get(0);
        assertEquals(SessionResource.FILE_TYPE, resource.type());
        assertEquals("file_abc", resource.fileId());
        ArgumentCaptor<SendMessageCommand> messageCaptor = ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(turnExecutionService).sendMessageAsync(messageCaptor.capture());
        assertEquals("开始", messageCaptor.getValue().message());
        assertEquals(session.sessionId(), messageCaptor.getValue().sessionId());
    }

    @Test
    void should_composeInitialEvents_when_launch_given_blankInputWithUserMessageEvents() {
        // given（无显式输入：按序合成 user_message 事件文本为首个 turn 消息，换行拼接）
        SchedulerLaunchDTO request = dto(null, null, null,
                "[{\"type\":\"user_message\",\"text\":\"第一条\"},{\"type\":\"user_message\",\"text\":\"第二条\"}]");
        when(sessionLifecycleService.createSession(any())).thenReturn(buildSession("cron"));

        // when
        api.launch(request);

        // then
        ArgumentCaptor<SendMessageCommand> captor = ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(turnExecutionService).sendMessageAsync(captor.capture());
        assertEquals("第一条\n第二条", captor.getValue().message());
    }

    @Test
    void should_useDefaultPrompt_when_launch_given_blankInputAndNoUsableEvents() {
        // given（输入与首批事件均缺省：回退默认调度提示，保证首个 turn 可执行；
        // 非 user_message 类型事件本期忽略）
        SchedulerLaunchDTO request = dto(" ", null, null, "[{\"type\":\"other\"}]");
        when(sessionLifecycleService.createSession(any())).thenReturn(buildSession("manual"));

        // when
        api.launch(request);

        // then
        ArgumentCaptor<SendMessageCommand> captor = ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(turnExecutionService).sendMessageAsync(captor.capture());
        assertEquals("调度触发，开始执行。", captor.getValue().message());
    }

    @Test
    void should_normalizeEmptyResources_when_launch_given_emptyArrayJson() {
        // given（"[]" 与缺省一律归一空挂载列表，不触发 JSON 解析异常）
        SchedulerLaunchDTO request = dto("跑一下", "[]", null, "[]");
        when(sessionLifecycleService.createSession(any())).thenReturn(buildSession("webhook"));

        // when
        api.launch(request);

        // then
        ArgumentCaptor<CreateSessionCommand> captor = ArgumentCaptor.forClass(CreateSessionCommand.class);
        verify(sessionLifecycleService).createSession(captor.capture());
        assertTrue(captor.getValue().resources().isEmpty());
        assertEquals(List.of(), captor.getValue().vaultIds());
    }

    @Test
    void should_throwException_when_launch_given_malformedResourcesJson() {
        // given（非法 resources JSON：装配解释即拒绝，不落会话）
        SchedulerLaunchDTO request = dto("跑", "not-a-json", null, null);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> api.launch(request));
        verify(sessionLifecycleService, org.mockito.Mockito.never()).createSession(any());
    }

    @Test
    void should_mapGithubRepositoryResource_when_launch_given_snakeCaseResourceFields() {
        // given（snake_case 键 → 资源值对象 camelCase 组件解释）
        SchedulerLaunchDTO request = dto(null,
                "[{\"type\":\"github_repository\",\"url\":\"https://example.com/repo.git\",\"authorization_token\":\"ghp_x\",\"checkout\":\"main\"}]",
                null, "[{\"type\":\"user_message\",\"text\":\"同步仓库\"}]");
        when(sessionLifecycleService.createSession(any())).thenReturn(buildSession("cron"));

        // when
        api.launch(request);

        // then
        ArgumentCaptor<CreateSessionCommand> captor = ArgumentCaptor.forClass(CreateSessionCommand.class);
        verify(sessionLifecycleService).createSession(captor.capture());
        SessionResource resource = captor.getValue().resources().get(0);
        assertEquals(SessionResource.GITHUB_REPO_TYPE, resource.type());
        assertEquals("https://example.com/repo.git", resource.url());
        assertEquals("ghp_x", resource.authorizationToken());
        assertEquals("main", resource.checkout());
    }
}
