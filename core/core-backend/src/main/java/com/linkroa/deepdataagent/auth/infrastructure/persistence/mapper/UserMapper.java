package com.linkroa.deepdataagent.auth.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.auth.infrastructure.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper（数据库访问器）。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    default UserEntity selectByEmail(String email) {
        return selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, email)
                .last("LIMIT 1"));
    }

    default UserEntity selectByEmailForUpdate(String email) {
        return selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, email)
                .last("FOR UPDATE"));
    }

    default UserEntity selectByUserId(Long userId) {
        return selectById(userId);
    }
}