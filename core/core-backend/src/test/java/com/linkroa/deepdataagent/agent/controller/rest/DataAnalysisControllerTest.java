package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.service.DataAnalysisApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.DataAnalysisRequest;
import com.linkroa.deepdataagent.shared.exception.SSENotConnectedException;
import com.linkroa.deepdataagent.shared.exception.SystemBusyException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DataAnalysisController 单元测试
 * <p>测试简化后的控制器逻辑：请求转命令、调用应用服务、异常映射。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataAnalysisControllerTest {

    @Mock
    private DataAnalysisApplicationService applicationService;

    @Test
    void should_returnError_when_analyze_given_clientNotConnected() {
        // given
        when(applicationService.executeAnalysis(any(), any()))
                .thenThrow(new SSENotConnectedException("客户端未连接 SSE，请先建立 SSE 连接"));

        DataAnalysisController controller = new DataAnalysisController(applicationService);

        DataAnalysisRequest request = new DataAnalysisRequest(
                "session-123", 1L, "1", "测试问题", false, "client-1");

        // when
        ApiResponse<?> response = controller.analyze(request);

        // then
        assertNotNull(response);
        assertEquals("400", response.code());
        assertEquals("客户端未连接 SSE，请先建立 SSE 连接", response.message());
    }

    @Test
    void should_returnSuccess_when_analyze_given_validRequest() {
        // given
        when(applicationService.executeAnalysis(any(), eq("client-1")))
                .thenReturn(new DataAnalysisApplicationService.AnalysisExecutionResult("session-123", "分析完成"));

        DataAnalysisController controller = new DataAnalysisController(applicationService);

        DataAnalysisRequest request = new DataAnalysisRequest(
                "session-123", 1L, "1", "测试问题", false, "client-1");

        // when
        ApiResponse<?> response = controller.analyze(request);

        // then
        assertNotNull(response);
        assertEquals("200", response.code());
        assertNotNull(response.data());
    }

    @Test
    void should_returnError_when_analyze_given_poolExhausted() {
        // given
        when(applicationService.executeAnalysis(any(), any()))
                .thenThrow(new SystemBusyException("系统繁忙，请稍后重试"));

        DataAnalysisController controller = new DataAnalysisController(applicationService);

        DataAnalysisRequest request = new DataAnalysisRequest(
                "session-123", 1L, "1", "测试问题", false, "client-1");

        // when
        ApiResponse<?> response = controller.analyze(request);

        // then
        assertNotNull(response);
        assertEquals("400", response.code());
        assertEquals("系统繁忙，请稍后重试", response.message());
    }

    @Test
    void should_buildCommand_given_requestHasWebSearch() {
        // given
        when(applicationService.executeAnalysis(any(), eq("client-2")))
                .thenReturn(new DataAnalysisApplicationService.AnalysisExecutionResult("session-456", "分析完成"));

        DataAnalysisController controller = new DataAnalysisController(applicationService);

        DataAnalysisRequest request = new DataAnalysisRequest(
                "session-456", 2L, "conn-2", "请联网搜索", true, "client-2");

        // when
        ApiResponse<?> response = controller.analyze(request);

        // then
        assertNotNull(response);
        assertEquals("200", response.code());
        // 验证命令正确传递（通过捕获 executeAnalysis 参数）
        org.mockito.ArgumentCaptor<DataAnalysisCommand> captor =
                org.mockito.ArgumentCaptor.forClass(DataAnalysisCommand.class);
        org.mockito.Mockito.verify(applicationService).executeAnalysis(captor.capture(), eq("client-2"));
        DataAnalysisCommand command = captor.getValue();
        assertEquals("session-456", command.sessionId());
        assertEquals(2L, command.modelConfigId());
        assertEquals("conn-2", command.connectionId());
        assertEquals("请联网搜索", command.userQuestion());
        assertEquals(true, command.enableWebSearch());
    }
}