package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.LlmCacheEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.LlmCacheMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcLlmCacheRepository} 仓储实现单测（mock MyBatis Mapper）。
 * <p>覆盖 {@code deleteByKbId}：彻底物理删除改造后委托标准
 * {@code mapper.delete(LambdaQueryWrapper)}（kb_id 等值条件、无逻辑删谓词）、
 * 跨 cache_type 分区整清、空结果（受影响 0 行）幂等无异常、kbId 入参透传进 wrapper；
 * 覆盖 {@code saveIfAbsent}：插入前显式填充审计字段（无操作人上下文兜底 system）
 * 且主键置空；覆盖 {@code findByKbIdAndCacheKeys}（rebuild-kg-on-document-delete / tasks 3.2）：
 * 非法入参不下发 SQL、键去重后单批下发 IN 查询、结果行还原为领域缓存条目。
 * 注解 SQL 文本语义按项目惯例不在单测校验。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcLlmCacheRepositoryTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    static {
        // 纯 Mockito 环境无 Spring 启动，手工装配 MyBatis-Plus lambda 列缓存，
        // 使仓储层 LambdaQueryWrapper 的 eq(LlmCacheEntity::getKbId, ...) 可解析列名
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, LlmCacheEntity.class);
        LambdaUtils.installCache(tableInfo);
    }

    /** LLM 缓存 Mapper Mock */
    @Mock
    private LlmCacheMapper mapper;

    /** 被测仓储 */
    @InjectMocks
    private JdbcLlmCacheRepository repository;

    /**
     * 场景：按知识库整清缓存（物理删）。
     * 预期：委托一次标准 delete，wrapper 仅含 kb_id 等值条件且入参原样透传，不产生其它交互。
     */
    @Test
    @SuppressWarnings("unchecked")
    void should_delegatePhysicalDeleteWithKbIdWrapper_when_deleteByKbId_given_kbId() {
        // given
        when(mapper.delete(any())).thenReturn(4);

        // when
        repository.deleteByKbId(KB_ID);

        // then：wrapper 条件列为 kb_id、参数值为原样透传的 kbId
        ArgumentCaptor<Wrapper<LlmCacheEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(1)).delete(captor.capture());
        LambdaQueryWrapper<LlmCacheEntity> wrapper = (LambdaQueryWrapper<LlmCacheEntity>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("kb_id"), "删除条件必须落在 kb_id 列");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(KB_ID), "kbId 必须原样透传给 Wrapper");
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：目标库无任何缓存条目（delete 受影响 0 行）。
     * 预期：deleteByKbId 静默成功、无异常抛出（幂等）。
     */
    @Test
    void should_completeWithoutError_when_deleteByKbId_given_zeroAffectedRows() {
        // given：空结果
        when(mapper.delete(any())).thenReturn(0);

        // when & then：0 行受影响不报错
        assertDoesNotThrow(() -> repository.deleteByKbId(KB_ID));
        verify(mapper, times(1)).delete(any());
    }

    /**
     * 场景：对不同知识库分别整清缓存。
     * 预期：两次调用分别以各自的 kbId 构造 wrapper 委托 delete，互不串库。
     */
    @Test
    @SuppressWarnings("unchecked")
    void should_deleteEachKbSeparately_when_deleteByKbId_given_multipleInvocations() {
        // given
        Long otherKbId = 2002L;
        when(mapper.delete(any())).thenReturn(1);

        // when
        repository.deleteByKbId(KB_ID);
        repository.deleteByKbId(otherKbId);

        // then：两次下发的 wrapper 参数各含自己的 kbId、互不串库
        // 注意：MP 的 eq 参数为惰性回填，须先触发 getSqlSegment() 渲染再断言参数表
        ArgumentCaptor<Wrapper<LlmCacheEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(2)).delete(captor.capture());
        LambdaQueryWrapper<LlmCacheEntity> first = (LambdaQueryWrapper<LlmCacheEntity>) captor.getAllValues().get(0);
        LambdaQueryWrapper<LlmCacheEntity> second = (LambdaQueryWrapper<LlmCacheEntity>) captor.getAllValues().get(1);
        assertTrue(first.getSqlSegment().contains("kb_id"), "首批删除条件必须落在 kb_id 列");
        assertTrue(second.getSqlSegment().contains("kb_id"), "次批删除条件必须落在 kb_id 列");
        assertTrue(first.getParamNameValuePairs().containsValue(KB_ID));
        assertTrue(second.getParamNameValuePairs().containsValue(otherKbId));
        assertTrue(!first.getParamNameValuePairs().containsValue(otherKbId), "两次调用不得互串 kbId");
    }

    /**
     * 场景：幂等回写缓存条目（首写视为系统操作，无操作人上下文）。
     * 预期：插入前主键置空、audit 四字段显式填充且操作人兜底 system，委托 insertIfAbsent。
     */
    @Test
    void should_fillInsertAuditWithSystemOperator_when_saveIfAbsent_given_validEntry() {
        // given
        LlmCacheEntry entry = LlmCacheEntry.create(KB_ID, "0123456789abcdef0123456789abcdef",
                CacheType.ANSWER, "gpt-test", "prompt-text", "response-text", 12);

        // when
        repository.saveIfAbsent(entry);

        // then
        ArgumentCaptor<LlmCacheEntity> captor = ArgumentCaptor.forClass(LlmCacheEntity.class);
        verify(mapper).insertIfAbsent(captor.capture());
        LlmCacheEntity entity = captor.getValue();
        assertNull(entity.getId(), "插入前主键必须置空由数据库自增");
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getCreatedBy());
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：按键批量读取的非法入参——kbId/缓存分类为 null、键集合为空、键集合仅含空白串。
     * 预期：全部直接返回空列表且不下发任何 SQL（mapper 零交互，防 IN 空列表非法语句与空条件全表扫）。
     */
    @Test
    void should_returnEmptyWithoutMapperQuery_when_findByKbIdAndCacheKeys_given_blankArguments() {
        // given
        String validKey = "0123456789abcdef0123456789abcdef";

        // when & then：四种非法形态均空返回
        assertTrue(repository.findByKbIdAndCacheKeys(null, CacheType.EXTRACT, List.of(validKey)).isEmpty());
        assertTrue(repository.findByKbIdAndCacheKeys(KB_ID, null, List.of(validKey)).isEmpty());
        assertTrue(repository.findByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of()).isEmpty());
        assertTrue(repository.findByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(" ", "\t")).isEmpty());
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：键集合含重复键、空白键与有效键（重放路径一次批量读取防 N+1 的仓储侧口径）。
     * 预期：去重保序后单批下发 IN 查询（分类以枚举名透传），结果行还原为领域缓存条目，
     * 缓存表不存在的键不出现于结果。
     */
    @Test
    @SuppressWarnings("unchecked")
    void should_deduplicateKeysAndConvertEntries_when_findByKbIdAndCacheKeys_given_duplicateKeys() {
        // given：keyA 重复出现 + 一空白键，缓存表仅命中 keyA 一行
        String keyA = "0123456789abcdef0123456789abcdef";
        String keyB = "89abcdef0123456789abcdef01234567";
        when(mapper.selectByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT.name()), any()))
                .thenReturn(List.of(cacheEntityRow(keyA)));

        // when
        List<LlmCacheEntry> entries = repository.findByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT,
                List.of(keyA, keyB, keyA, " "));

        // then：单批下发且键去重保序；结果行还原为领域条目
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(1))
                .selectByKbIdAndCacheKeys(eq(KB_ID), eq(CacheType.EXTRACT.name()), captor.capture());
        assertEquals(List.of(keyA, keyB), captor.getValue());
        assertEquals(1, entries.size());
        assertEquals(keyA, entries.get(0).cacheKey());
        assertEquals(CacheType.EXTRACT, entries.get(0).cacheType());
        assertEquals(KB_ID, entries.get(0).kbId());
        assertEquals("response-text", entries.get(0).response());
    }

    /**
     * 场景（组 6 缓存回收仓储面）：批量删除的非法入参——kbId/缓存分类为 null、键集合为空、
     * 键集合仅含空白串。
     * 预期：全部直接返回 0 且不下发任何 SQL（mapper 零交互，防 IN 空列表非法语句与空条件全删）。
     */
    @Test
    void should_returnZeroWithoutMapperDelete_when_deleteByKbIdAndCacheKeys_given_blankArguments() {
        // given
        String validKey = "0123456789abcdef0123456789abcdef";

        // when & then：四种非法形态均零删除、零 SQL
        assertEquals(0, repository.deleteByKbIdAndCacheKeys(null, CacheType.EXTRACT, List.of(validKey)));
        assertEquals(0, repository.deleteByKbIdAndCacheKeys(KB_ID, null, List.of(validKey)));
        assertEquals(0, repository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of()));
        assertEquals(0, repository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT, List.of(" ", "\t")));
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：键集合含重复与空白键的正常回收删除。
     * 预期：去重保序后单批下发 delete（kb_id 与 cache_type 条件携带、键以 IN 传入），
     * 返回受影响行数合计。
     */
    @Test
    void should_deduplicateKeysAndDelegateDelete_when_deleteByKbIdAndCacheKeys_given_duplicateKeys() {
        // given：keyA 重复出现 + 一空白键，缓存表命中 1 行
        String keyA = "0123456789abcdef0123456789abcdef";
        String keyB = "89abcdef0123456789abcdef01234567";
        when(mapper.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT.name(), List.of(keyA, keyB)))
                .thenReturn(1);

        // when
        int deleted = repository.deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT,
                List.of(keyA, keyB, keyA, " "));

        // then：单批下发且键去重保序，计数原样回传
        assertEquals(1, deleted);
        verify(mapper, org.mockito.Mockito.times(1))
                .deleteByKbIdAndCacheKeys(KB_ID, CacheType.EXTRACT.name(), List.of(keyA, keyB));
    }

    /**
     * 构造持久化缓存行（断言无关字段固定值）。
     *
     * @param cacheKey 缓存键（32 位 hex）
     * @return 缓存实体行
     */
    private static LlmCacheEntity cacheEntityRow(String cacheKey) {
        OffsetDateTime time = OffsetDateTime.parse("2026-01-01T08:00:00+08:00");
        LlmCacheEntity entity = new LlmCacheEntity();
        entity.setId(9L);
        entity.setKbId(KB_ID);
        entity.setCacheType(CacheType.EXTRACT.name());
        entity.setCacheKey(cacheKey);
        entity.setModel("gpt-test");
        entity.setPrompt("prompt-text");
        entity.setResponse("response-text");
        entity.setTotalTokens(10);
        entity.setCreatedAt(time);
        entity.setUpdatedAt(time);
        return entity;
    }
}
