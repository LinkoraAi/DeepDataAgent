package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.KnowledgeBaseEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库 Mapper。
 * <p>彻底物理删体系：实体无 is_deleted 列、
 * 无 {@code @TableLogic}，内置 delete / deleteById 即物理 DELETE，行消失即「已删除」。</p>
 * <p><b>Lambda 列写法约束</b>：条件构造器的列参数必须使用方法引用（如 {@code KnowledgeBaseEntity::getId}），
 * 禁止箭头 lambda——MyBatis-Plus 无法从合成方法名解析列名，详见 {@link ChunkMapper} 同款说明。</p>
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    /**
     * 列表可见性三态全集：ACTIVE / DELETING / DELETE_FAILED。
     * <p>findByCondition / countByCondition 显式携带该 IN 条件——收口即物理 DELETE、
     * 行消失天然不可达，保留状态全集条件仅防「未来扩展新状态」的意外可见。</p>
     */
    List<String> VISIBLE_LIFECYCLE_STATUSES = List.of(
            LifecycleStatus.ACTIVE.name(),
            LifecycleStatus.DELETING.name(),
            LifecycleStatus.DELETE_FAILED.name());

    /**
     * 按名称查询知识库（uk_kb_name 普通唯一索引，全表唯一；已删除库行物理不存在，天然不参与判重）。
     *
     * @param name 知识库名称
     * @return 命中的实体；无命中返回 null
     */
    default KnowledgeBaseEntity selectByName(String name) {
        return selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 条件分页查询：关键字对名称与描述模糊匹配，状态精确过滤，按白名单字段与方向排序，
     * 恒以 ID 倒序作为次级排序兜底，保证同值记录的分页稳定。
     *
     * @param keyword   搜索关键字（可空）
     * @param status    生命周期状态（可空）
     * @param sortField 排序字段（可空，为空时按创建时间）
     * @param ascending 是否升序
     * @param offset    偏移量
     * @param size      每页条数
     * @return 知识库实体列表
     */
    default List<KnowledgeBaseEntity> selectByCondition(String keyword, String status,
                                                        KnowledgeBaseSortField sortField, boolean ascending,
                                                        long offset, int size) {
        LambdaQueryWrapper<KnowledgeBaseEntity> wrapper = buildCondition(keyword, status);
        applySort(wrapper, ObjectUtils.isEmpty(sortField) ? KnowledgeBaseSortField.CREATED_AT : sortField, ascending);
        return selectList(wrapper
                .orderByDesc(KnowledgeBaseEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 应用白名单排序：将排序字段枚举映射到实体列并按方向追加到查询条件。
     *
     * @param wrapper   待追加排序的查询条件
     * @param sortField 排序字段（非空）
     * @param ascending 是否升序
     */
    private void applySort(LambdaQueryWrapper<KnowledgeBaseEntity> wrapper,
                           KnowledgeBaseSortField sortField, boolean ascending) {
        // 枚举已穷举全部白名单取值，无需 default 分支
        switch (sortField) {
            case NAME -> {
                if (ascending) {
                    wrapper.orderByAsc(KnowledgeBaseEntity::getName);
                } else {
                    wrapper.orderByDesc(KnowledgeBaseEntity::getName);
                }
            }
            case CREATED_AT -> {
                if (ascending) {
                    wrapper.orderByAsc(KnowledgeBaseEntity::getCreatedAt);
                } else {
                    wrapper.orderByDesc(KnowledgeBaseEntity::getCreatedAt);
                }
            }
            case UPDATED_AT -> {
                if (ascending) {
                    wrapper.orderByAsc(KnowledgeBaseEntity::getUpdatedAt);
                } else {
                    wrapper.orderByDesc(KnowledgeBaseEntity::getUpdatedAt);
                }
            }
        }
    }

    /**
     * 条件统计总数。
     *
     * @param keyword 搜索关键字（可空）
     * @param status  生命周期状态（可空）
     * @return 命中记录数
     */
    default long countByCondition(String keyword, String status) {
        Long count = selectCount(buildCondition(keyword, status));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 悲观锁读取单条（级联删除 / 状态流转前的行锁）。
     *
     * @param id 主键
     * @return 命中的实体；无命中返回 null
     */
    default KnowledgeBaseEntity selectByIdForUpdate(Long id) {
        return selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getId, id)
                .last("FOR UPDATE"));
    }

    /**
     * 按生命周期状态统计库数量（stats 口径：调用方传 ACTIVE 即「可用库数」）。
     *
     * @param status 生命周期状态；为空时统计全部存量记录
     * @return 命中记录数
     */
    default long countByStatus(String status) {
        Long count = selectCount(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(StringUtils.isNotBlank(status), KnowledgeBaseEntity::getLifecycleStatus, status));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 按生命周期状态取全部主键 ID（id 升序，仅回读 id 列，保留能力；启动恢复已改为按本实例在飞注册表键读取残留）。
     * <p>彻底物理删体系：已收口库行物理不存在，天然不可达。</p>
     *
     * @param status 生命周期状态名
     * @return 知识库 ID 列表（升序）；入参空白或无命中返回空列表
     */
    default List<Long> selectAllIdsByLifecycleStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return List.of();
        }
        return selectList(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .select(KnowledgeBaseEntity::getId)
                .eq(KnowledgeBaseEntity::getLifecycleStatus, status)
                .orderByAsc(KnowledgeBaseEntity::getId))
                .stream()
                .map(KnowledgeBaseEntity::getId)
                .toList();
    }

    /**
     * 生命周期状态 CAS 迁移（单条 UPDATE 条件更新，不做先查后写）。
     * <p>{@code WHERE id = ? AND lifecycle_status = ?} 原子判定并迁移，命中时同步刷新
     * {@code updated_at}；已收口库行物理不存在，天然不可达。重复调用第二次起状态不符、
     * 零影响（幂等）。</p>
     *
     * @param id         知识库主键（调用方保证非空）
     * @param fromStatus 迁移起点状态名（调用方保证非空）
     * @param toStatus   迁移目标状态名（调用方保证非空）
     * @return 受影响行数（1=命中，0=未命中）
     */
    @Update("""
            UPDATE knowledge_base
            SET lifecycle_status = #{toStatus}, updated_at = now()
            WHERE id = #{id}
              AND lifecycle_status = #{fromStatus}
            """)
    int transitLifecycleStatus(@Param("id") Long id,
                               @Param("fromStatus") String fromStatus,
                               @Param("toStatus") String toStatus);

    /**
     * 生命周期状态 CAS 迁移并同语句写入失败留痕（单条 UPDATE 条件更新，不做先查后写）。
     * <p>供清退失败落点（DELETING → DELETE_FAILED + error_message）使用：
     * {@code WHERE id = ? AND lifecycle_status = ?} 原子判定，非源态零行命中（幂等空转）；
     * 已收口库行物理不存在，天然不可达。命中时同步刷新 {@code updated_at}。</p>
     *
     * @param id           知识库主键（调用方保证非空）
     * @param fromStatus   迁移起点状态名（调用方保证非空）
     * @param toStatus     迁移目标状态名（调用方保证非空）
     * @param errorMessage 失败留痕文案（写入 error_message 列，调用方已截断），可为空
     * @return 受影响行数（1=命中，0=未命中）
     */
    @Update("""
            UPDATE knowledge_base
            SET lifecycle_status = #{toStatus}, error_message = #{errorMessage}, updated_at = now()
            WHERE id = #{id}
              AND lifecycle_status = #{fromStatus}
            """)
    int transitLifecycleStatusWithMessage(@Param("id") Long id,
                                          @Param("fromStatus") String fromStatus,
                                          @Param("toStatus") String toStatus,
                                          @Param("errorMessage") String errorMessage);

    private LambdaQueryWrapper<KnowledgeBaseEntity> buildCondition(String keyword, String status) {
        return Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                // 列表可见性：三态全集显式可见，防未来扩展新状态的意外可见
                .in(KnowledgeBaseEntity::getLifecycleStatus, VISIBLE_LIFECYCLE_STATUSES)
                .and(StringUtils.isNotBlank(keyword), w -> w
                        .like(KnowledgeBaseEntity::getName, keyword)
                        .or()
                        .like(KnowledgeBaseEntity::getDescription, keyword))
                .eq(StringUtils.isNotBlank(status), KnowledgeBaseEntity::getLifecycleStatus, status);
    }
}
