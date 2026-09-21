package com.linkroa.deepdataagent.rag.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultimodalJsonParser} 单元测试。
 * <p>覆盖五级容错链各级：直接解析、thinking/围栏剥离、基础清理（尾逗号/单引号键）、
 * 结构修复（缺闭括号）、补引号（裸键）、插逗号（跨行漏逗号）、正则字段降级提取，
 * 以及全链失败的纯垃圾文本与 null/空白边界。纯静态工具、无外部依赖、完全离线。</p>
 * <p>测试数据中的 think 标签使用 unicode 转义书写尖括号，防止文档工具剥离标签字面量。</p>
 */
class MultimodalJsonParserTest {

    /** think 开标签（尖括号 unicode 转义） */
    private static final String THINK_OPEN = "\u003Cthinking\u003E";
    /** think 闭标签（尖括号 unicode 转义） */
    private static final String THINK_CLOSE = "\u003C/thinking\u003E";

    /**
     * 场景：干净 JSON（详描 + entity_info 嵌套），应命中一级直接解析。
     * 预期：解析成功，字段经 FIELD_* 常量读取全部正确。
     */
    @Test
    void should_parseAllFields_when_parse_given_cleanJson() {
        // given
        String raw = "{\"detailed_description\": \"干净描述\", \"entity_info\": "
                + "{\"entity_name\": \"架构图\", \"entity_type\": \"image\", \"summary\": \"系统架构\"}}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        JsonNode root = parsed.get();
        assertEquals("干净描述", root.path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        JsonNode entityInfo = root.path(MultimodalJsonParser.FIELD_ENTITY_INFO);
        assertEquals("架构图", entityInfo.path(MultimodalJsonParser.FIELD_ENTITY_NAME).asText());
        assertEquals("image", entityInfo.path(MultimodalJsonParser.FIELD_ENTITY_TYPE).asText());
        assertEquals("系统架构", entityInfo.path(MultimodalJsonParser.FIELD_SUMMARY).asText());
    }

    /**
     * 场景：markdown json 代码围栏包裹的 JSON，且含嵌套对象尾逗号。
     * 预期：围栏剥离 + 尾逗号清理后解析成功。
     */
    @Test
    void should_parse_when_parse_given_fencedJsonWithTrailingComma() {
        // given
        String raw = "```json\n"
                + "{\"detailed_description\": \"围栏内描述\", \"entity_info\": {\"summary\": \"摘要\",},}\n"
                + "```";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("围栏内描述", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        assertEquals("摘要", parsed.get().path(MultimodalJsonParser.FIELD_ENTITY_INFO)
                .path(MultimodalJsonParser.FIELD_SUMMARY).asText());
    }

    /**
     * 场景：成对 think 标签包裹推理内容（含干扰花括号），JSON 在标签之后。
     * 预期：标签对（含内部花括号）整体剥离后解析成功。
     */
    @Test
    void should_parse_when_parse_given_closedThinkingBlock() {
        // given
        String raw = "前置说明。\n" + THINK_OPEN + "\n内部推理 {草稿花括号}\n" + THINK_CLOSE
                + "\n{\"detailed_description\": \"thinking 后描述\", \"entity_info\": {\"summary\": \"s\"}}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("thinking 后描述", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
    }

    /**
     * 场景：模型输出被截断，think 开标签未闭合，其后紧跟 JSON。
     * 预期：从孤儿开标签处截断保留其前文（即 JSON 主体），解析成功。
     */
    @Test
    void should_parse_when_parse_given_orphanThinkingTag() {
        // given
        String raw = "答案如下：\n" + THINK_OPEN + "\n未闭合的推理过程，无花括号。\n\n"
                + "{\"detailed_description\": \"孤儿标签后描述\"}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("孤儿标签后描述", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
    }

    /**
     * 场景：对象嵌套两处尾逗号（无围栏无 thinking）。
     * 预期：基础清理级修复成功。
     */
    @Test
    void should_parse_when_parse_given_trailingCommas() {
        // given
        String raw = "{\"summary\": \"尾逗号\", \"entity_info\": {\"entity_name\": \"n\",},}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("n", parsed.get().path(MultimodalJsonParser.FIELD_ENTITY_INFO)
                .path(MultimodalJsonParser.FIELD_ENTITY_NAME).asText());
    }

    /**
     * 场景：键使用单引号包裹（JSON 非法写法），值仍为双引号。
     * 预期：单引号键转双引号后解析成功。
     */
    @Test
    void should_parse_when_parse_given_singleQuotedKeys() {
        // given
        String raw = "{'detailed_description': \"单引号键\", 'entity_info': {\"summary\": \"s\"}}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("单引号键", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
    }

    /**
     * 场景：裸键漏写引号（结构符后直接书写标识符键）。
     * 预期：补引号级修复成功，全部字段保留。
     */
    @Test
    void should_parse_when_parse_given_bareKeysWithoutQuotes() {
        // given
        String raw = "{detailed_description: \"裸键\", entity_info: {summary: \"s\"}}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("裸键", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        assertEquals("s", parsed.get().path(MultimodalJsonParser.FIELD_ENTITY_INFO)
                .path(MultimodalJsonParser.FIELD_SUMMARY).asText());
    }

    /**
     * 场景：外层大括号漏写（仅内层闭合），字符串本身完整。
     * 预期：结构修复级按括号栈自动补齐外层闭括号后解析成功。
     */
    @Test
    void should_parse_when_parse_given_unclosedOuterBrace() {
        // given
        String raw = "{\"detailed_description\": \"缺闭括号\", \"entity_info\": {\"entity_name\": \"n1\"}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("缺闭括号", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        assertEquals("n1", parsed.get().path(MultimodalJsonParser.FIELD_ENTITY_INFO)
                .path(MultimodalJsonParser.FIELD_ENTITY_NAME).asText());
    }

    /**
     * 场景：两字段之间跨行漏写逗号（值行结束后直接换行开新键）。
     * 预期：插逗号级补救成功，前后字段值均完整保留。
     */
    @Test
    void should_parse_when_parse_given_missingCommaAcrossLines() {
        // given
        String raw = "{\n  \"detailed_description\": \"漏逗号\"\n  \"entity_info\": {\"summary\": \"s\"}\n}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        assertEquals("漏逗号", parsed.get().path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        assertEquals("s", parsed.get().path(MultimodalJsonParser.FIELD_ENTITY_INFO)
                .path(MultimodalJsonParser.FIELD_SUMMARY).asText());
    }

    /**
     * 场景：字符串值内含未转义引号（结构性修复全链无法救回），但已知字段格式完好。
     * 预期：降级为正则字段提取，至少恢复 entity_name / summary（详描按首个引号截断为部分值）。
     */
    @Test
    void should_extractKnownFields_when_parse_given_unescapedInnerQuotes() {
        // given
        String raw = "{\"detailed_description\": \"图中标签\"销售额\"呈上升趋势\", \"entity_info\": "
                + "{\"entity_name\": \"销售图\", \"entity_type\": \"image\", \"summary\": \"销售趋势图\"}}";

        // when
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(raw);

        // then
        assertTrue(parsed.isPresent());
        JsonNode root = parsed.get();
        assertEquals("图中标签", root.path(MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION).asText());
        JsonNode entityInfo = root.path(MultimodalJsonParser.FIELD_ENTITY_INFO);
        assertEquals("销售图", entityInfo.path(MultimodalJsonParser.FIELD_ENTITY_NAME).asText());
        assertEquals("image", entityInfo.path(MultimodalJsonParser.FIELD_ENTITY_TYPE).asText());
        assertEquals("销售趋势图", entityInfo.path(MultimodalJsonParser.FIELD_SUMMARY).asText());
    }

    /**
     * 场景：纯垃圾文本（无任何 JSON 主体特征）。
     * 预期：全链失败返回 empty，不抛异常。
     */
    @Test
    void should_returnEmpty_when_parse_given_pureGarbageWithoutJsonBody() {
        // given / when / then（无花括号正文）
        assertFalse(MultimodalJsonParser.parse("非常抱歉，我无法解析该图片内容。").isPresent());
        // given / when / then（有括号壳但无已知字段，结构修复与降级提取均无法命中）
        assertFalse(MultimodalJsonParser.parse("{ 残缺 ; 内容 }").isPresent());
    }

    /**
     * 场景：入参为 null、空串、纯空白。
     * 预期：直接返回 empty；stripThinkingAndFences 归一为空串。
     */
    @Test
    void should_returnEmpty_when_parse_given_nullOrBlankText() {
        // given / when / then
        assertFalse(MultimodalJsonParser.parse(null).isPresent());
        assertFalse(MultimodalJsonParser.parse("").isPresent());
        assertFalse(MultimodalJsonParser.parse("   \n  ").isPresent());
        assertEquals("", MultimodalJsonParser.stripThinkingAndFences(null));
        assertEquals("", MultimodalJsonParser.stripThinkingAndFences("  \t "));
    }

    /**
     * 场景：合法但非对象/数组根（字符串、数字标量）。
     * 预期：不视为解析成功返回 empty；数组根则应成功。
     */
    @Test
    void should_rejectScalarRootButAcceptArrayRoot_when_parse_given_nonObjectRoot() {
        // given / when / then（标量根拒绝）
        assertFalse(MultimodalJsonParser.parse("\"just a string\"").isPresent());
        assertFalse(MultimodalJsonParser.parse("123").isPresent());
        // given / when / then（数组根接受）
        Optional<JsonNode> array = MultimodalJsonParser.parse("[1, 2, 3]");
        assertTrue(array.isPresent());
        assertTrue(array.get().isArray());
    }

    /**
     * 场景：thinking 块 + 围栏叠加的复合污染文本，直接调用公共剥离方法。
     * 预期：输出恰为最外层 JSON 主体文本。
     */
    @Test
    void should_returnJsonBody_when_stripThinkingAndFences_given_thinkingAndFenceCombined() {
        // given
        String raw = THINK_OPEN + "\n推理 {干扰花括号}\n" + THINK_CLOSE
                + "\n```json\n{\"a\":1}\n```";

        // when
        String stripped = MultimodalJsonParser.stripThinkingAndFences(raw);

        // then
        assertEquals("{\"a\":1}", stripped);
    }
}
