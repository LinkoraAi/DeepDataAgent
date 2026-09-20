package com.linkroa.deepdataagent.runtime.application.service.hitl;

import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.PendingToolCallSpec;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.repository.ChatEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PendingBatchResolver} 事件表解析单测（decompose-command-facade 2.2 自命令服务 HITL 分区外迁）。
 * <p>新形态等待现场完全由事件表承载：工具调用集合（{@code agent.tool_use} / {@code agent.mcp_tool_use}）
 * 减去已配对工具结果集合（{@code agent.tool_result} / {@code agent.mcp_tool_result}）即「未应答批次」，
 * 不存在 {@code session.requires_action} 旁路事件。两类方法的分支语义（未应答判定 / 锚点成员判定 /
 * 解析失败降级 / 非法明细行跳过）原先仅能经 {@code resolveHumanConfirmation} 端到端用例间接触达，
 * 本类将其直断；领取事务、租约与续跑注入等编排红线由 {@code hitl.HumanConfirmationServiceTest}
 * 的 {@code resolveHumanConfirmation} 用例簇留守固化断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class PendingBatchResolverTest {

    private static final String SESSION_ID = "sess_hitl_1";

    @Mock private ChatEventRepository chatEventRepository;

    private PendingBatchResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PendingBatchResolver();
        ReflectionTestUtils.setField(resolver, "chatEventRepository", chatEventRepository);
        ReflectionTestUtils.setField(resolver, "objectMapper", new ObjectMapper());
    }

    /** 内置工具调用行（agent.tool_use，payload 携带 tool_use_id / name / input）。 */
    private ChatEvent toolUse(String eventId, String payloadJson) {
        return ChatEvent.create(SESSION_ID, ChatEventType.AGENT_TOOL_USE, payloadJson, 3L, eventId);
    }

    /** MCP 工具调用行（agent.mcp_tool_use，与内置同形承载暂停现场）。 */
    private ChatEvent mcpToolUse(String eventId, String payloadJson) {
        return ChatEvent.create(SESSION_ID, ChatEventType.AGENT_MCP_TOOL_USE, payloadJson, 4L, eventId);
    }

    /** 内置工具结果行（agent.tool_result，tool_use_id 为配对键）。 */
    private ChatEvent toolResult(String payloadJson) {
        return ChatEvent.create(SESSION_ID, ChatEventType.AGENT_TOOL_RESULT, payloadJson, 5L);
    }

    /** MCP 工具结果行（agent.mcp_tool_result，tool_use_id 为配对键）。 */
    private ChatEvent mcpToolResult(String payloadJson) {
        return ChatEvent.create(SESSION_ID, ChatEventType.AGENT_MCP_TOOL_RESULT, payloadJson, 6L);
    }

    /** 桩：事件表内工具调用集合 + 工具结果集合（未应答批次的唯一判据来源）。 */
    private void wireLedger(List<ChatEvent> toolUses, List<ChatEvent> toolResults) {
        when(chatEventRepository.findByTypes(SESSION_ID, ChatEventType.TOOL_USE_TYPES)).thenReturn(toolUses);
        if (!toolUses.isEmpty()) {
            when(chatEventRepository.findByTypes(SESSION_ID, ChatEventType.TOOL_RESULT_TYPES))
                    .thenReturn(toolResults);
        }
    }

    /** 内置工具调用载荷（tool_use_id / name / input 三键同形）。 */
    private static String payload(String toolUseId, String name, String inputJson) {
        return "{\"tool_use_id\":\"" + toolUseId + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    // ==================== 锚点定位等待批次 ====================

    @Test
    void should_returnEmpty_when_locatePendingBatch_given_noToolUseInLedger() {
        // given（事件表无任何工具调用行：会话未在等待）
        when(chatEventRepository.findByTypes(SESSION_ID, ChatEventType.TOOL_USE_TYPES)).thenReturn(List.of());

        // when & then（空集合 = 无待确认项，由调用方映射 404；工具结果集合无需读取）
        assertTrue(resolver.locatePendingBatch(SESSION_ID, "evt_1").isEmpty());
        verify(chatEventRepository, never()).findByTypes(SESSION_ID, ChatEventType.TOOL_RESULT_TYPES);
    }

    @Test
    void should_returnEmpty_when_locatePendingBatch_given_allToolUsesAnswered() {
        // given（工具调用已全部被配对结果应答：无未应答项）
        wireLedger(
                List.of(toolUse("evt_a", payload("tc-1", "search", "{}"))),
                List.of(toolResult("{\"tool_use_id\":\"tc-1\",\"state\":\"success\"}")));

        // when & then（应答集合覆盖调用集合 → 空）
        assertTrue(resolver.locatePendingBatch(SESSION_ID, "evt_a").isEmpty());
    }

    @Test
    void should_returnWholeBatch_when_locatePendingBatch_given_blankAnchor() {
        // given（两笔未应答工具调用 + 空白锚点：视为无定位约束）
        wireLedger(List.of(
                toolUse("evt_a", payload("tc-1", "search", "{}")),
                toolUse("evt_b", payload("tc-2", "fetch", "{}"))), List.of());

        // when
        List<String> batch = resolver.locatePendingBatch(SESSION_ID, "  ");

        // then（取当前未应答批次全体，保序）
        assertEquals(List.of("evt_a", "evt_b"), batch);
    }

    @Test
    void should_returnWholeBatch_when_locatePendingBatch_given_anchorIsBatchMember() {
        // given（批次内任一 id 均可锚定，裁决整批生效）
        wireLedger(List.of(
                toolUse("evt_a", payload("tc-1", "search", "{}")),
                toolUse("evt_b", payload("tc-2", "fetch", "{}"))), List.of());

        // when（锚第二位成员）
        List<String> batch = resolver.locatePendingBatch(SESSION_ID, "evt_b");

        // then（返回整批而非单项）
        assertEquals(List.of("evt_a", "evt_b"), batch);
    }

    @Test
    void should_returnEmpty_when_locatePendingBatch_given_anchorAnsweredAlready() {
        // given（锚点指向已被结果配对、因而已离开等待批次的行）
        wireLedger(
                List.of(toolUse("evt_a", payload("tc-1", "search", "{}"))),
                List.of(toolResult("{\"tool_use_id\":\"tc-1\"}")));

        // when & then（不消费当前等待：空集合）
        assertTrue(resolver.locatePendingBatch(SESSION_ID, "evt_a").isEmpty());
    }

    @Test
    void should_returnEmpty_when_locatePendingBatch_given_anchorNotInPendingSet() {
        // given（锚点属已被新批次取代的旧等待项）
        wireLedger(List.of(toolUse("evt_a", payload("tc-1", "search", "{}"))), List.of());

        // when & then（不消费当前等待：空集合）
        assertTrue(resolver.locatePendingBatch(SESSION_ID, "evt_stale").isEmpty());
    }

    @Test
    void should_keepUnansweredRow_when_locatePendingBatch_given_unparseableToolUsePayload() {
        // given（工具调用行 payload 非法 JSON：配对键读不出，按「未应答」处置而非整批抛错）
        wireLedger(List.of(toolUse("evt_broken", "not-a-json")), List.of());

        // when
        List<String> batch = resolver.locatePendingBatch(SESSION_ID, null);

        // then（事件表数据损坏降级为仍待确认，确认入口不因单行损坏抛技术异常）
        assertEquals(List.of("evt_broken"), batch);
    }

    @Test
    void should_ignoreResultWithoutPairingKey_when_locatePendingBatch_given_resultMissingToolUseId() {
        // given（结果行缺 tool_use_id：无法与任何调用配对，不构成应答）
        wireLedger(
                List.of(toolUse("evt_a", payload("tc-1", "search", "{}"))),
                List.of(toolResult("{\"name\":\"search\"}")));

        // when & then（调用仍属未应答）
        assertEquals(List.of("evt_a"), resolver.locatePendingBatch(SESSION_ID, "evt_a"));
    }

    @Test
    void should_excludeAnsweredMcpToolUse_when_locatePendingBatch_given_pairedMcpResult() {
        // given（D15：MCP 调用与 MCP 结果以 tool_use_id 配对，两类集合均在判据内）
        wireLedger(
                List.of(mcpToolUse("evt_mcp", payload("tc-mcp", "mcp__weather__get_weather", "{}"))),
                List.of(mcpToolResult("{\"tool_use_id\":\"tc-mcp\"}")));

        // when & then（MCP 调用被配对结果应答 → 空）
        assertTrue(resolver.locatePendingBatch(SESSION_ID, null).isEmpty());
    }

    // ==================== 整批明细重建 ====================

    @Test
    void should_rebuildWholeBatch_when_rebuildPendingBatch_given_toolUseRowsByEventIds() {
        // given（批次键为公开事件 id 且批查命中两行）
        when(chatEventRepository.findToolUsesByEventIds(SESSION_ID, List.of("evt_a", "evt_b")))
                .thenReturn(List.of(
                        toolUse("evt_a", payload("tc-1", "search", "{\"q\":\"x\"}")),
                        toolUse("evt_b", payload("tc-2", "fetch", "{\"url\":\"u\"}"))));

        // when
        List<PendingToolCallSpec> specs = resolver.rebuildPendingBatch(SESSION_ID, List.of("evt_a", "evt_b"));

        // then（SDK id / 工具名 / 入参 JSON 均取自事件表 payload，保序整批重建）
        assertEquals(2, specs.size());
        assertEquals("tc-1", specs.get(0).toolCallId());
        assertEquals("search", specs.get(0).toolName());
        assertTrue(specs.get(0).inputJson().contains("\"q\":\"x\""), "入参须原样携带 SDK 侧键值");
        assertEquals("tc-2", specs.get(1).toolCallId());
    }

    @Test
    void should_rebuildMcpToolRow_when_rebuildPendingBatch_given_mcpToolUseEventRow() {
        // given（D15：MCP 工具调用行与内置同形承载暂停现场，事件表类型为 agent.mcp_tool_use）
        when(chatEventRepository.findToolUsesByEventIds(SESSION_ID, List.of("evt_mcp")))
                .thenReturn(List.of(mcpToolUse("evt_mcp",
                        payload("tc-mcp", "mcp__weather__get_weather", "{\"city\":\"SH\"}"))));

        // when
        List<PendingToolCallSpec> specs = resolver.rebuildPendingBatch(SESSION_ID, List.of("evt_mcp"));

        // then（MCP 实名原样作为工具名回传，续跑侧按同一实名重放）
        assertEquals(1, specs.size());
        assertEquals("tc-mcp", specs.get(0).toolCallId());
        assertEquals("mcp__weather__get_weather", specs.get(0).toolName());
        assertTrue(specs.get(0).inputJson().contains("\"city\":\"SH\""));
    }

    @Test
    void should_rebuildMixedBatch_when_rebuildPendingBatch_given_builtinAndMcpRows() {
        // given（同一批次混含内置与 MCP 工具调用：两类事件同批重建，保序）
        when(chatEventRepository.findToolUsesByEventIds(SESSION_ID, List.of("evt_bi", "evt_mcp")))
                .thenReturn(List.of(
                        toolUse("evt_bi", payload("tc-1", "web_fetch", "{}")),
                        mcpToolUse("evt_mcp", payload("tc-2", "mcp__weather__get_weather", "{}"))));

        // when
        List<PendingToolCallSpec> specs = resolver.rebuildPendingBatch(SESSION_ID, List.of("evt_bi", "evt_mcp"));

        // then
        assertEquals(List.of("tc-1", "tc-2"), specs.stream().map(PendingToolCallSpec::toolCallId).toList());
        assertEquals(List.of("web_fetch", "mcp__weather__get_weather"),
                specs.stream().map(PendingToolCallSpec::toolName).toList());
    }

    @Test
    void should_skipRowsWithoutToolCallId_when_rebuildPendingBatch_given_malformedLedgerRows() {
        // given（两行合法 + 一行缺 tool_use_id（非法）+ 一行 payload 损坏）
        when(chatEventRepository.findToolUsesByEventIds(anyString(), anyList())).thenReturn(List.of(
                toolUse("evt_ok1", payload("tc-1", "search", "{}")),
                toolUse("evt_no_id", "{\"name\":\"search\"}"),
                toolUse("evt_broken", "not-a-json"),
                toolUse("evt_ok2", payload("tc-2", "fetch", "{}"))));

        // when
        List<PendingToolCallSpec> specs = resolver.rebuildPendingBatch(SESSION_ID,
                List.of("evt_ok1", "evt_no_id", "evt_broken", "evt_ok2"));

        // then（非法行跳过、合法行保序重建，不因单行损坏整批失败）
        assertEquals(List.of("tc-1", "tc-2"), specs.stream().map(PendingToolCallSpec::toolCallId).toList());
    }

    @Test
    void should_defaultEmptyInput_when_rebuildPendingBatch_given_rowWithoutInputPayload() {
        // given（明细行未携带 input 字段）
        when(chatEventRepository.findToolUsesByEventIds(anyString(), anyList()))
                .thenReturn(List.of(toolUse("evt_a", "{\"tool_use_id\":\"tc-1\",\"name\":\"search\"}")));

        // when
        List<PendingToolCallSpec> specs = resolver.rebuildPendingBatch(SESSION_ID, List.of("evt_a"));

        // then（入参收敛为空对象串，续跑侧无需再判空）
        assertEquals("{}", specs.get(0).inputJson());
    }

    @Test
    void should_returnEmpty_when_rebuildPendingBatch_given_noToolUseRowsAtAll() {
        // given（批查未命中：事件表缺工具调用行）
        when(chatEventRepository.findToolUsesByEventIds(anyString(), anyList())).thenReturn(List.of());

        // when & then（空批由调用方映射为「明细重建失败」运行错误，本类不抛）
        assertTrue(resolver.rebuildPendingBatch(SESSION_ID, List.of("evt_ghost")).isEmpty());
    }
}