package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型配置 Mapper（合并原 provider + info + config）
 * <p>SQL 定义见 ModelConfigMapper.xml。</p>
 */
@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfigEntity> {

    /**
     * 查询所有已启用的模型配置
     *
     * @return 已启用的模型配置列表
     */
    List<ModelConfigEntity> selectAllEnabled();

    /**
     * 查询默认模型配置
     *
     * @return 默认模型配置，无默认时返回 null
     */
    ModelConfigEntity selectDefault();

    /**
     * 按服务商名称查询模型配置
     *
     * @param providerName 服务商名称
     * @return 匹配的模型配置列表
     */
    List<ModelConfigEntity> selectByProviderName(@Param("providerName") String providerName);

    /**
     * 查询所有已启用的服务商（去重，取每个 provider_name 的第一条记录）
     *
     * @return 去重后的模型配置列表
     */
    List<ModelConfigEntity> selectProviders();

    /**
     * 逻辑删除模型配置
     *
     * @param id 模型配置 ID
     * @return 影响行数
     */
    int markDeleted(@Param("id") Long id);
}