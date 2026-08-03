package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.service.DataAnalysisApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.DataAnalysisRequest;
import com.linkroa.deepdataagent.agent.controller.response.DataAnalysisResponse;
import com.linkroa.deepdataagent.shared.exception.SSENotConnectedException;
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
@RequestMapping("/agent/data-analysis")
public class DataAnalysisController {

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
        } catch (SystemBusyException e) {
            return ApiResponse.error("400", e.getMessage());
        }
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
                request.enableWebSearch()
        );
    }
}