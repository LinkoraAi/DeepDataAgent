package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent 会话 Mapper
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    /**
     * 查询所有活跃会话
     */
    List<AgentSessionEntity> selectActiveSessions();

    /**
     * 更新会话消息计数和最后消息时间
     */
    @Update("UPDATE agent_session SET message_count = message_count + 1, last_message_at = #{lastMessageAt}, updated_at = datetime('now') WHERE id = #{sessionId}")
    int incrementMessageCount(@Param("sessionId") String sessionId, @Param("lastMessageAt") String lastMessageAt);

    /**
     * 关闭会话
     */
    @Update("UPDATE agent_session SET status = 'closed', closed_at = datetime('now'), updated_at = datetime('now') WHERE id = #{sessionId}")
    int closeSession(@Param("sessionId") String sessionId);

    /**
     * 更新会话标题
     */
    @Update("UPDATE agent_session SET title = #{title}, updated_at = datetime('now') WHERE id = #{sessionId}")
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /**
     * 统计活跃会话数量
     */
    @Select("SELECT COUNT(*) FROM agent_session WHERE status = 'active' AND is_deleted = 0")
    int countActiveSessions();
}
