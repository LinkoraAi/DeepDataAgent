package com.linkroa.deepdataagent.rag.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link RagAuditFieldUtils} 审计字段填充工具单测（RAG BC 物理删体系）。
 * <p>覆盖：now() 中国时区口径；fillInsert 四字段全量填充与操作人空白/为 null 回落
 * {@code system}；fillUpdate 仅刷新 updated_at / updated_by 且不触碰创建态字段；
 * null 实体防御（空操作不抛异常）。</p>
 *
 * @author DeepDataAgent
 */
class RagAuditFieldUtilsTest {

    /** 有效操作人（非空白，应原样落字段） */
    private static final String OPERATOR = "tester";

    /**
     * 场景：取当前时间。
     * 预期：偏移量为中国时区固定偏移 +08:00（Asia/Shanghai）。
     */
    @Test
    void should_returnChinaZoneOffset_when_now_given_invocation() {
        // when
        OffsetDateTime now = RagAuditFieldUtils.now();

        // then
        assertNotNull(now);
        assertEquals(ZoneOffset.ofHours(8), now.getOffset(),
                "now() 必须按 CHINA_ZONE（Asia/Shanghai）取时");
    }

    /**
     * 场景：插入前填充，操作人有效。
     * 预期：createdAt / updatedAt 非空且同刻，createdBy / updatedBy 均为该操作人。
     */
    @Test
    void should_fillAllFourAuditFields_when_fillInsert_given_validOperator() {
        // given
        FakeAuditable entity = new FakeAuditable();

        // when
        RagAuditFieldUtils.fillInsert(entity, OPERATOR);

        // then
        assertNotNull(entity.getCreatedAt(), "createdAt 必须被填充");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 必须被填充");
        assertEquals(OPERATOR, entity.getCreatedBy());
        assertEquals(OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：插入前填充，操作人为空白串。
     * 预期：createdBy / updatedBy 回落缺省操作人 system。
     */
    @Test
    void should_fallbackToSystemOperator_when_fillInsert_given_blankOperator() {
        // given
        FakeAuditable entity = new FakeAuditable();

        // when
        RagAuditFieldUtils.fillInsert(entity, "   ");

        // then
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getCreatedBy());
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：插入前填充，操作人为 null（无操作人上下文）。
     * 预期：createdBy / updatedBy 回落缺省操作人 system。
     */
    @Test
    void should_fallbackToSystemOperator_when_fillInsert_given_nullOperator() {
        // given
        FakeAuditable entity = new FakeAuditable();

        // when
        RagAuditFieldUtils.fillInsert(entity, null);

        // then
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getCreatedBy());
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：更新前填充，实体已有创建态审计值。
     * 预期：仅 updatedAt / updatedBy 被刷新，createdAt / createdBy 原值不动。
     */
    @Test
    void should_refreshOnlyUpdateFields_when_fillUpdate_given_existingCreateAudit() {
        // given：先经 fillInsert 建立创建态基线
        FakeAuditable entity = new FakeAuditable();
        RagAuditFieldUtils.fillInsert(entity, "creator");
        OffsetDateTime originalCreatedAt = entity.getCreatedAt();

        // when
        RagAuditFieldUtils.fillUpdate(entity, OPERATOR);

        // then：创建态保持、更新态刷新
        assertSame(originalCreatedAt, entity.getCreatedAt(), "fillUpdate 不得触碰 createdAt");
        assertEquals("creator", entity.getCreatedBy(), "fillUpdate 不得触碰 createdBy");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 必须被刷新");
        assertEquals(OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：更新前填充，操作人为 null。
     * 预期：updatedBy 回落缺省操作人 system。
     */
    @Test
    void should_fallbackToSystemOperator_when_fillUpdate_given_nullOperator() {
        // given
        FakeAuditable entity = new FakeAuditable();

        // when
        RagAuditFieldUtils.fillUpdate(entity, null);

        // then
        assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, entity.getUpdatedBy());
    }

    /**
     * 场景：实体为 null（防御分支）。
     * 预期：fillInsert / fillUpdate 均空操作、不抛异常。
     */
    @Test
    void should_doNothingWithoutException_when_fill_given_nullEntity() {
        // when & then
        assertDoesNotThrow(() -> RagAuditFieldUtils.fillInsert(null, OPERATOR));
        assertDoesNotThrow(() -> RagAuditFieldUtils.fillUpdate(null, OPERATOR));
    }

    /**
     * {@link RagAuditable} 最小测试替身（纯 POJO，无外部依赖，无需 Mockito）。
     */
    private static class FakeAuditable implements RagAuditable {

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
