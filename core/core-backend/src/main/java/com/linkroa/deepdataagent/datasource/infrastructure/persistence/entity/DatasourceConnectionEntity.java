package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_connection")
public class DatasourceConnectionEntity extends BaseEntity {

    private String name;
    private String type;
    private String subType;
    private String status;
    /** JDBC 连接配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String jdbcConnectionConfig;
    private String description;

    /**
     * 归属用户 ID（{@code datasource_connection.owner_id} 列）：创建即定、一经写入不可变。
     * <p>列表过滤与按标识寻址的归属校验均以本列为唯一依据。</p>
     */
    @TableField("owner_id")
    private Long ownerId;
}