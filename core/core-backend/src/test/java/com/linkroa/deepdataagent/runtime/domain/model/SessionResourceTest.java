package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionResource} 挂载资源值对象不变量单测
 * （四类型扁平结构：{@code file} / {@code github_repository} / {@code git_repository} /
 * {@code memory_store}，按 type 取用对应字段）。
 */
class SessionResourceTest {

    // ==================== 类型常量与外部接口字面量对齐 ====================

    @Test
    void should_exposeContractAlignedConstants_when_referenceConstants_given_fourTypes() {
        // given & when & then（四类型标识与缺省挂载前缀须与外部接口字面量逐字一致）
        assertEquals("file", SessionResource.FILE_TYPE);
        assertEquals("github_repository", SessionResource.GITHUB_REPO_TYPE);
        assertEquals("git_repository", SessionResource.GIT_REPO_TYPE);
        assertEquals("memory_store", SessionResource.MEMORY_STORE_TYPE);
        assertEquals("mounts", SessionResource.MOUNTS_ROOT);
        assertEquals("mounts/", SessionResource.DEFAULT_MOUNT_PATH_PREFIX);
        assertEquals(2, SessionResource.MEMORY_STORE_ACCESS_MODES.size());
        assertTrue(SessionResource.MEMORY_STORE_ACCESS_MODES.contains("read_write"));
        assertTrue(SessionResource.MEMORY_STORE_ACCESS_MODES.contains("read_only"));
    }

    // ==================== file：文件挂载 ====================

    @Test
    void should_createFileResource_when_file_given_customMountPath() {
        // when（自定义挂载路径：mounts/ 前缀下的嵌套相对路径）
        SessionResource resource = SessionResource.file("file_1", "mounts/dataset/a.txt");

        // then（文件资源仅取用 fileId / mountPath，其余类型字段必须为 null）
        assertEquals(SessionResource.FILE_TYPE, resource.type());
        assertEquals("file_1", resource.fileId());
        assertEquals("mounts/dataset/a.txt", resource.mountPath());
        assertNull(resource.url());
        assertNull(resource.authorizationToken());
        assertNull(resource.checkout());
        assertNull(resource.memoryStoreId());
        assertNull(resource.access());
        assertNull(resource.instructions());
    }

    @Test
    void should_defaultMountPath_when_file_given_nullMountPath() {
        // when（挂载路径留空：仅注入内联上下文，落库前归一为缺省绝对路径）
        SessionResource resource = SessionResource.file("file_9", null);

        // then（缺省挂载路径 = mounts/<file_id>）
        assertEquals("mounts/file_9", resource.mountPath());
    }

    @Test
    void should_defaultMountPath_when_file_given_blankMountPath() {
        // when（空白串与 null 同等对待，一律回落缺省路径）
        SessionResource resource = SessionResource.file("file_9", "   ");

        // then
        assertEquals("mounts/file_9", resource.mountPath());
    }

    @Test
    void should_throw_when_file_given_absoluteMountPath() {
        // given & when & then（反转：绝对路径不再合法，必须为 mounts/ 前缀相对路径）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "/data/in.csv"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "/tmp/notes.md"));
    }

    @Test
    void should_throw_when_file_given_mountPathWithoutMountsPrefix() {
        // given & when & then（非 mounts/ 前缀的工作区相对路径拒绝，避免落到挂载根之外）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "data/a.csv"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mountsa/b"));
    }

    @Test
    void should_throw_when_file_given_traversalOrMalformedMountPath() {
        // given & when & then（穿越 / 反斜杠 / 空段 / 结尾斜杠一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/../secret"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a\\b"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts//a"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/"));
    }

    @Test
    void should_reject_when_file_given_dotSegmentMountPath() {
        // given & when & then（`.` 段归一后与既有路径指向同一物理目标，可绕过判重，一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/./b"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/./a"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/."));
    }

    @Test
    void should_throw_when_construct_given_blankFileId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file(null, null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("  ", "mounts/a.txt"));
    }

    @Test
    void should_throw_when_construct_given_fileIdWithoutFilePrefix() {
        // given & when & then（文件 ID 必须携带 file_ 前缀）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("not-a-file", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("ms_1", null));
    }

    @Test
    void should_throw_when_construct_given_relativeMountPath() {
        // given & when & then（旧口径反例：裸相对路径与穿越写法均缺 mounts/ 前缀，一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "../upload.txt"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "uploads/a.txt"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "etc/passwd"));
    }

    // ==================== github_repository：仓库挂载 ====================

    @Test
    void should_createGithubResource_when_githubRepository_given_urlTokenCheckout() {
        // when
        SessionResource resource = SessionResource.githubRepository(
                "https://github.com/acme/app.git", "ghp_token_only_write", "main");

        // then（仓库资源仅取用 url / authorizationToken / checkout）
        assertEquals(SessionResource.GITHUB_REPO_TYPE, resource.type());
        assertEquals("https://github.com/acme/app.git", resource.url());
        assertEquals("ghp_token_only_write", resource.authorizationToken());
        assertEquals("main", resource.checkout());
        assertNull(resource.fileId());
        assertNull(resource.mountPath());
        assertNull(resource.memoryStoreId());
        assertNull(resource.access());
        assertNull(resource.instructions());
    }

    @Test
    void should_createGithubResource_when_githubRepository_given_nullTokenAndCheckout() {
        // when（令牌与检出的分支 / 标签均可空：仅 url 必填）
        SessionResource resource = SessionResource.githubRepository("https://github.com/acme/app.git", null, null);

        // then
        assertEquals("https://github.com/acme/app.git", resource.url());
        assertNull(resource.authorizationToken());
        assertNull(resource.checkout());
    }

    @Test
    void should_throw_when_construct_given_blankGithubUrl() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.githubRepository(null, "ghp_token", "main"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.githubRepository("", "ghp_token", "main"));
        assertThrows(IllegalArgumentException.class,
                () -> resource(SessionResource.GITHUB_REPO_TYPE, null, null, "   "));
    }

    @Test
    void should_keepForeignTypeFieldsUntouched_when_construct_given_githubTypeWithFileId() {
        // given & when（扁平结构按 type 取用字段：github_repository 类型不校验文件字段）
        SessionResource resource = new SessionResource(null, SessionResource.GITHUB_REPO_TYPE, "file_1", "/uploads/a.txt",
                "https://github.com/acme/app.git", null, null, null, null, null, null);

        // then（非本类型字段原样保留，不做归一也不抛错）
        assertEquals("https://github.com/acme/app.git", resource.url());
        assertEquals("file_1", resource.fileId());
        assertEquals("/uploads/a.txt", resource.mountPath());
    }

    // ==================== memory_store：记忆库挂载 ====================

    @Test
    void should_createMemoryStoreResource_when_memoryStore_given_allFields() {
        // when
        SessionResource resource = SessionResource.memoryStore("ms_1", "read_write", "仅读取本会话相关记忆");

        // then（记忆库资源仅取用 memoryStoreId / access / instructions）
        assertEquals(SessionResource.MEMORY_STORE_TYPE, resource.type());
        assertEquals("ms_1", resource.memoryStoreId());
        assertEquals("read_write", resource.access());
        assertEquals("仅读取本会话相关记忆", resource.instructions());
        assertNull(resource.fileId());
        assertNull(resource.mountPath());
        assertNull(resource.url());
        assertNull(resource.authorizationToken());
        assertNull(resource.checkout());
    }

    @Test
    void should_acceptBothLegalAccessModes_when_memoryStore_given_readWriteOrReadOnly() {
        // when
        SessionResource readWrite = SessionResource.memoryStore("ms_1", "read_write", null);
        SessionResource readOnly = SessionResource.memoryStore("ms_2", "read_only", "只读引用");

        // then
        assertEquals("read_write", readWrite.access());
        assertEquals("read_only", readOnly.access());
        assertEquals("只读引用", readOnly.instructions());
    }

    @Test
    void should_allowNullAccessAndInstructions_when_memoryStore_given_optionalFieldsOmitted() {
        // when（access 留空 = 不覆盖记忆库默认访问模式；instructions 可空）
        SessionResource resource = SessionResource.memoryStore("ms_1", null, null);

        // then
        assertNull(resource.access());
        assertNull(resource.instructions());
    }

    @Test
    void should_throw_when_construct_given_oversizedMemoryStoreInstructions() {
        // given & when & then（契约上限 ≤4096 字符：4096 放行、4097 拒绝）
        assertEquals(4096, SessionResource.MAX_MEMORY_STORE_INSTRUCTIONS_LENGTH);
        SessionResource boundary = SessionResource.memoryStore("ms_1", null, "x".repeat(4096));
        assertEquals(4096, boundary.instructions().length());
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.memoryStore("ms_1", null, "x".repeat(4097)));
    }

    @Test
    void should_throw_when_construct_given_illegalMemoryStoreAccess() {
        // given & when & then（访问模式仅 ∈ {read_write, read_only}：大小写敏感，空串亦属非法）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("ms_1", "readwrite", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("ms_1", "READ_ONLY", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("ms_1", "read_only_write", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("ms_1", "", null));
    }

    @Test
    void should_throw_when_construct_given_blankMemoryStoreId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore(null, "read_only", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("", "read_only", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("  ", null, null));
    }

    @Test
    void should_throw_when_construct_given_memoryStoreIdWithoutPrefix() {
        // given & when & then（记忆库 ID 必须携带 ms_ 前缀）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("mem-1", null, null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.memoryStore("file_1", null, null));
    }

    // ==================== git_repository：通用 Git 仓库挂载（用户名在 URL、密码只写） ====================

    @Test
    void should_createGitRepositoryResource_when_gitRepository_given_urlPasswordCheckout() {
        // when（用户名体现在 URL userinfo 段，密码成对提供）
        SessionResource resource = SessionResource.gitRepository(
                "https://alice@git.example.com/acme/app.git", "s3cr3t_pwd", "release/1.0");

        // then（通用 Git 资源仅取用 url / password / checkout）
        assertEquals(SessionResource.GIT_REPO_TYPE, resource.type());
        assertEquals("https://alice@git.example.com/acme/app.git", resource.url());
        assertEquals("s3cr3t_pwd", resource.password());
        assertEquals("release/1.0", resource.checkout());
        assertNull(resource.fileId());
        assertNull(resource.mountPath());
        assertNull(resource.authorizationToken());
        assertNull(resource.memoryStoreId());
        assertNull(resource.access());
        assertNull(resource.instructions());
    }

    @Test
    void should_allowNullCheckout_when_gitRepository_given_checkoutOmitted() {
        // when（检出分支 / 标签可空：url + password 成对必填）
        SessionResource resource = SessionResource.gitRepository(
                "https://alice@git.example.com/acme/app.git", "s3cr3t_pwd", null);

        // then
        assertNull(resource.checkout());
        assertEquals("s3cr3t_pwd", resource.password());
    }

    @Test
    void should_throw_when_gitRepository_given_urlWithoutUsername() {
        // given & when & then（用户名 MUST 体现在 URL 中：无 userinfo 段一律拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository("https://git.example.com/acme/app.git", "pwd", null));
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository("https://@git.example.com/acme/app.git", "pwd", null));
    }

    @Test
    void should_throw_when_gitRepository_given_blankUrlOrPassword() {
        // given & when & then（url 与 password 均必填，空白同等拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository(null, "pwd", null));
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository("", "pwd", null));
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository("https://alice@git.example.com/a.git", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> SessionResource.gitRepository("https://alice@git.example.com/a.git", "   ", null));
    }

    @Test
    void should_keepForeignTypeFieldsUntouched_when_construct_given_gitTypeWithToken() {
        // given & when（扁平结构按 type 取用字段：git_repository 不消费 authorization_token）
        SessionResource resource = new SessionResource(null, SessionResource.GIT_REPO_TYPE, null, null,
                "https://alice@git.example.com/acme/app.git", "ghp_foreign", "s3cr3t_pwd", "main", null, null, null);

        // then（非本类型字段原样保留，不做归一也不抛错）
        assertEquals("https://alice@git.example.com/acme/app.git", resource.url());
        assertEquals("s3cr3t_pwd", resource.password());
        assertEquals("ghp_foreign", resource.authorizationToken());
        assertEquals("main", resource.checkout());
    }

    // ==================== 通用：类型字段不变量 ====================

    @Test
    void should_throw_when_construct_given_blankType() {
        // given & when & then（类型为空 / 空白一律拒绝，三类型工厂之外不得凭空挂载）
        assertThrows(IllegalArgumentException.class, () -> resource(null, "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("", "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("  ", "file_1", null));
    }

    @Test
    void should_throw_when_construct_given_unsupportedType() {
        // given & when & then（仅支持三类型，未知类型拒绝；类型标识大小写敏感）
        assertThrows(IllegalArgumentException.class, () -> resource("dataset", "ds_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("File", "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("github", null, null, "https://github.com/a/b"));
    }

    // ==================== 资源 ID：sesr_ 前缀自动生成与显式保留 ====================

    @Test
    void should_generateSesrPrefixedId_when_file_given_blankId() {
        // when（工厂不传 id：紧凑构造器自动补齐 sesr_ + 32 位十六进制）
        SessionResource resource = SessionResource.file("file_1", null);

        // then
        assertTrue(resource.id().startsWith(SessionResource.RESOURCE_ID_PREFIX));
        assertEquals(SessionResource.RESOURCE_ID_PREFIX.length() + 32, resource.id().length());
    }

    @Test
    void should_generateDistinctIds_when_file_given_sameArguments() {
        // when（两次同源构造：id 互不相同）
        SessionResource first = SessionResource.file("file_1", null);
        SessionResource second = SessionResource.file("file_1", null);

        // then
        assertTrue(!first.id().equals(second.id()));
    }

    @Test
    void should_keepExplicitId_when_construct_given_nonBlankId() {
        // given & when（显式 id 原样保留，旧 jsonb 行反查后稳定不漂移）
        SessionResource resource = new SessionResource("sesr_fixed0001", SessionResource.FILE_TYPE,
                "file_1", null, null, null, null, null, null, null, null);

        // then
        assertEquals("sesr_fixed0001", resource.id());
    }

    @Test
    void should_regenerateId_when_construct_given_blankIdOnAllTypes() {
        // given & when（三类型工厂与 github 直构路径均补齐 id）
        SessionResource github = SessionResource.githubRepository("https://github.com/acme/app.git", null, null);
        SessionResource memoryStore = SessionResource.memoryStore("ms_1", null, null);

        // then
        assertTrue(github.id().startsWith("sesr_"));
        assertTrue(memoryStore.id().startsWith("sesr_"));
    }

    // ==================== 测试夹具 ====================

    /**
     * 以十一组件扁平构造器直接装配资源项（id 留空自动生成，仅开放前三字段，其余置空），供通用不变量用例复用。
     */
    private SessionResource resource(String type, String fileId, String mountPath) {
        return new SessionResource(null, type, fileId, mountPath, null, null, null, null, null, null, null);
    }

    /**
     * 以十一组件扁平构造器直接装配资源项（id 留空自动生成，额外开放 url），供 github_repository 负例复用。
     */
    private SessionResource resource(String type, String fileId, String mountPath, String url) {
        return new SessionResource(null, type, fileId, mountPath, url, null, null, null, null, null, null);
    }
}
