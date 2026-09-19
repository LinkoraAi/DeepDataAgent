package com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSessionMapper} SQL 形状断言单测。
 * <p>两类断言：</p>
 * <ol>
 *   <li>引用统计 SQL（环境 / 记忆库 / 保管库删除引用守卫依赖的 JSONB 包含查询）；</li>
 *   <li><b>迁移表 → 窄列 CAS 的形状对照</b>：{@code transition(sessionId, Transition)}
 *       对全部命名迁移常量分别生成「按声明选择性写 {@code status} / {@code turn_phase} +
 *       {@code updated_at} 恒刷新」的窄列 UPDATE，前置条件完全由迁移声明的
 *       {@code statusFrom} / {@code phaseFrom} 集合决定（空集 ⇒ WHERE 内不出现该列条件）。
 *       状态 / 相位字面量在此以期望值形式声明，Mapper 内不再出现硬编码前置状态。</li>
 * </ol>
 * <p>归档已移出状态机：{@code archive(sessionId)} 是唯一归档落点，只写
 * {@code archived_at} + {@code updated_at}，并带 {@code archived_at IS NULL} 幂等守卫。</p>
 * <p>mock Mapper + {@code doCallRealMethod} 真实装配 default 方法，捕获条件包装器比对。</p>
 */
class AgentSessionMapperTest {

    static {
        // 初始化实体 TableInfo：lambda 方法引用 ⇄ 列名解析所需的元数据缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentSessionEntity.class);
    }

    private static final String SESSION_ID = "sess_transition_shape";

    /**
     * 形状对照表：命名迁移常量 →（期望 status 前置取值集、期望 turn_phase 前置取值集、
     * 期望目标 status 取值、期望目标 turn_phase 取值）。null 目标值表示该列不由本迁移改写。
     *
     * @return 参数组（迁移常量, status 前置, phase 前置, 目标 status, 目标 phase）
     */
    static Stream<Arguments> transitionSqlShape() {
        return Stream.of(
                Arguments.of(Transition.BEGIN_TURN, List.of("idle"), List.of(),
                        "running", "running"),
                Arguments.of(Transition.PHASE_AWAIT, List.of(), List.of("running"),
                        null, "awaiting_confirmation"),
                Arguments.of(Transition.PHASE_RESUME, List.of(), List.of("awaiting_confirmation"),
                        null, "running"),
                Arguments.of(Transition.PHASE_CANCEL, List.of(), List.of("running", "awaiting_confirmation"),
                        null, "cancelling"),
                Arguments.of(Transition.FINISH_TURN, List.of("running"), List.of(),
                        "idle", "idle"),
                Arguments.of(Transition.TERMINATE, List.of("running"), List.of("running"),
                        "terminated", "idle"),
                Arguments.of(Transition.RESCHEDULE, List.of("running"), List.of(),
                        "rescheduling", null),
                Arguments.of(Transition.RESUME_FROM_RESCHEDULE, List.of("rescheduling"), List.of(),
                        "running", null),
                Arguments.of(Transition.ABANDON_ORPHAN_EXECUTION, List.of("running", "rescheduling"),
                        List.of("running", "cancelling"), "idle", "idle")
        );
    }

    @ParameterizedTest
    @MethodSource("transitionSqlShape")
    void should_emitGuardedNarrowUpdate_when_transition_given_namedConstant(
            Transition transition, List<String> expectedStatusGuards, List<String> expectedPhaseGuards,
            String expectedStatus, String expectedPhase) {
        // given：真实装配 default 方法，捕获窄列 UPDATE 包装器
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = captureTransitionUpdate(transition);

        // when
        String sqlSet = wrapper.getSqlSet();
        String where = wrapper.getSqlSegment();
        Map<String, String> values = nonTemporalValues(wrapper);

        // then：按声明选择性写列 + updated_at 恒刷新（窄列 update(null, wrapper)，不落实体全字段）
        assertTrue(sqlSet.contains("updated_at="), sqlSet);
        assertEquals(expectedStatus != null, sqlSet.contains("status="), sqlSet);
        assertEquals(expectedPhase != null, sqlSet.contains("turn_phase="), sqlSet);
        assertFalse(sqlSet.contains("archived_at="), "迁移 MUST NOT 触碰归档时间列: " + sqlSet);
        // 目标态取值按迁移声明写入
        if (expectedStatus != null) {
            assertTrue(values.containsValue(expectedStatus), String.valueOf(values));
        }
        if (expectedPhase != null) {
            assertTrue(values.containsValue(expectedPhase), String.valueOf(values));
        }
        // 前置条件形状：空集 ⇒ WHERE 不出现该列条件；非空 ⇒ 恰一处守卫并逐项取值
        assertEquals(expectedStatusGuards.isEmpty() ? 0 : 1, countColumnGuard(where, "status"),
                "status 守卫个数与迁移声明不符: " + where);
        assertEquals(expectedPhaseGuards.isEmpty() ? 0 : 1, countColumnGuard(where, "turn_phase"),
                "turn_phase 守卫个数与迁移声明不符: " + where);
        expectedStatusGuards.forEach(guard -> assertTrue(values.containsValue(guard),
                transition + " 缺少前置状态 " + guard + ": " + values));
        expectedPhaseGuards.forEach(guard -> assertTrue(values.containsValue(guard),
                transition + " 缺少前置相位 " + guard + ": " + values));
        // 窄列参数总量 = 写入的目标列 + 各前置取值 + session_id（时间列已剔除）
        int writtenColumns = (expectedStatus != null ? 1 : 0) + (expectedPhase != null ? 1 : 0);
        assertEquals(writtenColumns + expectedStatusGuards.size() + expectedPhaseGuards.size() + 1,
                values.size(), String.valueOf(values));
        // WHERE 恒带 session_id 等值
        assertTrue(where.contains("session_id ="), where);
        assertTrue(values.containsValue(SESSION_ID), String.valueOf(values));
    }

    @Test
    void should_delegateToNullEntityNarrowUpdate_when_transition_given_namedConstant() {
        // given：窄列更新——entity 传 null（不触发实体全字段 update，审计列由 set 显式维护）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        doCallRealMethod().when(mapper).transition(anyString(), any(Transition.class));

        // when
        int rows = mapper.transition(SESSION_ID, Transition.FINISH_TURN);

        // then
        assertEquals(1, rows);
        verify(mapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void should_throwIllegalArgument_when_transition_given_nullDeclaration() {
        // given：声明参数须匹配 null（Mockito 的 any(Class) 不匹配 null，此处用无类型 any()）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        doCallRealMethod().when(mapper).transition(anyString(), any());

        // when / then
        assertThrows(IllegalArgumentException.class, () -> mapper.transition(SESSION_ID, null));
    }

    @Test
    void should_writeArchivedAtOnly_when_archive_given_sessionId() {
        // given（归档唯一落点：真实装配 archive default 方法）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        doCallRealMethod().when(mapper).archive(anyString());

        // when
        int rows = mapper.archive(SESSION_ID);

        // then：只写 archived_at + updated_at，status / turn_phase 保持原值，守卫 archived_at IS NULL
        assertEquals(1, rows);
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = captured(mapper);
        String sqlSet = wrapper.getSqlSet();
        String where = wrapper.getSqlSegment();
        assertTrue(sqlSet.contains("archived_at"), sqlSet);
        assertTrue(sqlSet.contains("updated_at"), sqlSet);
        assertFalse(sqlSet.contains("status="), "归档不得改写 status: " + sqlSet);
        assertFalse(sqlSet.contains("turn_phase="), "归档不得改写 turn_phase: " + sqlSet);
        assertTrue(where.contains("archived_at IS NULL"), where);
        assertTrue(where.contains("session_id ="), where);
    }

    /** 捕获 {@code transition} 装配的窄列更新包装器。 */
    private static LambdaUpdateWrapper<AgentSessionEntity> captureTransitionUpdate(Transition transition) {
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        doCallRealMethod().when(mapper).transition(anyString(), any(Transition.class));
        mapper.transition(SESSION_ID, transition);
        return captured(mapper);
    }

    @SuppressWarnings("unchecked")
    private static LambdaUpdateWrapper<AgentSessionEntity> captured(AgentSessionMapper mapper) {
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Wrapper<AgentSessionEntity> wrapper = captor.getValue();
        assertTrue(wrapper instanceof LambdaUpdateWrapper, "迁移必须经 LambdaUpdateWrapper 装配");
        return (LambdaUpdateWrapper<AgentSessionEntity>) wrapper;
    }

    /** 参数值集（剔除时间列——{@code now()} 在两次装配间必然不同）。 */
    private static Map<String, String> nonTemporalValues(LambdaUpdateWrapper<AgentSessionEntity> wrapper) {
        return wrapper.getParamNameValuePairs().entrySet().stream()
                .filter(entry -> !(entry.getValue() instanceof OffsetDateTime))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }

    /** WHERE 片段内指定列的守卫个数（SET 片段不参与统计）。 */
    private static long countColumnGuard(String where, String column) {
        return List.of(where.split(column, -1)).size() - 1L;
    }

    @Test
    void should_queryVaultIdsContainment_when_countByVaultId_given_vaultId() {
        // given
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doCallRealMethod().when(mapper).countByVaultId(any());

        // when
        mapper.countByVaultId("vault_x");

        // then（vault_ids JSONB 数组包含判定，参数为 ["vault_x"] 序列化文本）
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        LambdaQueryWrapper<AgentSessionEntity> wrapper =
                (LambdaQueryWrapper<AgentSessionEntity>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("vault_ids @> cast("), wrapper.getSqlSegment());
        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains("[\"vault_x\"]"), String.valueOf(params));
    }

    @Test
    void should_queryMemoryStoreIdsContainment_when_countByMemoryStoreId_given_storeId() {
        // given
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doCallRealMethod().when(mapper).countByMemoryStoreId(any());

        // when
        mapper.countByMemoryStoreId("ms_x");

        // then（memory_store_ids JSONB 数组包含判定）
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        LambdaQueryWrapper<AgentSessionEntity> wrapper =
                (LambdaQueryWrapper<AgentSessionEntity>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("memory_store_ids @> cast("), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().values().contains("[\"ms_x\"]"),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void should_queryEnvironmentIdEquality_when_countByEnvironmentId_given_environmentId() {
        // given
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doCallRealMethod().when(mapper).countByEnvironmentId(any());

        // when
        mapper.countByEnvironmentId("env_x");

        // then（environment_id 等值 + 参数值）
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        LambdaQueryWrapper<AgentSessionEntity> wrapper =
                (LambdaQueryWrapper<AgentSessionEntity>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("environment_id ="), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("env_x"),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void should_selectOnlyStatusColumnWithSessionGuard_when_selectStatusValue_given_sessionId() {
        // given（终态决策表第 7 行的窄读：只取 status 单列，不做任何写操作）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        AgentSessionEntity row = new AgentSessionEntity();
        row.setStatus("running");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        doCallRealMethod().when(mapper).selectStatusValue(anyString());

        // when
        String status = mapper.selectStatusValue(SESSION_ID);

        // then：取回存量状态；SQL 仅投影 status 列、按 session_id 等值、限量一行
        assertEquals("running", status);
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<AgentSessionEntity> wrapper =
                (LambdaQueryWrapper<AgentSessionEntity>) captor.getValue();
        assertEquals("status", wrapper.getSqlSelect(), "窄读不得整行拉取");
        assertTrue(wrapper.getSqlSegment().contains("session_id ="), wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("LIMIT 1"), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(SESSION_ID),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void should_returnNull_when_selectStatusValue_given_noRow() {
        // given（会话行缺失 / 已逻辑删除：读不到 ⇒ 决策表跳过显式终态前置判定）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doCallRealMethod().when(mapper).selectStatusValue(anyString());

        // when / then
        assertNull(mapper.selectStatusValue("s-ghost"));
    }

    @Test
    void should_queryCancellingPhase_when_isCancelling_given_sessionId() {
        // given（在途取消的持久痕迹：按内部相位 cancelling 等值判定）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        doCallRealMethod().when(mapper).isCancelling(anyString());

        // when
        boolean cancelling = mapper.isCancelling(SESSION_ID);

        // then：命中一行 ⇒ true，且 SQL 按 session_id + turn_phase=cancelling 等值
        assertTrue(cancelling);
        ArgumentCaptor<Wrapper<AgentSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        LambdaQueryWrapper<AgentSessionEntity> wrapper =
                (LambdaQueryWrapper<AgentSessionEntity>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("turn_phase ="), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(SESSION_ID),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("cancelling"),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void should_returnFalse_when_isCancelling_given_noRow() {
        // given（无 cancelling 行 ⇒ 取消不在途）
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doCallRealMethod().when(mapper).isCancelling(anyString());

        // when / then
        assertFalse(mapper.isCancelling(SESSION_ID));
    }
}