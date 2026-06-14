package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.DataAnalysisCommand;
import com.linkroa.deepdataagent.agent.controller.request.DataAnalysisRequest;

/**
 * 命令组装器
 */
public final class DataAnalysisCommandAssembler {

    private DataAnalysisCommandAssembler() {
    }

    public static DataAnalysisCommand toCommand(DataAnalysisRequest request) {
        return new DataAnalysisCommand(request.sessionId(), request.modelConfigId(), request.connectionId(), request.userQuestion());
    }
}
