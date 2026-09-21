package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.rag.infrastructure.config.AppInstanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RedisInFlightTaskRegistry} 缓存在飞任务注册表单测。
 * <p>覆盖：三段式键名构造（实例标识 : 业务域固定字面量 : 任务类型段，中间段不随配置变化）、
 * 成员登记与移除、登记写入失败异常上抛（MUST NOT 静默放行）、移除与读取失败仅降级留痕
 * （移除失败不抛出、读取失败返回空集合）、集合成员解析（非数字成员跳过不抛出）、
 * 停机清空本实例三类键、参数非法零缓存交互。</p>
 *
 * <p>缓存模板与其集合操作按规范以 {@code @Mock} 隔离（注册表只承担键与成员形态的编排语义，
 * 缓存可用性由 Mock 显式编排）；实例标识持有者同样以 {@code @Mock} 承载——注册表只消费其
 * {@code getInstanceId()}，而标识的取值来源（仅进程环境变量）与构造期校验由
 * {@code AppInstancePropertiesTest} 覆盖，本类只验证键的实例维度确实取自标识值。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class RedisInFlightTaskRegistryTest {

    /** 实例标识（键的实例维度配置值） */
    private static final String INSTANCE_ID = "node-a";

    /** 文档解析任务注册表键 */
    private static final String DOC_INGESTION_KEY = "node-a:knowledgebase-rag:docIngestionTask";

    /** 文档清退任务注册表键 */
    private static final String DOC_CLEANUP_KEY = "node-a:knowledgebase-rag:docCleanupTask";

    /** 知识库清退任务注册表键 */
    private static final String KB_CLEANUP_KEY = "node-a:knowledgebase-rag:kbCleanupTask";

    /** 测试任务主键 */
    private static final Long TASK_ID = 1001L;

    /** 字符串模板（键与成员均为字符串形态） */
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 字符串模板的集合操作（成员登记 / 移除 / 读取） */
    @Mock
    private SetOperations<String, String> setOperations;

    /** 实例标识持有者（Mock：注册表只消费 {@code getInstanceId()}） */
    @Mock
    private AppInstanceProperties appInstanceProperties;

    /** 被测注册表 */
    private RedisInFlightTaskRegistry registry;

    @BeforeEach
    void setUp() {
        // 标识值以 lenient 打桩：部分用例（参数非法零交互）不会走到键构造，普通打桩会被判为多余桩
        lenient().when(appInstanceProperties.getInstanceId()).thenReturn(INSTANCE_ID);
        registry = new RedisInFlightTaskRegistry(stringRedisTemplate, appInstanceProperties);
    }

    @Test
    void should_buildThreeSegmentKey_when_keyOf_given_instanceIdAndTaskType() {
        // when：三类任务各自的键名（实例标识 : 业务域 : 任务类型段）
        String docIngestionKey = registry.keyOf(InFlightTaskType.DOC_INGESTION);
        String docCleanupKey = registry.keyOf(InFlightTaskType.DOC_CLEANUP);
        String kbCleanupKey = registry.keyOf(InFlightTaskType.KB_CLEANUP);

        // then：中间段为固定字面量，不随配置变化
        assertEquals(DOC_INGESTION_KEY, docIngestionKey);
        assertEquals(DOC_CLEANUP_KEY, docCleanupKey);
        assertEquals(KB_CLEANUP_KEY, kbCleanupKey);
    }

    @Test
    void should_addMember_when_register_given_validTask() {
        // given
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // when
        registry.register(InFlightTaskType.DOC_INGESTION, TASK_ID);

        // then：成员以字符串形态写入本实例对应任务类型的键
        verify(setOperations).add(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
    }

    @Test
    void should_propagateException_when_register_given_redisUnavailable() {
        // given：缓存写入失败
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        RuntimeException failure = new RuntimeException("redis down");
        doThrow(failure).when(setOperations).add(DOC_INGESTION_KEY, String.valueOf(TASK_ID));

        // when
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> registry.register(InFlightTaskType.DOC_INGESTION, TASK_ID));

        // then：异常上抛，由调用方走各自链的既有失败兜底，MUST NOT 静默放行
        assertSame(failure, thrown);
        verify(setOperations).add(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
    }

    @Test
    void should_removeMember_when_unregister_given_validTask() {
        // given
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // when
        registry.unregister(InFlightTaskType.DOC_INGESTION, TASK_ID);

        // then
        verify(setOperations).remove(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
    }

    @Test
    void should_swallowException_when_unregister_given_redisUnavailable() {
        // given：缓存移除失败
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        doThrow(new RuntimeException("redis down")).when(setOperations)
                .remove(DOC_INGESTION_KEY, String.valueOf(TASK_ID));

        // when // then：仅 ERROR 留痕并降级，残留成员由下次启动一致性校验清理
        assertDoesNotThrow(() -> registry.unregister(InFlightTaskType.DOC_INGESTION, TASK_ID));
        verify(setOperations).remove(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
    }

    @Test
    void should_returnTaskIds_when_findInFlightTaskIds_given_members() {
        // given：本实例键内有两个未完成任务
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(DOC_INGESTION_KEY)).thenReturn(Set.of("1001", "1002"));

        // when
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.DOC_INGESTION);

        // then：字符串成员解析为主键集合
        assertEquals(Set.of(1001L, 1002L), taskIds);
    }

    @Test
    void should_skipIllegalMember_when_findInFlightTaskIds_given_nonNumericMember() {
        // given：集合内含非数字成员（理论不可达，防御性跳过）
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(DOC_CLEANUP_KEY)).thenReturn(Set.of("1001", "abc"));

        // when
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.DOC_CLEANUP);

        // then：非法成员被跳过，不影响其余合法成员解析且不抛出
        assertEquals(Set.of(1001L), taskIds);
    }

    @Test
    void should_returnEmptySet_when_findInFlightTaskIds_given_noMembers() {
        // given：该类型键为空（集合清空后键由缓存自动消失）
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(KB_CLEANUP_KEY)).thenReturn(Set.of());

        // when
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.KB_CLEANUP);

        // then
        assertEquals(Set.of(), taskIds);
    }

    @Test
    void should_returnEmptySet_when_findInFlightTaskIds_given_redisUnavailable() {
        // given：缓存读取失败
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(KB_CLEANUP_KEY)).thenThrow(new RuntimeException("redis down"));

        // when
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.KB_CLEANUP);

        // then：按无残留降级（已登记的已知风险），MUST NOT 阻断启动恢复
        assertEquals(Set.of(), taskIds);
    }

    @Test
    void should_readOnlyOwnInstanceKey_when_findInFlightTaskIds_given_instanceScopedRegistry() {
        // given：本实例（node-a）的解析键内有 1 个未完成任务
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(DOC_INGESTION_KEY)).thenReturn(Set.of("1001"));

        // when：执行启动恢复 / 停机收敛的读取动作
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.DOC_INGESTION);

        // then：只以本实例标识起头的键读取一次
        assertEquals(Set.of(1001L), taskIds);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(setOperations, times(1)).members(keyCaptor.capture());
        assertEquals(DOC_INGESTION_KEY, keyCaptor.getValue());
        // then：任何其他实例前缀的键从未被读取（MUST NOT 读取或改动其他实例的键）
        verify(setOperations, never()).members(startsWith("node-b:"));
    }

    @Test
    void should_returnAllIdsUnderSingleKey_when_findInFlightTaskIds_given_threeUnfinishedDocuments() {
        // given：某实例同时有 3 篇未完成文档——表现为同一个键下的 3 个集合成员
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(DOC_INGESTION_KEY)).thenReturn(Set.of("1001", "1002", "1003"));

        // when
        Set<Long> taskIds = registry.findInFlightTaskIds(InFlightTaskType.DOC_INGESTION);

        // then：3 个任务 ID 全部解析出来，且读取只发生在一个键上（键数与任务数解耦）
        assertEquals(Set.of(1001L, 1002L, 1003L), taskIds);
        verify(setOperations, times(1)).members(anyString());
    }

    @Test
    void should_addSameKeyAndSameMemberTwice_when_register_given_duplicateTask() {
        // given
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // when：同一任务 ID 被登记两次
        registry.register(InFlightTaskType.DOC_INGESTION, TASK_ID);
        registry.register(InFlightTaskType.DOC_INGESTION, TASK_ID);

        // then：两次 SADD 使用同一键与同一成员字符串——重复堆积由 Redis 集合语义（SADD 幂等）挡下，
        // 集合中只保留一个成员；应用侧不做额外存在性判断（不得伪造去重断言）
        verify(setOperations, times(2)).add(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
    }

    @Test
    void should_removeMemberWithoutDeletingKey_when_unregister_given_lastMember() {
        // given：键内仅剩最后一个任务 ID
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // when
        registry.unregister(InFlightTaskType.DOC_INGESTION, TASK_ID);

        // then：仅移除成员；键的生命周期由 Redis 托管——集合为空时键自动消失（MUST NOT 遗留空集合），
        // 应用侧 MUST NOT 显式删键
        verify(setOperations).remove(DOC_INGESTION_KEY, String.valueOf(TASK_ID));
        verify(stringRedisTemplate, never()).delete(anyCollection());
    }

    @Test
    void should_deleteAllInstanceKeys_when_clearInstanceRegistry_given_inFlightTasks() {
        // given：键总数恒为「实例数 × 3」，与在飞任务数无关
        ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);

        // when
        registry.clearInstanceRegistry();

        // then：恰好三类任务的键，顺序与任务类型枚举声明一致
        verify(stringRedisTemplate).delete(keysCaptor.capture());
        assertEquals(List.of(DOC_INGESTION_KEY, DOC_CLEANUP_KEY, KB_CLEANUP_KEY),
                List.copyOf(keysCaptor.getValue()));
    }

    @Test
    void should_throwIllegalArgument_when_register_given_nullTaskTypeOrTaskId() {
        // when // then：空值即拒绝且零缓存交互（避免写入脏键 / 脏成员）
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, TASK_ID));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(InFlightTaskType.DOC_INGESTION, null));
        verifyNoInteractions(stringRedisTemplate, setOperations);
    }

    @Test
    void should_throwIllegalArgument_when_unregister_given_nullTaskTypeOrTaskId() {
        // when // then：空值即拒绝且零缓存交互
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(null, TASK_ID));
        assertThrows(IllegalArgumentException.class,
                () -> registry.unregister(InFlightTaskType.DOC_INGESTION, null));
        verifyNoInteractions(stringRedisTemplate, setOperations);
    }
}