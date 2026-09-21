package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityNodeGraphMapper;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcEntityNodeGraphRepository} 整库清退与展示来源列收缩原语单测（mock MyBatis Mapper）。
 * <p>覆盖 {@code deleteByKbId}：分片循环（不满一片即止、满片续圈至清空）、空入参零交互、
 * 依赖失败异常上抛；覆盖 {@code shrinkDisplaySourceIds}：非空入参以 ArrayList 化集合委托
 * mapper 并回传收缩数、kbId 为空 / 集合为空零 mapper 交互。注解 SQL 文本语义按项目惯例不在单测校验。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcEntityNodeGraphRepositoryTest {

    /** 整库清退单片上限（与被测实现内 DELETE_BATCH_SIZE 常量口径一致） */
    private static final int DELETE_BATCH = 1000;

    /** 测试用知识库ID */
    private static final Long KB_ID = 7L;

    /** 实体图行 Mapper Mock */
    @Mock
    private EntityNodeGraphMapper mapper;

    /** 被测仓储 */
    @InjectMocks
    private JdbcEntityNodeGraphRepository repository;

    @Test
    void should_returnTotalDeletedAndStopAtFirstShortBatch_when_deleteByKbId_given_singleIncompleteBatch() {
        // given：首片即不满批（600 &lt; 1000），代表该库已清空
        when(mapper.physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH)).thenReturn(600);

        // when
        int deleted = repository.deleteByKbId(KB_ID);

        // then：仅一片一次调用，计数为该片行数
        assertEquals(600, deleted);
        verify(mapper, times(1)).physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH);
    }

    @Test
    void should_keepLoopingUntilShortBatch_when_deleteByKbId_given_multipleFullBatches() {
        // given：两片满批 + 第三片不满批收尾
        when(mapper.physicalDeleteByKbIdBatch(KB_ID, DELETE_BATCH)).thenReturn(DELETE_BATCH, DELETE_BATCH, 250);

        // when
        int deleted = repository.deleteByKbId(KB_ID);

        // then：循环三次、总数为各片之和；批大小固定下传
        assertEquals(2250, deleted);
        verify(mapper, times(3)).physicalDeleteByKbIdBatch(eq(KB_ID), anyInt());
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
     * 场景：按实体名物理删单行图节点（重建写回「存活集合为空转删除」原语，组 4 新增端口）。
     * 预期：正常入参委托单条唯一键等值 DELETE；kbId/实体名缺失防御返回 0 且零 DB 交互。
     */
    @Test
    void should_delegateSingleRowDelete_when_deleteByKbIdAndName_given_validAndBlankInputs() {
        // given
        when(mapper.physicalDeleteByKbIdAndName(KB_ID, "Alice")).thenReturn(1);

        // when & then
        assertEquals(1, repository.deleteByKbIdAndName(KB_ID, "Alice"));
        assertEquals(0, repository.deleteByKbIdAndName(null, "Alice"));
        assertEquals(0, repository.deleteByKbIdAndName(KB_ID, " "));
        verify(mapper, times(1)).physicalDeleteByKbIdAndName(KB_ID, "Alice");
    }
}
