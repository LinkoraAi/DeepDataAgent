package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.port.SessionEnvironmentVariablesValidationPort;
import com.linkroa.deepdataagent.runtime.application.validation.SessionEnvironmentVariablesValidator;
import org.springframework.stereotype.Component;

/**
 * {@link SessionEnvironmentVariablesValidationPort} 进程内实现：agent BC 内唯一直接依赖
 * runtime 侧会话环境变量校验器的装配适配点，判定口径的唯一权威留在 runtime
 * （Session 创建 / 更新两条写入路径的 400 语义）。
 *
 * <p>纯委托、零逻辑：变量名形态、值必须为字符串、保留名 / 前缀、单值 / 条数 / 总字节等判定项
 * 全部在 runtime 校验器内保证，本类原样透传其 {@code IllegalArgumentException}（400 语义）。</p>
 */
@Component
public class DefaultSessionEnvironmentVariablesValidationPort
        implements SessionEnvironmentVariablesValidationPort {

    @Override
    public void validate(String environmentVariablesJson) {
        SessionEnvironmentVariablesValidator.validate(environmentVariablesJson);
    }
}