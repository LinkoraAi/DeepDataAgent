package com.linkroa.deepdataagent.rag.domain.port;

/**
 * 模型调用失败分类（观测与归因用）。
 * <p>由基础设施层按异常因果链判定，随调用级日志与失败异常向上传递，
 * 供摄入汇总日志统计失败原因分布。</p>
 *
 * @author DeepDataAgent
 */
public enum LlmFailureKind {

    /** 超时：框架超时、HTTP 读超时或消息含 timeout 语义 */
    TIMEOUT,

    /** 连接失败：网络不可达、DNS 失败、连接被拒绝等 */
    CONNECTION,

    /** 限流：HTTP 429 */
    RATE_LIMIT,

    /** 服务端错误：HTTP 5xx */
    SERVER_ERROR,

    /** 客户端错误：非 429 的 4xx（参数非法、鉴权失败、资源不存在等） */
    CLIENT_ERROR,

    /** 未归类失败 */
    UNKNOWN
}
