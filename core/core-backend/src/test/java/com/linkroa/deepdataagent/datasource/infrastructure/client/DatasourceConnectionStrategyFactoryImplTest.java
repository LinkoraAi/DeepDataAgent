package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DatasourceConnectionStrategyFactoryImplTest {

    @Mock
    private ApiConnectionStrategy apiStrategy;

    private DatasourceConnectionStrategyFactoryImpl factory;

    @BeforeEach
    void setUp() {
        factory = new DatasourceConnectionStrategyFactoryImpl(apiStrategy);
    }

    @Test
    void should_returnMysqlStrategy_when_getStrategy_givenJdbcMysqlType() {
        DatasourceConnectionStrategy strategy = factory.getStrategy(DatasourceType.JDBC, JdbcType.MYSQL);

        assertTrue(strategy instanceof MysqlConnectionStrategy);
    }

    @Test
    void should_returnClickhouseStrategy_when_getStrategy_givenJdbcClickhouseType() {
        DatasourceConnectionStrategy strategy = factory.getStrategy(DatasourceType.JDBC, JdbcType.CLICKHOUSE);

        assertTrue(strategy instanceof ClickhouseConnectionStrategy);
    }

    @Test
    void should_returnApiStrategy_when_getStrategy_givenApiType() {
        DatasourceConnectionStrategy strategy = factory.getStrategy(DatasourceType.API, null);

        assertSame(apiStrategy, strategy);
    }

    @Test
    void should_throwException_when_getStrategy_givenJdbcWithNullSubtype() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getStrategy(DatasourceType.JDBC, null)
        );

        assertEquals("JDBC数据源子类型不能为空", ex.getMessage());
    }

    @Test
    void should_throwException_when_getJdbcStrategy_givenUnsupportedJdbcType() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getJdbcStrategy(null)
        );

        assertEquals("不支持的JDBC数据源类型: null", ex.getMessage());
    }

    @Test
    void should_returnSameStrategyInstance_when_getJdbcStrategy_givenSameJdbcType() {
        DatasourceConnectionStrategy strategy1 = factory.getJdbcStrategy(JdbcType.MYSQL);
        DatasourceConnectionStrategy strategy2 = factory.getJdbcStrategy(JdbcType.MYSQL);

        assertSame(strategy1, strategy2);
    }

    @Test
    void should_returnApiStrategy_when_getApiStrategy_givenValidRequest() {
        DatasourceConnectionStrategy strategy = factory.getApiStrategy();

        assertSame(apiStrategy, strategy);
    }
}
