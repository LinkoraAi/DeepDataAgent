package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 数据源连接策略工厂实现
 * <p>根据数据源类型获取对应的连接策略实现。
 * 新增数据源类型只需创建新的Strategy实现类并注册到工厂即可。</p>
 */
@Component
public class DatasourceConnectionStrategyFactoryImpl implements DatasourceConnectionStrategyFactory {

    private final Map<JdbcType, DatasourceConnectionStrategy> jdbcStrategies;
    private final DatasourceConnectionStrategy apiStrategy;

    public DatasourceConnectionStrategyFactoryImpl(ApiConnectionStrategy apiStrategy) {
        this.jdbcStrategies = new EnumMap<>(JdbcType.class);
        this.jdbcStrategies.put(JdbcType.MYSQL, new MysqlConnectionStrategy());
        this.jdbcStrategies.put(JdbcType.CLICKHOUSE, new ClickhouseConnectionStrategy());
        this.apiStrategy = apiStrategy;
    }

    @Override
    public DatasourceConnectionStrategy getJdbcStrategy(JdbcType jdbcType) {
        DatasourceConnectionStrategy strategy = jdbcStrategies.get(jdbcType);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的JDBC数据源类型: " + jdbcType);
        }
        return strategy;
    }

    @Override
    public DatasourceConnectionStrategy getApiStrategy() {
        return apiStrategy;
    }

    @Override
    public DatasourceConnectionStrategy getStrategy(DatasourceType type, JdbcType subType) {
        if (type == DatasourceType.API) {
            return getApiStrategy();
        }
        if (type == DatasourceType.JDBC) {
            if (subType == null) {
                throw new IllegalArgumentException("JDBC数据源子类型不能为空");
            }
            return getJdbcStrategy(subType);
        }
        throw new IllegalArgumentException("不支持的数据源类型: " + type);
    }
}
