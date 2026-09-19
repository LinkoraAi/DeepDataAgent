package com.linkroa.deepdataagent.auth.domain.repository;

import com.linkroa.deepdataagent.auth.domain.model.User;

import java.util.Optional;

/**
 * 用户仓储接口（依赖倒置，声明领域语义方法）。
 */
public interface UserRepository {

    /**
     * 保存用户并返回带自增 user_id 的完整聚合。
     */
    User save(User user);

    /**
     * 按数字 user_id 查询。
     */
    Optional<User> findByUserId(Long userId);

    /**
     * 按登录邮箱查询（登录 / 重复注册校验）。
     */
    Optional<User> findByEmail(String email);

    /**
     * 按登录邮箱锁定该行（FOR UPDATE），用于并发注册下的 check-then-act 串行化。
     */
    Optional<User> findByEmailForUpdate(String email);
}