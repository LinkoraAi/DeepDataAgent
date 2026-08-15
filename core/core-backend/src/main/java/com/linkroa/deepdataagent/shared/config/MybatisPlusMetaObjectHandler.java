package com.linkroa.deepdataagent.shared.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * MyBatis-Plus 基础字段自动填充器。
 * <p>INSERT 填充 created_at / updated_at / created_by / updated_by / is_deleted；
 * UPDATE 填充 updated_at / updated_by。
 * 仅当字段为空时填充（strict 语义），业务侧显式赋值优先。</p>
 * <p>时间统一按中国时区 Asia/Shanghai 生成（与建表脚本 SET TIME ZONE 对齐）。</p>
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /** 系统统一时区：中国时区 */
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    /** 无安全上下文时的默认操作人 */
    private static final String DEFAULT_OPERATOR = "system";

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(CHINA_ZONE);
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "createdBy", String.class, DEFAULT_OPERATOR);
        this.strictInsertFill(metaObject, "updatedBy", String.class, DEFAULT_OPERATOR);
        this.strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now(CHINA_ZONE));
        this.strictUpdateFill(metaObject, "updatedBy", String.class, DEFAULT_OPERATOR);
    }
}