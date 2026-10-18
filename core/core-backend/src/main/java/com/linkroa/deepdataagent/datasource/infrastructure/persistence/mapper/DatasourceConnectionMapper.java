package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 数据源连接 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code DatasourceConnectionEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface DatasourceConnectionMapper extends BaseMapper<DatasourceConnectionEntity> {

    default DatasourceConnectionEntity selectByName(String name) {
        return selectOne(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .eq(DatasourceConnectionEntity::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 按名称与归属者查询（uk_name(owner_id, name)：owner 内唯一，跨用户允许同名）。
     *
     * @param name    数据源名称
     * @param ownerId 归属用户 ID（判重必须在同一归属者内进行）
     * @return 命中的实体；无命中返回 null
     */
    default DatasourceConnectionEntity selectByNameAndOwner(String name, Long ownerId) {
        return selectOne(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .eq(DatasourceConnectionEntity::getName, name)
                .eq(DatasourceConnectionEntity::getOwnerId, ownerId)
                .last("LIMIT 1"));
    }

    /**
     * 按主键与归属者查询单条（请求面按标识寻址的归属判定入口）。
     * <p>主键不存在与归属不匹配均返回 null（两情形对外不可区分，不泄露存在性）。</p>
     *
     * @param id      主键
     * @param ownerId 归属用户 ID
     * @return 命中的实体；无命中或归属不匹配返回 null
     */
    default DatasourceConnectionEntity selectByIdAndOwner(Long id, Long ownerId) {
        return selectOne(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .eq(DatasourceConnectionEntity::getId, id)
                .eq(DatasourceConnectionEntity::getOwnerId, ownerId)
                .last("LIMIT 1"));
    }

    default List<DatasourceConnectionEntity> selectAll() {
        return selectList(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .orderByDesc(DatasourceConnectionEntity::getUpdatedAt));
    }

    /**
     * 条件分页查询。
     *
     * @param keyword 名称关键字（可空）
     * @param type    数据源类型（可空）
     * @param status  状态（可空）
     * @param offset  偏移量
     * @param size    每页条数
     * @param ownerId 归属用户 ID，非空时仅返回该用户资源
     * @return 命中实体列表
     */
    default List<DatasourceConnectionEntity> selectByCondition(String keyword, String type, String status,
                                                               long offset, int size, Long ownerId) {
        return selectList(buildCondition(keyword, type, status, ownerId)
                .orderByAsc(DatasourceConnectionEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 条件统计总数。
     *
     * @param keyword 名称关键字（可空）
     * @param type    数据源类型（可空）
     * @param status  状态（可空）
     * @param ownerId 归属用户 ID，非空时仅统计该用户资源
     * @return 命中记录数
     */
    default long countByCondition(String keyword, String type, String status, Long ownerId) {
        return selectCount(buildCondition(keyword, type, status, ownerId));
    }

    default int updateStatus(Long id, String status) {
        return update(null, Wrappers.<DatasourceConnectionEntity>lambdaUpdate()
                .set(DatasourceConnectionEntity::getStatus, status)
                .set(DatasourceConnectionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(DatasourceConnectionEntity::getId, id));
    }

    private LambdaQueryWrapper<DatasourceConnectionEntity> buildCondition(String keyword, String type, String status,
                                                                         Long ownerId) {
        return Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), DatasourceConnectionEntity::getName, keyword)
                .eq(type != null && !type.isBlank(), DatasourceConnectionEntity::getType, type)
                .eq(status != null && !status.isBlank(), DatasourceConnectionEntity::getStatus, status)
                // 归属隔离：请求面恒传当前用户；后台 / 未认证链路传 null 即不加过滤
                .eq(ownerId != null, DatasourceConnectionEntity::getOwnerId, ownerId);
    }
}
