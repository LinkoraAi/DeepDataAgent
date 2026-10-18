package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;

import java.util.List;
import java.util.Optional;

/**
 * API Schema仓储接口
 */
public interface ApiSchemaRepository {

    ApiSchema save(ApiSchema apiSchema);

    ApiSchema update(ApiSchema apiSchema);

    Optional<ApiSchema> findById(Long id);

    /**
     * 沿父链解析 API 数据表所属数据源的归属者（api_schema → datasource_connection）。
     * <p>请求面按 API schema 标识寻址的归属判定入口；记录不存在或父链断裂返回
     * {@link Optional#empty()}，与「非本人归属」对外不可区分（不泄露存在性）。</p>
     *
     * @param schemaId API schema 主键，可为空
     * @return 所属数据源的归属用户 ID；无命中返回空
     */
    Optional<Long> findOwnerIdByApiSchemaId(Long schemaId);

    Optional<ApiSchema> findByConnectionIdAndName(Long connectionId, String name);

    List<ApiSchema> findByConnectionId(Long connectionId);

    void deleteByConnectionId(Long connectionId);

    void deleteById(Long id);
}
