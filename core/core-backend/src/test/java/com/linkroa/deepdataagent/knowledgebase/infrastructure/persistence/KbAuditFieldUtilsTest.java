package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link KbAuditFieldUtils} 审计字段显式填充工具单测（彻底物理删体系）。
 * <p>覆盖：insert 四字段填充与时间同源、update 仅刷新更新态两字段、操作人空白回落系统缺省、
 * null 入参防御不抛出、时间中国时区口径。测试夹具为局部显式实现 {@link KbAuditable} 的
 * 简易实体（无外部依赖，无需 Mock）。</p>
 */
class KbAuditFieldUtilsTest {

    /** 系统缺省操作人断言基准（与工具类常量同值，独立书写以锁定契约） */
    private static final String SYSTEM_OPERATOR = "system";

    /**
     * 构造预置创建态审计的测试实体（模拟「更新前已被填充过 insert 审计」的存量行）。
     *
     * @return 预置四字段与创建时间的实体
     */
    private static TestAuditable entityWithCreateAudit() {
        TestAuditable entity = new TestAuditable();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
        entity.setCreatedBy("creator");
        entity.setUpdatedBy("creator");
        return entity;
    }

    @Test
    void should_fillFourAuditFieldsWithSameTime_when_fillInsert_given_validEntityAndOperator() {
        // given
        TestAuditable entity = new TestAuditable();

        // when
        KbAuditFieldUtils.fillInsert(entity, "7");

        // then：四字段全部非空、创建/更新时间同源、操作人双侧落给定值
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertEquals(entity.getCreatedAt(), entity.getUpdatedAt());
        assertEquals("7", entity.getCreatedBy());
        assertEquals("7", entity.getUpdatedBy());
    }

    @Test
    void should_fallbackToDefaultOperator_when_fillInsert_given_blankOperator() {
        // given：null 与纯空白串两类空白操作人
        TestAuditable nullOperatorEntity = new TestAuditable();
        TestAuditable whitespaceOperatorEntity = new TestAuditable();

        // when
        KbAuditFieldUtils.fillInsert(nullOperatorEntity, null);
        KbAuditFieldUtils.fillInsert(whitespaceOperatorEntity, "   ");

        // then：均回落系统缺省操作人
        assertEquals(SYSTEM_OPERATOR, nullOperatorEntity.getCreatedBy());
        assertEquals(SYSTEM_OPERATOR, nullOperatorEntity.getUpdatedBy());
        assertEquals(SYSTEM_OPERATOR, whitespaceOperatorEntity.getCreatedBy());
        assertEquals(SYSTEM_OPERATOR, whitespaceOperatorEntity.getUpdatedBy());
    }

    @Test
    void should_refreshUpdateFieldsOnly_when_fillUpdate_given_entityWithExistingCreateAudit() {
        // given：创建态字段已有既成事实
        TestAuditable entity = entityWithCreateAudit();
        OffsetDateTime originalCreatedAt = entity.getCreatedAt();
        OffsetDateTime originalUpdatedAt = entity.getUpdatedAt();

        // when
        KbAuditFieldUtils.fillUpdate(entity, "8");

        // then：updatedAt 刷新（不早于原值）、updatedBy 覆盖，创建态两字段不被触碰
        assertEquals("8", entity.getUpdatedBy());
        assertEquals(originalCreatedAt, entity.getCreatedAt());
        assertEquals("creator", entity.getCreatedBy());
        assertNotNull(entity.getUpdatedAt());
        assertFalse(entity.getUpdatedAt().isBefore(originalUpdatedAt));
    }

    @Test
    void should_fallbackToDefaultOperator_when_fillUpdate_given_nullOperator() {
        // given
        TestAuditable entity = entityWithCreateAudit();

        // when
        KbAuditFieldUtils.fillUpdate(entity, null);

        // then：操作人缺省回落 system，创建人不被改写
        assertEquals(SYSTEM_OPERATOR, entity.getUpdatedBy());
        assertEquals("creator", entity.getCreatedBy());
    }

    @Test
    void should_doNothingWithoutException_when_fillInsert_given_nullEntity() {
        // when // then：null 实体防御式静默跳过，不抛异常（由调用方保证实体有效性）
        assertDoesNotThrow(() -> KbAuditFieldUtils.fillInsert(null, "7"));
    }

    @Test
    void should_doNothingWithoutException_when_fillUpdate_given_nullEntity() {
        // when // then：null 实体防御式静默跳过，不抛异常
        assertDoesNotThrow(() -> KbAuditFieldUtils.fillUpdate(null, "7"));
    }

    @Test
    void should_useChinaZoneOffset_when_now_given_any() {
        // when
        OffsetDateTime now = KbAuditFieldUtils.now();

        // then：中国时区固定 +08:00 偏移（与建表 TIMESTAMPTZ 写读口径对齐）
        assertNotNull(now);
        assertEquals(ZoneOffset.ofHours(8), now.getOffset());
    }

    /**
     * 测试夹具：显式实现 {@link KbAuditable} 的最小实体（仅承载 audit 四字段，局部、显式、可读）。
     */
    private static final class TestAuditable implements KbAuditable {

        /** 创建时间 */
        private OffsetDateTime createdAt;

        /** 更新时间 */
        private OffsetDateTime updatedAt;

        /** 创建人 */
        private String createdBy;

        /** 更新人 */
        private String updatedBy;

        @Override
        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }

        @Override
        public void setCreatedAt(OffsetDateTime createdAt) {
            this.createdAt = createdAt;
        }

        @Override
        public OffsetDateTime getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public void setUpdatedAt(OffsetDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }

        @Override
        public String getCreatedBy() {
            return createdBy;
        }

        @Override
        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        @Override
        public String getUpdatedBy() {
            return updatedBy;
        }

        @Override
        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }
    }
}
