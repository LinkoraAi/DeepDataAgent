package com.linkroa.deepdataagent.datasource.domain.service;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import org.springframework.stereotype.Service;

/**
 * 数据源连接领域服务
 * <p>封装数据源连接的核心业务规则和验证逻辑。</p>
 */
@Service
public class DatasourceConnectionDomainService {

    private static final int COMMENT_MAX_LENGTH = 150;


    /**
     * 验证注释内容
     *
     * @param comment 注释内容
     */
    public void validateComment(String comment) {
        if (comment != null && comment.length() > COMMENT_MAX_LENGTH) {
            throw new IllegalArgumentException("注释内容不能超过" + COMMENT_MAX_LENGTH + "个字符");
        }
    }

    /**
     * 验证数据源是否可以启用
     *
     * @param connection 数据源连接
     */
    public void validateCanEnable(DatasourceConnection connection) {
        if (connection.status() != DatasourceStatus.DISABLED) {
            throw new IllegalStateException("只有处于已禁用状态的数据源才能被启用");
        }
    }

    /**
     * 验证数据源是否可以禁用
     *
     * @param connection 数据源连接
     */
    public void validateCanDisable(DatasourceConnection connection) {
        if (connection.status() != DatasourceStatus.ENABLED) {
            throw new IllegalStateException("只有处于已启用状态的数据源才能被禁用");
        }
    }

    /**
     * 验证数据源是否可以删除
     *
     * @param connection 数据源连接
     */
    public void validateCanDelete(DatasourceConnection connection) {
        if (connection.status() != DatasourceStatus.DISABLED) {
            throw new IllegalStateException("只有处于已禁用状态的数据源才能被删除");
        }
    }

    /**
     * 验证数据源是否可以同步元数据
     *
     * @param connection 数据源连接
     */
    public void validateCanSync(DatasourceConnection connection) {
        if (connection.status() != DatasourceStatus.ENABLED) {
            throw new IllegalStateException("只有处于已启用状态的数据源才能同步元数据");
        }
    }

}
