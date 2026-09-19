package com.linkroa.deepdataagent.auth.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户持久化实体（对应 users 表）。
 * <p>BaseEntity.id 即数字 user_id（BIGSERIAL 主键），JWT {@code sub} 为其字符串形式。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class UserEntity extends BaseEntity {

    /** 登录邮箱（唯一） */
    private String email;

    /** bcrypt 密码散列 */
    private String passwordHash;
}