package com.linkroa.deepdataagent.auth.infrastructure.convert;

import com.linkroa.deepdataagent.auth.domain.model.User;
import com.linkroa.deepdataagent.auth.infrastructure.persistence.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 用户 ⇄ 持久化实体转换器（BaseEntity.id ↔ 数字 user_id 显式映射）。
 */
@Mapper
public interface UserPersistenceConvert {

    UserPersistenceConvert INSTANCE = Mappers.getMapper(UserPersistenceConvert.class);

    default UserEntity toEntity(User user) {
        UserEntity entity = new UserEntity();
        entity.setId(user.userId());
        entity.setEmail(user.email());
        entity.setPasswordHash(user.passwordHash());
        return entity;
    }

    default User toDomain(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        return User.restore(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}