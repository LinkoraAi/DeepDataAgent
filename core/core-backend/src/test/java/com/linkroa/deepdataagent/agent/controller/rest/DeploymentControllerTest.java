package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.UpdateDeploymentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentRunsQuery;
import com.linkroa.deepdataagent.agent.application.query.ListDeploymentsQuery;
import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import com.linkroa.deepdataagent.agent.application.service.DeploymentTriggerResult;
import com.linkroa.deepdataagent.agent.controller.request.TriggerDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateDeploymentRequest;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentRunResponse;
import com.linkroa.deepdataagent.agent.controller.response.DeploymentTriggerResponse;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.model.DeploymentRun;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentRunStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentTriggerType;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeploymentController} 单测（6.5 管理面）：游标列表参数装配与信封映射、
 * merge-patch PATCH 命令装配、run 端点（由 trigger 更名）委派、运行记录单调度器 /
 * 全局查询端点的查询作用域装配与响应形状。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentControllerTest {

    private static final OffsetDateTime T_RUN = OffsetDateTime.parse("2026-09-03T10:00:00+08:00");

    @Mock private DeploymentApplicationService applicationService;

    private DeploymentController controller;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        controller = new DeploymentController();
        ReflectionTestUtils.setField(controller, "applicationService", applicationService);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private Deployment buildDeployment() {
        return Deployment.create("dep_manual", "每日汇总", null, "agent-1", 2, null,
                null, null, null, null, null, null, null, 1L);
    }

    private DeploymentRun buildRun(String runId) {
        return new DeploymentRun(9L, runId, "dep_manual", "sess-1", DeploymentTriggerType.MANUAL,
                DeploymentRunStatus.RUNNING, T_RUN, null, T_RUN, T_RUN);
    }

    @Test
    void should_assembleListQueryAndMapEnvelope_when_list_given_cursorParams() {
        // given
        when(applicationService.list(any(ListDeploymentsQuery.class)))
                .thenReturn(CursorPage.of(List.of(buildDeployment()), true, Deployment::deploymentId));

        // when
        ApiResponse<CursorPage<DeploymentResponse>> response = controller.list(
                "active", null, null, null, "false", "10", "dep_x", null);

        // then（查询装配透传 + 信封 snake_case 映射）
        ArgumentCaptor<ListDeploymentsQuery> captor = ArgumentCaptor.forClass(ListDeploymentsQuery.class);
        verify(applicationService).list(captor.capture());
        ListDeploymentsQuery query = captor.getValue();
        assertEquals(1L, query.ownerId());
        assertEquals(new CursorPageParams(10, "dep_x", null), query.cursor());
        CursorPage<DeploymentResponse> page = response.data();
        assertEquals("dep_manual", page.data().get(0).deployment_id());
        assertEquals("dep_manual", page.firstId());
        assertTrue(page.hasMore());
    }

    @Test
    void should_buildNoopCommand_when_update_given_patchBody() {
        // given（PATCH 体仅含 name：命令装配 deploymentId 取路径、其余缺省）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", "改名");
        fields.put("description", null);
        when(applicationService.update(any(UpdateDeploymentCommand.class))).thenReturn(buildDeployment());

        // when
        ApiResponse<DeploymentResponse> response = controller.update("dep_manual",
                new UpdateDeploymentRequest(fields));

        // then
        ArgumentCaptor<UpdateDeploymentCommand> captor = ArgumentCaptor.forClass(UpdateDeploymentCommand.class);
        verify(applicationService).update(captor.capture());
        UpdateDeploymentCommand command = captor.getValue();
        assertEquals("dep_manual", command.deploymentId());
        assertEquals("改名", command.name());
        assertTrue(command.descriptionPresent());
        assertNull(command.description());
        assertEquals("dep_manual", response.data().deployment_id());
    }

    @Test
    void should_delegateRunEndpoint_when_run_given_inputBody() {
        // given
        when(applicationService.run("dep_manual", "跑一下"))
                .thenReturn(new DeploymentTriggerResult("dep_manual", "drun_1", "sess_1"));

        // when
        ApiResponse<DeploymentTriggerResponse> response =
                controller.run("dep_manual", new TriggerDeploymentRequest("跑一下"));

        // then
        assertEquals("dep_manual", response.data().deployment_id());
        assertEquals("drun_1", response.data().run_id());
        assertEquals("sess_1", response.data().session_id());
    }

    @Test
    void should_passNullBody_when_run_given_noRequestBody() {
        // given
        when(applicationService.run("dep_manual", null))
                .thenReturn(new DeploymentTriggerResult("dep_manual", "drun_1", "sess_1"));

        // when
        controller.run("dep_manual", null);

        // then
        verify(applicationService).run("dep_manual", null);
    }

    @Test
    void should_useDeploymentScope_when_listRuns_given_pathDeploymentId() {
        // given
        when(applicationService.listRuns(any(ListDeploymentRunsQuery.class)))
                .thenReturn(CursorPage.of(List.of(buildRun("drun_a")), false, DeploymentRun::runId));

        // when
        ApiResponse<CursorPage<DeploymentRunResponse>> response =
                controller.listRuns("dep_manual", null, null, "5", null, null);

        // then（作用域=路径调度器 + 记录响应形状）
        ArgumentCaptor<ListDeploymentRunsQuery> captor = ArgumentCaptor.forClass(ListDeploymentRunsQuery.class);
        verify(applicationService).listRuns(captor.capture());
        assertEquals("dep_manual", captor.getValue().deploymentId());
        DeploymentRunResponse runResponse = response.data().data().get(0);
        assertEquals("drun_a", runResponse.id());
        assertEquals("deployment_run", runResponse.type());
        assertEquals("manual", runResponse.trigger());
        assertEquals("running", runResponse.status());
        assertFalse(response.data().hasMore());
    }

    @Test
    void should_useGlobalScope_when_listAllRuns_given_noDeploymentFilter() {
        // given
        when(applicationService.listRuns(any(ListDeploymentRunsQuery.class)))
                .thenReturn(CursorPage.of(List.<DeploymentRun>of(), false, DeploymentRun::runId));

        // when
        controller.listAllRuns("2026-09-01T00:00:00+08:00", null, null, null, null);

        // then（全局作用域：deploymentId=null、ownerId=当前用户）
        ArgumentCaptor<ListDeploymentRunsQuery> captor = ArgumentCaptor.forClass(ListDeploymentRunsQuery.class);
        verify(applicationService).listRuns(captor.capture());
        assertNull(captor.getValue().deploymentId());
        assertEquals(1L, captor.getValue().ownerId());
        assertEquals(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"), captor.getValue().createdAfter());
    }

    @Test
    void should_delegateRunDetailEndpoints_when_getRun_given_scopes() {
        // given
        when(applicationService.getRun("dep_manual", "drun_a")).thenReturn(buildRun("drun_a"));
        when(applicationService.getRunGlobal("drun_b")).thenReturn(buildRun("drun_b"));

        // when // then（单调度器详情与全局详情均映射 deployment_run 形状）
        assertEquals("drun_a", controller.getRun("dep_manual", "drun_a").data().id());
        assertEquals("drun_b", controller.getRunGlobal("drun_b").data().id());
        verify(applicationService).getRun(eq("dep_manual"), eq("drun_a"));
    }
}
