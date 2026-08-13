package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ApiExpressionEvaluator {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    public String evaluateString(String input, Map<String, Object> context) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        Matcher matcher = EXPRESSION_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = evaluateVariable(matcher.group(1), context);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String evaluate(String expression, Map<String, Object> context) {
        return evaluateVariable(expression, context);
    }

    private String evaluateVariable(String expression, Map<String, Object> context) {
        if (context != null && context.containsKey(expression)) {
            Object value = context.get(expression);
            return value == null ? "" : value.toString();
        }

        LocalDateTime now = LocalDateTime.now();
        return switch (expression) {
            case "today", "yyyy-MM-dd" -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "yyyyMMdd" -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case "yyyy-MM-dd HH:mm:ss" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            case "timestamp" -> String.valueOf(System.currentTimeMillis());
            case "year" -> String.valueOf(now.getYear());
            case "month" -> String.valueOf(now.getMonthValue());
            case "day" -> String.valueOf(now.getDayOfMonth());
            default -> evaluateDateExpression(expression, now);
        };
    }

    private String evaluateDateExpression(String expression, LocalDateTime now) {
        if (expression.startsWith("today")) {
            return evaluateTodayExpression(expression);
        }
        if (expression.contains(":")) {
            String[] parts = expression.split(":", 2);
            String format = parts[0];
            String operation = parts[1];
            LocalDateTime result = applyOffset(now, operation);
            try {
                return result.format(DateTimeFormatter.ofPattern(format));
            } catch (IllegalArgumentException e) {
                return "${" + expression + "}";
            }
        }
        try {
            return now.format(DateTimeFormatter.ofPattern(expression));
        } catch (IllegalArgumentException e) {
            return "${" + expression + "}";
        }
    }

    private String evaluateTodayExpression(String expression) {
        LocalDate date = LocalDate.now();
        if (expression.equals("today")) {
            return date.toString();
        }
        if (expression.matches("today[+-]\\d+")) {
            int offset = Integer.parseInt(expression.substring(5));
            return date.plusDays(offset).toString();
        }
        return "${" + expression + "}";
    }

    private LocalDateTime applyOffset(LocalDateTime now, String operation) {
        if (operation == null || operation.length() < 3) {
            return now;
        }
        int sign = operation.startsWith("-") ? -1 : 1;
        String amountPart = operation.substring(1, operation.length() - 1);
        int amount = Integer.parseInt(amountPart);
        char unit = operation.charAt(operation.length() - 1);
        return switch (unit) {
            case 'd' -> now.plusDays(sign * amount);
            case 'M' -> now.plusMonths(sign * amount);
            case 'y' -> now.plusYears(sign * amount);
            case 'h' -> now.plusHours(sign * amount);
            case 'm' -> now.plusMinutes(sign * amount);
            default -> now;
        };
    }
}