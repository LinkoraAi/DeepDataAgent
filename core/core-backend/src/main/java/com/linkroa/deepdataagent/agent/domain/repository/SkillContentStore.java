package com.linkroa.deepdataagent.agent.domain.repository;

/**
 * 技能内容存储端口（可扩展：本期 LOCAL_FILE，预留 OSS）。
 * <p>领域层仅依赖端口抽象，存储后端选择不影响上传/下载/校验行为。</p>
 */
public interface SkillContentStore {

    /**
     * 写入技能包内容
     *
     * @param skillId       技能业务ID
     * @param versionNumber 版本号
     * @param content       二进制内容
     * @return 存储 key（相对路径或对象存储 key，落地到 skill_resource.storage_key）
     */
    String put(String skillId, int versionNumber, byte[] content);

    /**
     * 读取技能包内容
     *
     * @param storageKey 存储 key（与写入返回值一致）
     * @return 二进制内容
     * @throws IllegalStateException 内容缺失（存储损坏）
     */
    byte[] get(String storageKey);

    /**
     * 删除技能包内容
     *
     * @param storageKey 存储 key
     */
    void delete(String storageKey);
}