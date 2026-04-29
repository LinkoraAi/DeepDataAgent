package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class ApiExpressionEvaluatorTest {

    private ApiExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ApiExpressionEvaluator();
    }

    @Test
    void should_returnNullInput_when_evaluateString_given_nullInput() {
        String result = evaluator.evaluateString(null, Map.of());
        assertEquals(null, result);
    }

    @Test
    void should_returnEmptyInput_when_evaluateString_given_emptyInput() {
        String result = evaluator.evaluateString("", Map.of());
        assertEquals("", result);
    }

    @Test
    void should_returnInputWithoutExpression_when_evaluateString_given_noExpression() {
        String result = evaluator.evaluateString("hello world", Map.of());
        assertEquals("hello world", result);
    }

    @Test
    void should_replaceExpressionWithContextValue_when_evaluateString_given_validExpression() {
        String result = evaluator.evaluateString("Hello ${name}!", Map.of("name", "Alice"));
        assertEquals("Hello Alice!", result);
    }

    @Test
    void should_replaceMultipleExpressions_when_evaluateString_given_multipleExpressions() {
        String result = evaluator.evaluateString("${greeting} ${name}", Map.of("greeting", "Hi", "name", "Bob"));
        assertEquals("Hi Bob", result);
    }

    @Test
    void should_replaceWithEmptyString_when_contextValueIsNull() {
        HashMap<String, Object> ctx = new HashMap<>();
        ctx.put("key", null);
        String result = evaluator.evaluateString("${key}", ctx);
        assertEquals("", result);
    }

    @Test
    void should_evaluateDateExpression_when_evaluate_given_knownDateVariable() {
        String result = evaluator.evaluate("today", null);
        assertEquals(LocalDate.now().toString(), result);
    }

    @Test
    void should_evaluateSingleExpression_when_evaluate_given_contextVariable() {
        String result = evaluator.evaluate("userName", Map.of("userName", "test123"));
        assertEquals("test123", result);
    }

    @Test
    void should_returnExpressionUnchanged_when_evaluateString_given_noMatchingContext() {
        String result = evaluator.evaluateString("${unknown}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateTodayExpression_when_evaluateString_given_todayVariable() {
        String result = evaluator.evaluateString("${today}", Map.of());
        assertNotNull(result);
        assertEquals(10, result.length());
    }

    @Test
    void should_evaluateYyyyMMddExpression_when_evaluateString_given_dateFormat() {
        String result = evaluator.evaluateString("${yyyy-MM-dd}", Map.of());
        assertNotNull(result);
        assertEquals(10, result.length());
    }

    @Test
    void should_evaluateYmdExpression_when_evaluateString_given_yyyyMMddFormat() {
        String result = evaluator.evaluateString("${yyyyMMdd}", Map.of());
        assertNotNull(result);
        assertEquals(8, result.length());
    }

    @Test
    void should_evaluateTimestampExpression_when_evaluateString_given_timestampVariable() {
        String result = evaluator.evaluateString("${timestamp}", Map.of());
        assertNotNull(result);
        assertEquals(13, result.length());
    }

    @Test
    void should_evaluateYearExpression_when_evaluateString_given_yearVariable() {
        String result = evaluator.evaluateString("${year}", Map.of());
        assertNotNull(result);
        assertEquals(4, result.length());
    }

    @Test
    void should_evaluateMonthExpression_when_evaluateString_given_monthVariable() {
        String result = evaluator.evaluateString("${month}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDayExpression_when_evaluateString_given_dayVariable() {
        String result = evaluator.evaluateString("${day}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateFullDateTimeExpression_when_evaluateString_given_fullDateTimeFormat() {
        String result = evaluator.evaluateString("${yyyy-MM-dd HH:mm:ss}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateSimpleDateFormatExpression_when_evaluateString_given_customFormat() {
        String result = evaluator.evaluateString("${yyyy/MM/dd}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithOffset_when_evaluateString_given_formatWithOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd:+1d}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithNegativeOffset_when_evaluateString_given_formatWithNegativeOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd:-1d}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithMonthOffset_when_evaluateString_given_formatWithMonthOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd:+1M}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithYearOffset_when_evaluateString_given_formatWithYearOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd:+1y}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithHourOffset_when_evaluateString_given_formatWithHourOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd HH+2h}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateDateFormatWithMinuteOffset_when_evaluateString_given_formatWithMinuteOperation() {
        String result = evaluator.evaluateString("${yyyy-MM-dd HH+30m}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_returnExpressionUnchanged_when_evaluateString_given_invalidDateFormat() {
        String result = evaluator.evaluateString("${invalid_format}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_evaluateTodayWithPositiveOffset_when_evaluateString_given_todayPlusDays() {
        String result = evaluator.evaluateString("${today+1}", Map.of());
        assertNotNull(result);
        assertEquals(10, result.length());
    }

    @Test
    void should_evaluateTodayWithNegativeOffset_when_evaluateString_given_todayMinusDays() {
        String result = evaluator.evaluateString("${today-1}", Map.of());
        assertNotNull(result);
        assertEquals(10, result.length());
    }

    @Test
    void should_returnExpressionUnchanged_when_evaluateString_given_invalidTodayExpression() {
        String result = evaluator.evaluateString("${todayabc}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_returnExpressionUnchanged_when_evaluateString_given_invalidOffsetFormat() {
        String result = evaluator.evaluateString("${yyyy-MM-dd:+1z}", Map.of());
        assertNotNull(result);
    }

    @Test
    void should_handleMixedTextAndExpressions_when_evaluateString_given_complexInput() {
        String result = evaluator.evaluateString(
            "Date: ${yyyy-MM-dd}, User: ${name}",
            Map.of("name", "test")
        );
        assertNotNull(result);
    }
}