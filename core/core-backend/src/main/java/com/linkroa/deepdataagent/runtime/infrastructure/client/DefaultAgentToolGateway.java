package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨 BC 工具网关进程内实现：内置只读基础工具集（真实执行器，非 schema 骨架）。
 * <p>首版提供三类自包含工具，用于打通「工具调用 → 流式回传 → 结果生产」全链路：</p>
 * <ul>
 *   <li>{@code echo}：原样回显文本（连通性与参数解析验证）；</li>
 *   <li>{@code current_time}：返回 Asia/Shanghai 当前时间；</li>
 *   <li>{@code calculator}：安全求值四则运算表达式（白名单语法，禁止任意代码执行）。</li>
 * </ul>
 * <p>数据源/API 等业务工具链为后续增量：在本类中扩展工具表，或经跨 BC 应用服务薄委托。</p>
 */
@Component
public class DefaultAgentToolGateway implements AgentToolGateway {

    /** 工具注册表：工具名 → 描述 */
    private final Map<String, ToolDescriptor> tools = Map.of(
            "echo", new ToolDescriptor("echo", "原样回显传入的文本内容，用于验证工具调用与参数解析",
                    parameters(Map.of("text", stringSchema("待回显的文本")), List.of("text"))),
            "current_time", new ToolDescriptor("current_time", "返回当前时间（Asia/Shanghai，ISO-8601）",
                    parameters(Map.of(), List.of())),
            "calculator", new ToolDescriptor("calculator", "安全求值四则运算表达式（支持 + - * / 与括号、小数）",
                    parameters(Map.of("expression", stringSchema("数学表达式，例如 (1+2)*3.5")), List.of("expression")))
    );

    @Override
    public Set<String> availableToolNames() {
        return tools.keySet();
    }

    @Override
    public ToolDescriptor describe(String toolName) {
        ToolDescriptor descriptor = tools.get(toolName);
        if (descriptor == null) {
            throw new IllegalArgumentException("未知工具: " + toolName);
        }
        return descriptor;
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        ToolDescriptor descriptor = describe(toolName);
        return switch (descriptor.name()) {
            case "echo" -> invokeEcho(arguments);
            case "current_time" -> invokeCurrentTime();
            case "calculator" -> invokeCalculator(arguments);
            default -> throw new UnsupportedOperationException("工具执行器未实现: " + toolName);
        };
    }

    // ==================== 工具执行器 ====================

    private String invokeEcho(Map<String, Object> arguments) {
        Object text = arguments.get("text");
        if (text == null || text.toString().isBlank()) {
            throw new IllegalArgumentException("echo 参数 text 不能为空");
        }
        return Map.of("text", text.toString()).toString();
    }

    private String invokeCurrentTime() {
        return Map.of("datetime",
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString()).toString();
    }

    private String invokeCalculator(Map<String, Object> arguments) {
        Object expression = arguments.get("expression");
        if (expression == null) {
            throw new IllegalArgumentException("calculator 参数 expression 不能为空");
        }
        double result = SafeExpressionEvaluator.evaluate(expression.toString());
        // 整数结果去小数点，避免 1+2 → 3.0
        String value = result == Math.floor(result) && !Double.isInfinite(result)
                ? String.valueOf((long) result)
                : String.valueOf(result);
        return Map.of("result", value).toString();
    }

    // ==================== JSON Schema 辅助 ====================

    private static Map<String, Object> parameters(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> stringSchema(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    /**
     * 安全四则运算求值器：仅接受数字 / 小数、+ - * / 与圆括号，遇到非法字符直接拒绝。
     * <p>递归下降解析，无 javaslang/eval 等任何动态执行能力，杜绝注入。</p>
     */
    private static final class SafeExpressionEvaluator {

        private final String source;
        private int pos;

        private SafeExpressionEvaluator(String source) {
            this.source = source;
        }

        static double evaluate(String expression) {
            return new SafeExpressionEvaluator(expression).parseExpression();
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek() == '+') {
                    pos++;
                    value += parseTerm();
                } else if (peek() == '-') {
                    pos++;
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (peek() == '*') {
                    pos++;
                    value *= parseFactor();
                } else if (peek() == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("除数不能为零");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            char c = peek();
            if (c == '(') {
                pos++;
                double value = parseExpression();
                skipWhitespace();
                if (peek() != ')') {
                    throw new IllegalArgumentException("表达式括号不匹配");
                }
                pos++;
                return value;
            }
            if (c == '-') {
                pos++;
                return -parseFactor();
            }
            if (c == '+') {
                pos++;
                return parseFactor();
            }
            if (c == '.' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new IllegalArgumentException("表达式包含非法字符: " + (c == 0 ? "结尾" : String.valueOf(c)));
        }

        private double parseNumber() {
            int start = pos;
            while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
                pos++;
            }
            String token = source.substring(start, pos);
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("非法数字: " + token);
            }
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < source.length() ? source.charAt(pos) : 0;
        }
    }
}