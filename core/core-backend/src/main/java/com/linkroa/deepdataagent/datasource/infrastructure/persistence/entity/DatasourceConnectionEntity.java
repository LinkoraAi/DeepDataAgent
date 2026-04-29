package com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("datasource_connection")
public class DatasourceConnectionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private String subType;
    private String status;
    private String jdbcConnectionConfig;
    private String apiConnectionConfig;
    private String apiAuthConfig;
    private String description;
    private String createdAt;
    private String updatedAt;
    private String createdBy;
    private String updatedBy;
    private Integer isDeleted;
}
