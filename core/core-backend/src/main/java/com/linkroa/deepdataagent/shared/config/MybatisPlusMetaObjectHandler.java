package com.linkroa.deepdataagent.shared.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.linkroa.deepdataagent.shared.security.AuthContext;
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
 * <p><b>操作人口径</b>：填充器在发起 JDBC 调用的线程内执行，直接读取
 * {@link AuthContext}（JWT 过滤器在请求线程写入的数字 user_id）——
 * 请求线程内的写入记真实操作人（user_id 字符串）；后台 / 虚拟线程与未认证入口
 * 上下文必空，回落约定值 <code>system</code>，其操作人语义以业务 owner_id 与事件账本为准。</p>
 * <p><b>填充覆盖范围盲区（预期行为，非缺陷）</b>：updateFill 仅在 entity-based 写入
 * （update(entity, wrapper) 且 entity 非 null）时触发；
 * update(null, wrapper) 形式的窄列 CAS（如状态机条件更新）不经过本填充器，
 * updated_by 保持原值——此类写入的操作人归因同样看事件账本与业务 owner_id。</p>
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /** 系统统一时区：中国时区 */
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    /** 非请求线程（后台 / 虚拟线程 / 未认证入口）写入的约定操作人 */
    private static final String DEFAULT_OPERATOR = "system";

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(CHINA_ZONE);
        String operator = currentOperator();
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "createdBy", String.class, operator);
        this.strictInsertFill(metaObject, "updatedBy", String.class, operator);
        this.strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now(CHINA_ZONE));
        this.strictUpdateFill(metaObject, "updatedBy", String.class, currentOperator());
    }

    /**
     * 取当前操作人：请求线程读 {@link AuthContext} 中的数字 user_id 字符串；
     * 上下文为空或读取异常（非请求线程）回落 {@code system}。
     */
    private static String currentOperator() {
        try {
            Long userId = AuthContext.getUserId();
            return userId != null ? String.valueOf(userId) : DEFAULT_OPERATOR;
        } catch (Exception e) {
            return DEFAULT_OPERATOR;
        }
    }
}