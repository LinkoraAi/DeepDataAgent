package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationEdgeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationEdgeGraphMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcRelationEdgeGraphRepository} 整库清退、普通读与展示来源列收缩原语单测（mock MyBatis Mapper）。
 * <p>覆盖 {@code deleteByKbId}：分片循环（不满一片即止、满片续圈至清空）、空库零行、
 * 空入参零交互、依赖失败异常上抛；覆盖 {@code findByKbIdAndUnorderedPair} 只读不加锁查询：
 * 入参下传 mapper 与行到领域对象映射、无命中返回空列表；覆盖 {@code shrinkDisplaySourceIds}：
 * 非空入参以 ArrayList 化集合委托 mapper 并回传收缩数、kbId 为空 / 集合为空零 mapper 交互。
 * 注解 SQL 文本语义按项目惯例不在单测校验。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcRelationEdgeGraphRepositoryTest {

    /** 整库清退单片上限（与被测实现内 DELETE_BATCH_SIZE 常量口径一致） */
    private static final int DELETE_BATCH = 1000;

    /** 测试用知识库ID */
    private static final Long KB_ID = 7L;

    /** 测试用端点一名称 */
    private static final String NAME_A = "Alice";

    /** 测试用端点二名称 */
    private static final String NAME_B = "Bob";

    /** 测试用关系属性 JSON 文本（与 RelationProperties 序列化实况同形） */
    private static final String PROPERTIES_JSON =
            "{\"weight\":2.0,\"description\":\"同事\",\"keywords\":[\"同事\"],\"sourceIds\":[11],"
                    + "\"filePaths\":[\"a.md\"]}";

    /** 关系图边 Mapper Mock */
    @Mock
    private RelationEdgeGraphMapper mapper;

    /** 被测仓储 */
    @InjectMocks
    private JdbcRelationEdgeGraphRepository repository;

    @Test
    void should_delegateToMapper_when_findByKbIdAndUnorderedPair_given_endpoints() {
        // given：库内已有 (NAME_A, NAME_B) 方向的一行
        when(mapper.selectByKbIdAndUnorderedPair(KB_ID, NAME_A, NAME_B))
                .thenReturn(List.of(edgeRow(NAME_A, NAME_B)));

        // when
        List<RelationEdge> edges = repository.findByKbIdAndUnorderedPair(KB_ID, NAME_A, NAME_B);

        // then：入参原样下传，行经转换器还原为领域对象（端点方向与属性一致）
        verify(mapper, times(1)).selectByKbIdAndUnorderedPair(KB_ID, NAME_A, NAME_B);
        assertEquals(1, edges.size());
        assertEquals(KB_ID, edges.get(0).kbId());
        assertEquals(NAME_A, edges.get(0).sourceName());
        assertEquals(NAME_B, edges.get(0).targetName());
        assertEquals(2.0D, edges.get(0).properties().weight(), 0.001D);
    }

    @Test
    void should_returnEmptyList_when_findByKbIdAndUnorderedPair_given_noRows() {
        // given：库内无该端点对，mapper 返回空列表（非 null）
        when(mapper.selectByKbIdAndUnorderedPair(KB_ID, NAME_A, NAME_B)).thenReturn(List.of());

        // when
        List<RelationEdge> edges = repository.findByKbIdAndUnorderedPair(KB_ID, NAME_A, NAME_B);

        // then：空列表原样返回，不抛异常
        assertTrue(edges.isEmpty());
    }

    @Test
    void should_returnTotalDeletedAndStopAtFirstShortBatch_when_deleteByKbId_given_singleIncompleteBatch() {
        // given：首片即不满批（1 行），一片一次即清空
        when(mapper.physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH)).thenReturn(1);

        // when
        int deleted = repository.deleteByKbId(KB_ID);

        // then
        assertEquals(1, deleted);
        verify(mapper, times(1)).physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH);
    }

    @Test
    void should_keepLoopingUntilShortBatch_when_deleteByKbId_given_multipleFullBatches() {
        // given：两片满批 + 第三片 0 行收尾（极端：行数恰为整批倍数）
        when(mapper.physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH)).thenReturn(DELETE_BATCH, DELETE_BATCH, 0);

        // when
        int deleted = repository.deleteByKbId(KB_ID);

        // then：循环三次、总数为各片之和
        assertEquals(2000, deleted);
        verify(mapper, times(3)).physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH);
    }

    @Test
    void should_returnZeroWithoutAnyStatement_when_deleteByKbId_given_nullKbId() {
        // when
        int deleted = repository.deleteByKbId(null);

        // then
        assertEquals(0, deleted);
        verifyNoInteractions(mapper);
    }

    @Test
    void should_propagateException_when_deleteByKbId_given_mapperFailure() {
        // given：依赖失败（数据源抖动）
        when(mapper.physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        // when & then：原样上抛，由消费方决定退避重试
        assertThrows(DataAccessResourceFailureException.class, () -> repository.deleteByKbId(KB_ID));
    }

    @Test
    void should_returnMapperCountWithArrayListCopy_when_shrinkDisplaySourceIds_given_nonEmptyChunkIds() {
        // given：入参用非 List 集合（LinkedHashSet），实现 MUST 转 ArrayList 下传 mapper（foreach 兼容）
        Set<Long> chunkIds = new LinkedHashSet<>(List.of(101L, 102L));
        when(mapper.shrinkDisplaySourceIds(eq(KB_ID), anyList())).thenReturn(5);

        // when
        int shrunk = repository.shrinkDisplaySourceIds(KB_ID, chunkIds);

        // then：mapper 收缩计数原样回传
        assertEquals(5, shrunk, "收缩数应原样回传 mapper 影响行数");
        // then：mapper 收到 ArrayList 化集合，元素与原入参一致
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(1)).shrinkDisplaySourceIds(eq(KB_ID), captor.capture());
        assertInstanceOf(ArrayList.class, captor.getValue(), "入参集合 MUST 以 ArrayList 副本下传 mapper");
        assertEquals(List.of(101L, 102L), captor.getValue(), "元素与顺序应透传原入参");
    }

    @Test
    void should_returnZeroWithoutAnyStatement_when_shrinkDisplaySourceIds_given_nullKbId() {
        // when：扫描面收窄条件缺失，防御性短路
        int shrunk = repository.shrinkDisplaySourceIds(null, List.of(101L));

        // then
        assertEquals(0, shrunk);
        verifyNoInteractions(mapper);
    }

    @Test
    void should_returnZeroWithoutAnyStatement_when_shrinkDisplaySourceIds_given_emptyChunkIds() {
        // when：空集合入参，不开 SQL
        int shrunk = repository.shrinkDisplaySourceIds(KB_ID, List.of());

        // then
        assertEquals(0, shrunk);
        verifyNoInteractions(mapper);
    }

    /**
     * 构造关系边持久化行（properties 为 RelationProperties 序列化 JSON 文本）。
     *
     * @param sourceName 源实体名称
     * @param targetName 目标实体名称
     * @return 关系边持久化行
     */
    private static RelationEdgeGraphEntity edgeRow(String sourceName, String targetName) {
        RelationEdgeGraphEntity row = new RelationEdgeGraphEntity();
        row.setKbId(KB_ID);
        row.setSourceName(sourceName);
        row.setTargetName(targetName);
        row.setProperties(PROPERTIES_JSON);
        return row;
    }
}
