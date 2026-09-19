package com.linkroa.deepdataagent.auth.infrastructure.repository;

import com.linkroa.deepdataagent.auth.domain.model.User;
import com.linkroa.deepdataagent.auth.domain.repository.UserRepository;
import com.linkroa.deepdataagent.auth.infrastructure.convert.UserPersistenceConvert;
import com.linkroa.deepdataagent.auth.infrastructure.persistence.entity.UserEntity;
import com.linkroa.deepdataagent.auth.infrastructure.persistence.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓储实现（MyBatis-Plus）。
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private final UserMapper mapper;

    public JdbcUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public User save(User user) {
        UserEntity entity = UserPersistenceConvert.INSTANCE.toEntity(user);
        entity.setId(null);
        mapper.insert(entity);
        return UserPersistenceConvert.INSTANCE.toDomain(entity);
    }

    @Override
    public Optional<User> findByUserId(Long userId) {
        return Optional.ofNullable(UserPersistenceConvert.INSTANCE.toDomain(mapper.selectById(userId)));
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(UserPersistenceConvert.INSTANCE.toDomain(mapper.selectByEmail(email)));
    }

    @Override
    public Optional<User> findByEmailForUpdate(String email) {
        return Optional.ofNullable(UserPersistenceConvert.INSTANCE.toDomain(mapper.selectByEmailForUpdate(email)));
    }
}