package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话实体 MyBatis-Plus Mapper
 * <p>提供活跃会话计数和标题更新的 SQL 映射。</p>
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    /**
     * 统计活跃会话数量
     *
     * @return 活跃会话数
     */
    @Select("SELECT COUNT(*) FROM agent_session WHERE status = 'ACTIVE' AND is_deleted = 0")
    Integer countActiveSessions();

    /**
     * 更新会话标题
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    @Update("UPDATE agent_session SET title = #{title}, updated_time = datetime('now') WHERE id = #{sessionId} AND is_deleted = 0")
    void updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
}
