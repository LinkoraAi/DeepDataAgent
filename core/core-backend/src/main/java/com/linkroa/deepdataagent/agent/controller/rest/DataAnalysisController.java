package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.assembler.DataAnalysisCommandAssembler;
import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.application.event.AnalysisEvent;
import com.linkroa.deepdataagent.agent.application.service.DataAnalysisApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.DataAnalysisRequest;
import com.linkroa.deepdataagent.agent.controller.response.DataAnalysisResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * 数据分析 REST 控制器
 * <p>提供同步和 SSE 流式两种分析接口。</p>
 */
@RestController
@RequestMapping("/agent/data-analysis")
public class DataAnalysisController {

    private final DataAnalysisApplicationService applicationService;

    public DataAnalysisController(DataAnalysisApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 流式执行数据分析（SSE）
     * <p>前端可通过 EventSource 接收实时分析进度。</p>
     */
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@Valid @RequestBody DataAnalysisRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        DataAnalysisCommand command = DataAnalysisCommandAssembler.toCommand(request);
        Flux<AnalysisEvent> eventFlux = applicationService.executeStream(command);

        eventFlux.subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type())
                                .data(event, MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }

    /**
     * 同步执行数据分析（向后兼容）
     */
    @PostMapping("/analyze-sync")
    public ApiResponse<DataAnalysisResponse> analyze(@Valid @RequestBody DataAnalysisRequest request) {
        DataAnalysisCommand command = DataAnalysisCommandAssembler.toCommand(request);
        DataAnalysisResponse response = DataAnalysisResponse.from(applicationService.execute(command));
        return ApiResponse.success(response);
    }
}
