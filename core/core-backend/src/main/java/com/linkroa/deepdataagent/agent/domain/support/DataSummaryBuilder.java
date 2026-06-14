package com.linkroa.deepdataagent.agent.domain.support;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据摘要构建器
 * <p>将查询结果数据转换为适合 LLM 分析的摘要格式，包含统计信息和样本数据。</p>
 */
@Component
public class DataSummaryBuilder {

    private static final DateTimeFormatter SLASH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d");

    private final ObjectMapper objectMapper;

    public DataSummaryBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构建数据摘要
     *
     * @param data 查询结果数据
     * @return 数据摘要文本
     */
    public String build(List<Map<String, Object>> data) {
        if (ObjectUtils.isEmpty(data)) {
            return "无数据";
        }

        StringBuilder sb = new StringBuilder();
        int rowCount = data.size();
        sb.append(String.format("总行数: %d\n", rowCount));

        // 字段类型信息
        Map<String, Object> firstRow = data.get(0);
        sb.append("字段信息:\n");
        for (Map.Entry<String, Object> entry : firstRow.entrySet()) {
            String fieldName = entry.getKey();
            Object firstValue = entry.getValue();
            String typeName = firstValue != null ? firstValue.getClass().getSimpleName() : "unknown";
            sb.append(String.format("  - %s: %s\n", fieldName, typeName));
        }

        // 数值列统计
        sb.append("\n数值列统计:\n");
        for (String fieldName : firstRow.keySet()) {
            List<Number> numbers = collectNumbers(data, fieldName);
            if (!numbers.isEmpty()) {
                DoubleSummaryStatistics stats = numbers.stream()
                        .collect(Collectors.summarizingDouble(Number::doubleValue));
                sb.append(String.format("  - %s: 总和=%.2f, 平均=%.2f, 最大=%.2f, 最小=%.2f\n",
                        fieldName, stats.getSum(), stats.getAverage(), stats.getMax(), stats.getMin()));
            }
        }

        // 时间范围检测
        detectTimeRange(data).ifPresent(range -> sb.append(String.format("\n时间范围: %s\n", range)));

        // 前10行样本数据
        int maxRows = Math.min(data.size(), 10);
        sb.append(String.format("\n样本数据 (前 %d 行):\n", maxRows));
        try {
            String sampleJson = objectMapper.writeValueAsString(data.subList(0, maxRows));
            sb.append(sampleJson);
        } catch (Exception e) {
            sb.append("数据序列化失败");
        }

        return sb.toString();
    }

    private List<Number> collectNumbers(List<Map<String, Object>> data, String fieldName) {
        List<Number> numbers = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Object value = row.get(fieldName);
            if (value instanceof Number num) {
                numbers.add(num);
            }
        }
        return numbers;
    }

    private Optional<String> detectTimeRange(List<Map<String, Object>> data) {
        for (String fieldName : data.get(0).keySet()) {
            List<LocalDate> dates = collectDates(data, fieldName);
            if (!dates.isEmpty()) {
                Collections.sort(dates);
                return Optional.of(dates.get(0) + " 至 " + dates.get(dates.size() - 1));
            }
        }
        return Optional.empty();
    }

    private List<LocalDate> collectDates(List<Map<String, Object>> data, String fieldName) {
        List<LocalDate> dates = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Object value = row.get(fieldName);
            LocalDate date = parseDate(value);
            if (date != null) {
                dates.add(date);
            }
        }
        return dates;
    }

    private LocalDate parseDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (value instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (DateTimeParseException ignored) {
                // 尝试其他常见格式
                try {
                    return LocalDate.parse(s, SLASH_DATE_FORMAT);
                } catch (DateTimeParseException ignored2) {
                    return null;
                }
            }
        }
        return null;
    }
}
