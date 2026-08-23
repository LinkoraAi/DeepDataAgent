package com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.SecretEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 凭证密钥 Mapper
 */
@Mapper
public interface SecretMapper extends BaseMapper<SecretEntity> {

    default SecretEntity selectBySecretId(String secretId) {
        return selectOne(Wrappers.<SecretEntity>lambdaQuery()
                .eq(e -> e.getSecretId(), secretId)
                .last("LIMIT 1"));
    }

    default List<SecretEntity> selectByIds(List<String> secretIds) {
        if (secretIds == null || secretIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<SecretEntity>lambdaQuery()
                .in(e -> e.getSecretId(), secretIds));
    }

    default SecretEntity selectBySecretIdForUpdate(String secretId) {
        return selectOne(Wrappers.<SecretEntity>lambdaQuery()
                .eq(e -> e.getSecretId(), secretId)
                .last("FOR UPDATE"));
    }

    default List<SecretEntity> selectPage(long offset, int size) {
        return selectList(Wrappers.<SecretEntity>lambdaQuery()
                .orderByAsc(e -> e.getCreatedAt())
                .orderByAsc(e -> e.getId())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countAll() {
        return selectCount(Wrappers.<SecretEntity>lambdaQuery());
    }
}