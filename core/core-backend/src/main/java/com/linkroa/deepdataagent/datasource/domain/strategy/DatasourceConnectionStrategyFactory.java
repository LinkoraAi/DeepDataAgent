package com.linkroa.deepdataagent.datasource.domain.strategy;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;

/**
 * 数据源连接策略工厂接口
 * <p>根据数据源类型获取对应的连接策略实现，
 * 新增数据源类型只需注册新的策略实现即可。</p>
 */
public interface DatasourceConnectionStrategyFactory {

    /**
     * 根据数据源类型获取JDBC连接策略
     *
     * @param jdbcType JDBC子类型
     * @return 对应的连接策略
     */
    DatasourceConnectionStrategy getJdbcStrategy(JdbcType jdbcType);

    /**
     * 获取API连接策略
     *
     * @return API连接策略
     */
    DatasourceConnectionStrategy getApiStrategy();

    /**
     * 根据数据源类型和子类型获取策略
     *
     * @param type     数据源类型
     * @param subType  JDBC子类型（JDBC类型时必填）
     * @return 对应的连接策略
     */
    DatasourceConnectionStrategy getStrategy(DatasourceType type, JdbcType subType);
}