package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentModelInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 模型信息 Mapper（合并原 provider + info + config）
 */
@Mapper
public interface AgentModelInfoMapper extends BaseMapper<AgentModelInfoEntity> {

    @Select("SELECT * FROM agent_model_info WHERE is_enabled = 1 AND is_deleted = 0 ORDER BY created_time ASC")
    List<AgentModelInfoEntity> selectAllEnabled();

    @Select("SELECT * FROM agent_model_info WHERE is_default = 1 AND is_enabled = 1 AND is_deleted = 0 LIMIT 1")
    AgentModelInfoEntity selectDefault();

    @Select("SELECT * FROM agent_model_info WHERE provider_name = #{providerName} AND is_deleted = 0 ORDER BY sort_order ASC")
    List<AgentModelInfoEntity> selectByProviderName(@Param("providerName") String providerName);

    /** 查询所有启用的服务商（去重，取每个 provider_name 的第一条记录） */
    @Select("SELECT * FROM agent_model_info WHERE is_enabled = 1 AND is_deleted = 0 GROUP BY provider_name ORDER BY MIN(created_time) ASC")
    List<AgentModelInfoEntity> selectDistinctProviders();

    @Update("UPDATE agent_model_info SET is_deleted = 1, updated_time = datetime('now') WHERE id = #{id}")
    int markDeleted(@Param("id") Long id);
}
