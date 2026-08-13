package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.service.DataAnalysisApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.DataAnalysisRequest;
import com.linkroa.deepdataagent.agent.controller.request.StopAnalysisRequest;
import com.linkroa.deepdataagent.agent.controller.response.DataAnalysisResponse;
import com.linkroa.deepdataagent.shared.exception.SSENotConnectedException;
import com.linkroa.deepdataagent.shared.exception.SessionNotRunningException;
import com.linkroa.deepdataagent.shared.exception.SystemBusyException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据分析 REST 控制器
 * <p>提供数据分析接口。控制器只负责请求验证、命令构建、调用应用服务与异常映射，
 * 具体的编排逻辑（SSE 连接、事件总线、执行池）已下沉到 {@link DataAnalysisApplicationService#executeAnalysis}。</p>
 */
@RestController
@RequestMapping("/api/agent/data-analysis")
public class
DataAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisController.class);

    private final DataAnalysisApplicationService applicationService;

    public DataAnalysisController(DataAnalysisApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 执行数据分析
     * <p>接收分析请求，将请求对象转换为命令对象，调用应用服务执行分析。</p>
     *
     * @param request 分析请求
     * @return 分析响应，包含 sessionId
     */
    @PostMapping("/analyze")
    public ApiResponse<DataAnalysisResponse> analyze(@Valid @RequestBody DataAnalysisRequest request) {
        DataAnalysisCommand command = toCommand(request);
        try {
            DataAnalysisApplicationService.AnalysisExecutionResult result =
                    applicationService.executeAnalysis(command, request.clientId());
            return ApiResponse.success(new DataAnalysisResponse(result.sessionId(), result.message()));
        } catch (SSENotConnectedException e) {
            return ApiResponse.error("400", e.getMessage());
        } catch (SessionNotRunningException e) {
            return ApiResponse.error("404", e.getMessage());
        } catch (SystemBusyException e) {
            return ApiResponse.error("400", e.getMessage());
        }
    }

    /**
     * 停止数据分析
     * <p>根据会话 ID 停止进行中的分析，后端真正取消 agent 运行并强制写库（CANCELLED）。
     * 无进行中的分析时返回提示。</p>
     *
     * @param request 停止请求
     * @return 停止结果
     */
    @PostMapping("/stop")
    public ApiResponse<Void> stop(@Valid @RequestBody StopAnalysisRequest request) {
        boolean stopped = applicationService.stopAnalysis(request.sessionId());
        return stopped
                ? ApiResponse.success(null)
                : ApiResponse.error("404", "没有进行中的分析");
    }

    /**
     * 将请求对象转换为命令对象
     * <p>控制器层负责请求对象与命令对象的转换，避免应用层反向依赖控制器层。</p>
     *
     * @param request 分析请求
     * @return 分析命令
     */
    private DataAnalysisCommand toCommand(DataAnalysisRequest request) {
        return new DataAnalysisCommand(
                request.sessionId(),
                request.modelConfigId(),
                request.connectionId(),
                request.userQuestion(),
                request.enableWebSearch(),
                request.resumeOnly()
        );
    }
}