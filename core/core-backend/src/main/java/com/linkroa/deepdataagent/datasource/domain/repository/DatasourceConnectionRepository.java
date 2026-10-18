package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;

import java.util.List;
import java.util.Optional;

/**
 * 数据源连接仓储接口
 */
public interface DatasourceConnectionRepository {

    DatasourceConnection save(DatasourceConnection connection);

    DatasourceConnection update(DatasourceConnection connection);

    Optional<DatasourceConnection> findById(Long id);

    /**
     * 按主键与归属者查询（请求面按标识寻址的归属判定入口）。
     * <p>主键不存在与归属不匹配均返回 {@link Optional#empty()}，两情形对外不可区分（不泄露存在性）。
     * MUST NOT 用于跨 BC 契约的元数据读取（{@code DatasourceApi}，见 spec 消费方规矩）。</p>
     *
     * @param id      数据源主键，可为空
     * @param ownerId 归属用户 ID，可为空（空即无命中）
     * @return 命中且归属一致的数据源；否则为空
     */
    Optional<DatasourceConnection> findByIdAndOwner(Long id, Long ownerId);

    /**
     * 判定数据源是否存在且归属指定用户。
     *
     * @param id      数据源主键，可为空
     * @param ownerId 归属用户 ID，可为空（空即返回 false）
     * @return true 表示存在且归属一致
     */
    boolean existsOwned(Long id, Long ownerId);

    Optional<DatasourceConnection> findByName(String name);

    /**
     * 按名称与归属者查询（owner 内重名判定的唯一入口）。
     *
     * @param name    数据源名称
     * @param ownerId 归属用户 ID
     * @return 命中且归属一致的数据源；否则为空
     */
    Optional<DatasourceConnection> findByNameAndOwner(String name, Long ownerId);

    List<DatasourceConnection> findAll();

    /**
     * 条件分页查询。
     *
     * @param keyword 名称关键字，可为空
     * @param type    数据源类型，可为空
     * @param status  状态，可为空
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @param ownerId 归属用户 ID，非空时仅返回该用户资源
     * @return 命中列表
     */
    List<DatasourceConnection> findByCondition(String keyword, DatasourceType type, DatasourceStatus status,
                                               int page, int size, Long ownerId);

    /**
     * 条件统计总数。
     *
     * @param keyword 名称关键字，可为空
     * @param type    数据源类型，可为空
     * @param status  状态，可为空
     * @param ownerId 归属用户 ID，非空时仅统计该用户资源
     * @return 命中记录数
     */
    long countByCondition(String keyword, DatasourceType type, DatasourceStatus status, Long ownerId);

    void updateStatus(Long id, DatasourceStatus status);

    void deleteById(Long id);
}
