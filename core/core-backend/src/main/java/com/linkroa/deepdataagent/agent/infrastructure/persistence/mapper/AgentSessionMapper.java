package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话实体 MyBatis-Plus Mapper
 * <p>提供活跃会话计数和标题更新的 SQL 映射，SQL 定义见 AgentSessionMapper.xml。</p>
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    /**
     * 统计活跃会话数量
     *
     * @return 活跃会话数
     */
    Integer countActiveSessions();

    /**
     * 更新会话标题
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    void updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
}
