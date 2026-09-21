package com.linkroa.deepdataagent.shared.exception;

/**
 * 检索附图入参非法异常（映射真实 HTTP 400）。
 * <p>检索 REST 端点（含共享校验口径的进程内入口）接收请求时同步校验内联附图
 * （数量上限、单图字节、contentType 白名单、base64 可解码性与文件头魔数真实性），
 * 任一附件非法整请求拒绝，错误信息含附件序号与原因定位。</p>
 * <p>与一般业务异常（{@link DeepDataAgentException} 映射 HTTP 200 + 响应体 code="400"）
 * 刻意区分：本异常属<b>客户端入参错误</b>，全局异常处理器以真实 HTTP 400 状态码发布
 * （先例：{@code InvalidApiVersionException} handler）。继承 {@link DeepDataAgentException}
 * 保证既有按父类型捕获/断言的调用点子类型兼容，Spring 按最具体类型选中本异常的专属 handler。</p>
 *
 * @author DeepDataAgent
 */
public class InvalidQueryImageException extends DeepDataAgentException {

    /**
     * 构造附图非法异常。
     *
     * @param message 含附件序号与拒绝原因的定位信息
     */
    public InvalidQueryImageException(String message) {
        super(message);
    }
}
