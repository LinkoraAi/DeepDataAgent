package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 调度运行终局语义（决策表「deployment 回写」列的领域表达）。
 * <p>领域层只声明「本轮按什么语义收场」；到 agent BC 发布语言字符串
 * （{@code succeeded / failed / terminated}）的映射由应用层编排完成，
 * 领域 MUST NOT 依赖跨 BC 契约常量。</p>
 */
public enum DeploymentOutcome {

    /** 成功收口（正常收敛回 idle） */
    SUCCEEDED,

    /** 失败收口（迭代上限进 terminated / 执行出错） */
    FAILED,

    /** 中断收口（被中断 / 取消抢跑回退 idle） */
    TERMINATED
}
