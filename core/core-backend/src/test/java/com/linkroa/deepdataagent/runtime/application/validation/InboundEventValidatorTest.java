package com.linkroa.deepdataagent.runtime.application.validation;

import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InboundEventValidator} 入站校验单测（自原装配器结构校验区逐方法平移，断言不丢）。
 * <p>覆盖：事件类型名解析（400 {@code unknown_event_type}，{@code user.define_outcome} 已移出白名单）、
 * user.message content 形状、user.tool_confirmation 形状与 deny_message 规则、工具结果定位键、
 * system.message 的 content 形状与批规则，以及追加挂载批次非空。</p>
 */
class InboundEventValidatorTest {

    /** 被测校验器（实例持有 FileApi 协作者，但本类用例不触达 image file source 门禁）。 */
    private final InboundEventValidator validator = new InboundEventValidator();

    // ==================== 事件类型名解析 ====================

    @Test
    void should_returnEventType_when_parseKnownType_given_authoritativeTypeName() {
        // given & when（权威事件表成员：精确小写类型名）
        ChatEventType type = InboundEventValidator.parseKnownType("user.message");

        // then
        assertEquals(ChatEventType.USER_MESSAGE, type);
    }

    @Test
    void should_throwUnknownEventType_when_parseKnownType_given_unknownTypeName() {
        // given & when & then（整批 400 unknown_event_type，业务异常语义）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> InboundEventValidator.parseKnownType("RUN_START"));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"));
    }

    @Test
    void should_throwUnknownEventType_when_parseKnownType_given_defineOutcomeType() {
        // given & when & then（user.define_outcome 已移出权威事件表：入站一律 400，不接收、不落库）
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> InboundEventValidator.parseKnownType("user.define_outcome"));
        assertTrue(ex.getMessage().startsWith("unknown_event_type"));
    }

    // ==================== user.message ====================

    @Test
    void should_passValidation_when_validateInboundBatch_given_nonEmptyContentBlocks() {
        // given（多块 content 数组）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_MESSAGE,
                "{\"content\":[{\"type\":\"text\",\"text\":\"第一段\"},{\"type\":\"text\",\"text\":\"第二段\"}]}"));

        // when & then（不抛异常即通过）
        validator.validateInboundBatch(drafts, 1L);
    }

    @Test
    void should_rejectUserMessage_when_validateInboundBatch_given_plainStringContent() {
        // given（纯字符串 content 被拒：MUST 为 content block 数组）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_MESSAGE,
                "{\"content\":\"你好\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectUserMessage_when_validateInboundBatch_given_emptyContentArray() {
        // given（空数组被拒：MUST 非空）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_MESSAGE, "{\"content\":[]}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectUserMessage_when_validateInboundBatch_given_missingContent() {
        // given（旧 text 形状不再受理）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_MESSAGE, "{\"text\":\"你好\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectUserMessage_when_validateInboundBatch_given_nonObjectBlock() {
        // given（数组元素非 content block 对象）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_MESSAGE,
                "{\"content\":[\"纯文本块\"]}"));

        // when & then
        assertValidationRejected(drafts);
    }

    // ==================== user.tool_confirmation ====================

    @Test
    void should_passValidation_when_validateInboundBatch_given_denyWithMessage() {
        // given（result=deny + deny_message 合法组合）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_CONFIRMATION,
                "{\"tool_use_id\":\"tc-1\",\"result\":\"deny\",\"deny_message\":\"不允许执行\"}"));

        // when & then（不抛异常即通过）
        validator.validateInboundBatch(drafts, 1L);
    }

    @Test
    void should_rejectToolConfirmation_when_validateInboundBatch_given_illegalResult() {
        // given（result 非 allow/deny）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_CONFIRMATION,
                "{\"tool_use_id\":\"tc-1\",\"result\":\"maybe\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectToolConfirmation_when_validateInboundBatch_given_denyMessageOnAllow() {
        // given（deny_message 仅 result=deny 时合法）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_CONFIRMATION,
                "{\"tool_use_id\":\"tc-1\",\"result\":\"allow\",\"deny_message\":\"非法组合\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectToolConfirmation_when_validateInboundBatch_given_missingToolUseId() {
        // given（缺定位键）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_CONFIRMATION,
                "{\"result\":\"allow\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectToolConfirmation_when_validateInboundBatch_given_blankDenyMessage() {
        // given（result=deny 但 deny_message 为空白）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_CONFIRMATION,
                "{\"tool_use_id\":\"tc-1\",\"result\":\"deny\",\"deny_message\":\" \"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    // ==================== user.tool_result / user.custom_tool_result ====================

    @Test
    void should_rejectToolResult_when_validateInboundBatch_given_missingToolUseId() {
        // given（user.tool_result 的 tool_use_id 必填）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_RESULT,
                "{\"content\":[{\"type\":\"text\",\"text\":\"结果\"}],\"is_error\":false}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_passValidation_when_validateInboundBatch_given_customToolUseIdAndIsError() {
        // given（custom_tool_use_id + 可选 content / is_error 合法形状）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_CUSTOM_TOOL_RESULT,
                "{\"custom_tool_use_id\":\"ctu-1\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"外化结果\"}],\"is_error\":true}"));

        // when & then（不抛异常即通过）
        validator.validateInboundBatch(drafts, 1L);
    }

    @Test
    void should_rejectToolResult_when_validateInboundBatch_given_nonBooleanIsError() {
        // given（is_error 必须布尔）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_RESULT,
                "{\"tool_use_id\":\"tc-1\",\"is_error\":\"yes\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectToolResult_when_validateInboundBatch_given_nonArrayContent() {
        // given（content 若出现必须为 content block 数组）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_TOOL_RESULT,
                "{\"tool_use_id\":\"tc-1\",\"content\":\"纯文本结果\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    // ==================== system.message（批尾注入 + 仅 text 块） ====================

    @Test
    void should_passValidation_when_validateInboundBatch_given_userMessageThenTextSystemMessage() {
        // given（system.message 位于批尾且紧跟 user.message、content 仅非空 text 块）
        List<InboundEventDraft> drafts = List.of(
                draft(ChatEventType.USER_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"问题\"}]}"),
                draft(ChatEventType.SYSTEM_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"补充指令\"}]}"));

        // when & then（不抛异常即通过）
        validator.validateInboundBatch(drafts, 1L);
    }

    @Test
    void should_rejectSystemMessage_when_validateInboundBatch_given_missingContent() {
        // given（system.message 的 content MUST 为非空 content block 数组）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.SYSTEM_MESSAGE, "{}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectSystemMessage_when_validateInboundBatch_given_nonTextBlock() {
        // given（system.message 的 content 仅接受 text 块）
        List<InboundEventDraft> drafts = List.of(
                draft(ChatEventType.USER_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"问题\"}]}"),
                draft(ChatEventType.SYSTEM_MESSAGE,
                        "{\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"url\","
                                + "\"url\":\"https://example.com/a.png\"}}]}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectSystemMessage_when_validateInboundBatch_given_notAtBatchTail() {
        // given（system.message 必须位于批次最后）
        List<InboundEventDraft> drafts = List.of(
                draft(ChatEventType.USER_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"问题\"}]}"),
                draft(ChatEventType.SYSTEM_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"补充指令\"}]}"),
                draft(ChatEventType.USER_INTERRUPT, "{}"));

        // when & then
        assertValidationRejected(drafts);
    }

    @Test
    void should_rejectSystemMessage_when_validateInboundBatch_given_predecessorNotUserEvent() {
        // given（system.message 必须紧跟 user.message / user.tool_result / user.custom_tool_result）
        List<InboundEventDraft> drafts = List.of(
                draft(ChatEventType.USER_INTERRUPT, "{}"),
                draft(ChatEventType.SYSTEM_MESSAGE, "{\"content\":[{\"type\":\"text\",\"text\":\"补充指令\"}]}"));

        // when & then
        assertValidationRejected(drafts);
    }

    // ==================== 批量语义与无约束类型 ====================

    @Test
    void should_passValidation_when_validateInboundBatch_given_nullOrEmptyDrafts() {
        // given & when & then（空批次直接通过，会话类错误由后续步骤给出）
        validator.validateInboundBatch(null, 1L);
        validator.validateInboundBatch(List.of(), 1L);
    }

    @Test
    void should_passValidation_when_validateInboundBatch_given_inboundTypeWithoutStructureRules() {
        // given（user.interrupt 无附加结构约束）
        List<InboundEventDraft> drafts = List.of(draft(ChatEventType.USER_INTERRUPT, "{}"));

        // when & then
        validator.validateInboundBatch(drafts, 1L);
    }

    @Test
    void should_rejectWholeBatch_when_validateInboundBatch_given_secondEventMalformed() {
        // given（全有或全无：任一条目非法即整批 400，零部分落库）
        List<InboundEventDraft> drafts = List.of(
                draft(ChatEventType.USER_INTERRUPT, "{}"),
                draft(ChatEventType.USER_MESSAGE, "{\"content\":\"你好\"}"));

        // when & then
        assertValidationRejected(drafts);
    }

    // ==================== 追加挂载批次 ====================

    @Test
    void should_passValidation_when_validateAppendResourceBatch_given_nonEmptyResources() {
        // given & when & then（非空批次通过；类型与字段不变量由领域工厂在装配阶段兜底）
        InboundEventValidator.validateAppendResourceBatch(List.of(SessionResource.file("file_1", null)));
    }

    @Test
    void should_rejectBatch_when_validateAppendResourceBatch_given_nullOrEmptyResources() {
        // given & when & then（空批次 400，先于会话归属校验）
        assertThrows(IllegalArgumentException.class, () -> InboundEventValidator.validateAppendResourceBatch(null));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InboundEventValidator.validateAppendResourceBatch(List.of()));
        assertEquals("追加挂载资源不能为空", ex.getMessage());
    }

    /** 断言入站 payload 结构校验失败（400 语义 validation_error）。 */
    private void assertValidationRejected(List<InboundEventDraft> drafts) {
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> validator.validateInboundBatch(drafts, 1L));
        assertTrue(ex.getMessage().startsWith("validation_error"),
                "应抛 validation_error，实际: " + ex.getMessage());
    }

    /** 构造入站事件草案（payload 以 JSON 文本原样承载，模拟接口层形态归一结果）。 */
    private static InboundEventDraft draft(ChatEventType type, String payloadJson) {
        return new InboundEventDraft(type, payloadJson);
    }
}