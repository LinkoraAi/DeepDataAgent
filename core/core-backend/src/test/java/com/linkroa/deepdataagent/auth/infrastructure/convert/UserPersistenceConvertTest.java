package com.linkroa.deepdataagent.auth.infrastructure.convert;

import com.linkroa.deepdataagent.auth.domain.model.User;
import com.linkroa.deepdataagent.auth.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UserPersistenceConvert} 单测：空实体守卫（未命中查询返回 null 而非 NPE）
 * 与 BaseEntity.id ⇄ user_id、email、password_hash、时间戳的全字段映射。
 */
class UserPersistenceConvertTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T10:00:00+08:00");

    private UserEntity buildEntity() {
        UserEntity entity = new UserEntity();
        entity.setId(7L);
        entity.setEmail("analyst@linkroa.com");
        entity.setPasswordHash("$2a$10$hash");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    @Test
    void should_returnNull_when_toDomain_given_nullEntity() {
        // given（selectById / selectByEmail 未命中行返回 null）
        UserEntity entity = null;

        // when
        User user = UserPersistenceConvert.INSTANCE.toDomain(entity);

        // then（守卫返回 null，交由 Optional.ofNullable 归一为空 Optional）
        assertNull(user);
    }

    @Test
    void should_mapAllFields_when_toDomain_given_entity() {
        // given
        UserEntity entity = buildEntity();

        // when
        User user = UserPersistenceConvert.INSTANCE.toDomain(entity);

        // then
        assertEquals(7L, user.userId());
        assertEquals("analyst@linkroa.com", user.email());
        assertEquals("$2a$10$hash", user.passwordHash());
        assertEquals(NOW, user.createdAt());
        assertEquals(NOW, user.updatedAt());
    }

    @Test
    void should_mapUserIdAndCredentials_when_toEntity_given_user() {
        // given
        User original = User.restore(7L, "analyst@linkroa.com", "$2a$10$hash", NOW, NOW);

        // when
        UserEntity entity = UserPersistenceConvert.INSTANCE.toEntity(original);
        User restored = UserPersistenceConvert.INSTANCE.toDomain(entity);

        // then（BaseEntity.id ⇄ 数字 user_id 显式映射；时间戳由 MetaObjectHandler 落库时填充，不经 toEntity 承载）
        assertEquals(7L, entity.getId());
        assertEquals("analyst@linkroa.com", entity.getEmail());
        assertEquals("$2a$10$hash", entity.getPasswordHash());
        assertEquals(original.userId(), restored.userId());
        assertEquals(original.email(), restored.email());
        assertEquals(original.passwordHash(), restored.passwordHash());
    }
}
