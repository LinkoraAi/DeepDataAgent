package com.linkroa.deepdataagent.agent.exception;

/**
 * 数据分析模块异常
 */
public class DataAnalysisException extends RuntimeException {

    public DataAnalysisException(String message) {
        super(message);
    }

    public DataAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
