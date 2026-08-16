package com.linkroa.deepdataagent.agent.domain.service;

/**
 * Agent 版本领域服务：发布号计算
 */
public class AgentVersionDomainService {

    /**
     * 计算下一个发布号（当前最大 + 1）
     * <p>数据库唯一索引 (agent_id, version_number) 兜底并发；业务层分布式部署时
     * 由事务内行锁（SELECT ... FOR UPDATE）串行化同一 Agent 的发布。</p>
     *
     * @param currentMaxVersionNumber 当前最大发布号
     * @return 下一个发布号
     */
    public int nextVersionNumber(int currentMaxVersionNumber) {
        return currentMaxVersionNumber + 1;
    }
}