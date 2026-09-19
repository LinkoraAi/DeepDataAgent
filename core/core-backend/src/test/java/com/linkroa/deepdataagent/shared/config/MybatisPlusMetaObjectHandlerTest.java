package com.linkroa.deepdataagent.shared.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MybatisPlusMetaObjectHandler} 单元测试：
 * 覆盖操作人取值（请求线程 user_id / 非请求线程 system 回落）、
 * strict「业务显式赋值优先」语义与 UPDATE 路径同口径。
 */
class MybatisPlusMetaObjectHandlerTest {

    private final MybatisPlusMetaObjectHandler handler = new MybatisPlusMetaObjectHandler();

    /** 测试专用持久化实体：复用 BaseEntity 审计字段填充注解 */
    static class AuditTestEntity extends BaseEntity {
    }

    @BeforeAll
    static void initTableInfo() {
        // strict 填充依赖 TableInfo 注册（正常运行期由 MyBatis-Plus 扫描完成，单测手工初始化）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditTestEntity.class);
    }

    @BeforeEach
    void setUp() {
        AuthContext.clear();
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 隔离：防止认证上下文串测
        AuthContext.clear();
    }

    @Test
    void should_fillUserIdAsOperator_when_insertFill_given_authenticatedContext() {
        // given
        AuthContext.setUserId(7L);
        AuditTestEntity entity = new AuditTestEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.insertFill(metaObject);

        // then
        assertEquals("7", entity.getCreatedBy());
        assertEquals("7", entity.getUpdatedBy());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertEquals(0, entity.getIsDeleted());
    }

    @Test
    void should_fallbackToSystem_when_insertFill_given_noAuthenticatedContext() {
        // given：后台 / 虚拟线程或未认证入口，ThreadLocal 为空
        AuditTestEntity entity = new AuditTestEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.insertFill(metaObject);

        // then
        assertEquals("system", entity.getCreatedBy());
        assertEquals("system", entity.getUpdatedBy());
    }

    @Test
    void should_keepBusinessAssignedValues_when_insertFill_given_explicitOperatorSet() {
        // given：业务侧显式赋值（如后台任务代填真实归属人）
        AuthContext.setUserId(7L);
        AuditTestEntity entity = new AuditTestEntity();
        entity.setCreatedBy("operator_explicit");
        entity.setUpdatedBy("operator_explicit");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.insertFill(metaObject);

        // then：strict 语义——已有值不被上下文操作人覆盖
        assertEquals("operator_explicit", entity.getCreatedBy());
        assertEquals("operator_explicit", entity.getUpdatedBy());
    }

    @Test
    void should_fillUserIdAsOperator_when_updateFill_given_authenticatedContext() {
        // given
        AuthContext.setUserId(42L);
        AuditTestEntity entity = new AuditTestEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.updateFill(metaObject);

        // then
        assertEquals("42", entity.getUpdatedBy());
        assertNotNull(entity.getUpdatedAt());
        // UPDATE 路径不填充创建侧字段
        assertNull(entity.getCreatedBy());
        assertNull(entity.getCreatedAt());
    }

    @Test
    void should_fallbackToSystem_when_updateFill_given_noAuthenticatedContext() {
        // given
        AuditTestEntity entity = new AuditTestEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.updateFill(metaObject);

        // then
        assertEquals("system", entity.getUpdatedBy());
    }

    @Test
    void should_keepBusinessAssignedValue_when_updateFill_given_explicitOperatorSet() {
        // given
        AuthContext.setUserId(42L);
        AuditTestEntity entity = new AuditTestEntity();
        entity.setUpdatedBy("operator_explicit");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        // when
        handler.updateFill(metaObject);

        // then
        assertEquals("operator_explicit", entity.getUpdatedBy());
    }
}
