package com.linkroa.deepdataagent.agent.domain.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataSummaryBuilder 单元测试
 * <p>测试数据摘要构建的各种场景，包括空数据、数值统计、日期解析等。</p>
 */
class DataSummaryBuilderTest {

    private DataSummaryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DataSummaryBuilder(new ObjectMapper());
    }

    // ==================== build ====================

    @Test
    void should_returnNoData_when_build_given_emptyList() {
        // given
        List<Map<String, Object>> data = List.of();

        // when
        String result = builder.build(data);

        // then
        assertEquals("无数据", result);
    }

    @Test
    void should_returnNoData_when_build_given_null() {
        // when
        String result = builder.build(null);

        // then
        assertEquals("无数据", result);
    }

    @Test
    void should_returnRowCountAndFieldInfo_when_build_given_validData() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("name", "Alice", "age", 30, "score", 95.5)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("总行数: 1"));
        assertTrue(result.contains("字段信息:"));
        assertTrue(result.contains("name: String"));
        assertTrue(result.contains("age: Integer"));
        assertTrue(result.contains("score: Double"));
    }

    @Test
    void should_includeNumberStats_when_build_given_numericColumns() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("id", 1, "value", 100),
                Map.of("id", 2, "value", 200),
                Map.of("id", 3, "value", 300)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("数值列统计:"));
        assertTrue(result.contains("value:"));
        assertTrue(result.contains("总和=600.00"));
        assertTrue(result.contains("平均=200.00"));
        assertTrue(result.contains("最大=300.00"));
        assertTrue(result.contains("最小=100.00"));
    }

    @Test
    void should_skipNonNumericColumns_when_build_given_mixedData() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("name", "Alice", "age", 30)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("数值列统计:"));
        assertTrue(result.contains("age:"));
        // name 是字符串，不应出现在数值统计中
        assertFalse(result.contains("name: 总和="));
    }

    @Test
    void should_handleNullValues_when_build_given_nullInNumericColumn() {
        // given
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 1);
        row1.put("value", 100);

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 2);
        row2.put("value", null);

        Map<String, Object> row3 = new LinkedHashMap<>();
        row3.put("id", 3);
        row3.put("value", 300);

        List<Map<String, Object>> data = List.of(row1, row2, row3);

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("数值列统计:"));
        // 应该只统计非null的数值
        assertTrue(result.contains("value:"));
    }

    @Test
    void should_detectTimeRange_when_build_given_dateColumn() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("date", LocalDate.of(2025, 3, 15), "value", 100),
                Map.of("date", LocalDate.of(2025, 1, 1), "value", 200),
                Map.of("date", LocalDate.of(2025, 6, 30), "value", 300)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("时间范围:"));
        assertTrue(result.contains("2025-01-01"));
        assertTrue(result.contains("2025-06-30"));
    }

    @Test
    void should_detectTimeRangeFromStringDates_when_build_given_stringDateColumn() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("date", "2025-03-15", "value", 100),
                Map.of("date", "2025-01-01", "value", 200),
                Map.of("date", "2025-06-30", "value", 300)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("时间范围:"));
        assertTrue(result.contains("2025-01-01"));
        assertTrue(result.contains("2025-06-30"));
    }

    @Test
    void should_detectTimeRangeFromSlashDateStrings_when_build_given_slashDateFormat() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("date", "2025/3/15", "value", 100),
                Map.of("date", "2025/1/1", "value", 200)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("时间范围:"));
    }

    @Test
    void should_handleLocalDateTime_when_build_given_localDateTimeColumn() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("datetime", LocalDateTime.of(2025, 1, 1, 10, 0), "value", 100),
                Map.of("datetime", LocalDateTime.of(2025, 6, 30, 18, 0), "value", 200)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("时间范围:"));
        assertTrue(result.contains("2025-01-01"));
        assertTrue(result.contains("2025-06-30"));
    }

    @Test
    void should_limitSampleTo10Rows_when_build_given_moreThan10Rows() {
        // given
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            data.add(Map.of("id", i, "value", i * 10));
        }

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("样本数据 (前 10 行):"));
    }

    @Test
    void should_includeAllRowsAsSample_when_build_given_lessThan10Rows() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("id", 1, "value", 100),
                Map.of("id", 2, "value", 200),
                Map.of("id", 3, "value", 300)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("样本数据 (前 3 行):"));
        assertTrue(result.contains("100"));
        assertTrue(result.contains("200"));
        assertTrue(result.contains("300"));
    }

    @Test
    void should_showUnknownType_when_build_given_nullFirstValue() {
        // given
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", null);
        row.put("age", 30);

        List<Map<String, Object>> data = List.of(row);

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("name: unknown"));
        assertTrue(result.contains("age: Integer"));
    }

    @Test
    void should_noTimeRange_when_build_given_noDateColumn() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("name", "Alice", "age", 30)
        );

        // when
        String result = builder.build(data);

        // then
        assertFalse(result.contains("时间范围:"));
    }

    @Test
    void should_handleInvalidDateString_when_build_given_mixedDateStringFormat() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("date", "2025-01-01", "value", 100),
                Map.of("date", "invalid-date", "value", 200),
                Map.of("date", "2025-12-31", "value", 300)
        );

        // when
        String result = builder.build(data);

        // then
        // 应该仍然能检测到时间范围（忽略无效日期）
        assertTrue(result.contains("时间范围:"));
    }

    @Test
    void should_handleSingleRow_when_build_given_singleRowData() {
        // given
        List<Map<String, Object>> data = List.of(
                Map.of("id", 1, "name", "Alice", "score", 95.5)
        );

        // when
        String result = builder.build(data);

        // then
        assertTrue(result.contains("总行数: 1"));
        assertTrue(result.contains("样本数据 (前 1 行):"));
    }
}
