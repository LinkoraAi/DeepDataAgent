package com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptTemplates} 单元测试（随迁至 catalog 包）。
 * <p>校验集中常量表自身的自洽性：模板名收录数量、名称集合无重复、
 * 协议分隔符字面量、全部模板名常量与 {@code TEMPLATE_NAMES} 集合的一致性冒烟。</p>
 */
class PromptTemplatesTest {

    /** 分隔符常量字段名（非模板名，不参与 TEMPLATE_NAMES 收录校验） */
    private static final List<String> DELIMITER_FIELD_NAMES = List.of("TUPLE_DELIMITER", "COMPLETION_DELIMITER");

    /**
     * 场景：集中常量表收录 32 既有模板 + 9 查询增强模板。
     * 预期：{@code TEMPLATE_NAMES} 恰为 41 个且无重复（Set 语义保证去重，数量断言即隐含无重复）。
     */
    @Test
    void should_contain41TemplateNames_when_TEMPLATE_NAMES_given_centralConstantsDefined() {
        // given / when
        int size = PromptTemplates.TEMPLATE_NAMES.size();

        // then：Set.of 遇重复元素会在类初始化直接抛异常，可正常取值即无重复；数量口径 41
        assertEquals(41, size, "TEMPLATE_NAMES 应恰收录 41 个模板名（32 既有 + 9 查询增强）");
    }

    /**
     * 场景：读取抽取协议分隔符常量。
     * 预期：与 LightRAG 协议字面量逐字一致（渲染注入与响应解析双方共用该单一定义）。
     */
    @Test
    void should_defineProtocolLiteralValues_when_delimiterConstants_given_LightRAGProtocol() {
        // given / when / then
        assertEquals("<|#|>", PromptTemplates.TUPLE_DELIMITER, "元组分隔符必须为 <|#|>");
        assertEquals("<|COMPLETE|>", PromptTemplates.COMPLETION_DELIMITER, "完成信号必须为 <|COMPLETE|>");
    }

    /**
     * 场景：反射遍历本类全部公开 String 常量。
     * 预期：除两个分隔符外的每个模板名常量值都被 {@code TEMPLATE_NAMES} 收录，
     * 且收录的名称与常量值逐字一致（新增常量漏登集合、或集合外多余项都会被本用例捕获）。
     *
     * @throws IllegalAccessException 反射读取常量字段失败时抛出（测试环境不应发生）
     */
    @Test
    void should_matchEveryConstantField_when_TEMPLATE_NAMES_given_reflectionSmoke() throws IllegalAccessException {
        // given
        List<String> constantValues = new ArrayList<>();

        // when
        for (Field field : PromptTemplates.class.getDeclaredFields()) {
            boolean publicStaticString = Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && String.class.equals(field.getType());
            if (publicStaticString && !DELIMITER_FIELD_NAMES.contains(field.getName())) {
                constantValues.add((String) field.get(null));
            }
        }

        // then
        assertEquals(41, constantValues.size(), "公开模板名 String 常量应为 41 个");
        for (String value : constantValues) {
            assertTrue(PromptTemplates.TEMPLATE_NAMES.contains(value),
                    "模板名常量 [" + value + "] 未收录进 TEMPLATE_NAMES");
        }
    }
}
