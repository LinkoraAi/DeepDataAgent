package com.linkroa.deepdataagent.knowledgebase.infrastructure.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Bm25IndexStartupValidator} 单元测试：覆盖四类依赖缺失分支与全通过分支。
 * <p>校验顺序固定为「预加载 → 扩展 → 索引 → 分词配置」，前一项失败即终止后续查询，
 * 因此各失败用例只打桩至失败点，并断言后续查询零触达。</p>
 */
@ExtendWith(MockitoExtension.class)
class Bm25IndexStartupValidatorTest {

    /** 分词配置探针返回值（内容无关，只需可执行）。 */
    private static final String TS_PROBE_RESULT = "'fulltext-probe':1";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private Bm25IndexStartupValidator validator;

    @Test
    void should_pass_when_allDependenciesPresent() {
        // given
        stubAllReady();

        // when // then
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void should_throw_when_preloadMissing() {
        // given：预加载项不含 pg_textsearch
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.PRELOAD_SQL, String.class))
                .thenReturn("pg_stat_statements");

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);

        // then：首项失败即终止，后续依赖零查询
        assertTrue(exception.getMessage().contains("shared_preload_libraries"));
        verify(jdbcTemplate, never()).queryForList(Bm25IndexStartupValidator.EXTENSIONS_SQL, String.class);
    }

    @Test
    void should_throw_when_preloadIsNull() {
        // given：查询返回空值（无配置）
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.PRELOAD_SQL, String.class))
                .thenReturn(null);

        // when // then
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void should_throw_when_extensionMissing() {
        // given：预加载通过，但当前库缺少 pg_textsearch 扩展
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.PRELOAD_SQL, String.class))
                .thenReturn("pg_textsearch");
        when(jdbcTemplate.queryForList(Bm25IndexStartupValidator.EXTENSIONS_SQL, String.class))
                .thenReturn(List.of("vector", "zhparser"));

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);

        // then
        assertTrue(exception.getMessage().contains("pg_textsearch"));
        verify(jdbcTemplate, never()).queryForObject(Bm25IndexStartupValidator.INDEX_SQL, Integer.class);
    }

    @Test
    void should_throw_when_extensionQueryReturnsNull() {
        // given
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.PRELOAD_SQL, String.class))
                .thenReturn("pg_textsearch");
        when(jdbcTemplate.queryForList(Bm25IndexStartupValidator.EXTENSIONS_SQL, String.class))
                .thenReturn(null);

        // when // then
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void should_throw_when_bm25IndexMissing() {
        // given：扩展齐全，但未找到 bm25 访问方法的索引
        stubPreloadAndExtensions();
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.INDEX_SQL, Integer.class)).thenReturn(0);

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);

        // then
        assertTrue(exception.getMessage().contains("idx_chunk_bm25_content"));
        verify(jdbcTemplate, never()).queryForObject(Bm25IndexStartupValidator.TEXT_CONFIG_SQL, String.class);
    }

    @Test
    void should_throw_when_indexQueryReturnsNull() {
        // given
        stubPreloadAndExtensions();
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.INDEX_SQL, Integer.class)).thenReturn(null);

        // when // then
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void should_throw_when_textConfigUnavailable() {
        // given：扩展与索引齐全，但分词配置不可用（如 zhparser 未建立配置）
        stubPreloadAndExtensions();
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.INDEX_SQL, Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.TEXT_CONFIG_SQL, String.class))
                .thenThrow(new DataAccessResourceFailureException("text search configuration does not exist"));

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);

        // then
        assertTrue(exception.getMessage().contains("zhparser_cfg"));
    }

    /**
     * 打桩：预加载、扩展、索引、分词配置四项全部就绪。
     */
    private void stubAllReady() {
        stubPreloadAndExtensions();
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.INDEX_SQL, Integer.class)).thenReturn(1);
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.TEXT_CONFIG_SQL, String.class))
                .thenReturn(TS_PROBE_RESULT);
    }

    /**
     * 打桩：预加载与三扩展就绪。
     */
    private void stubPreloadAndExtensions() {
        when(jdbcTemplate.queryForObject(Bm25IndexStartupValidator.PRELOAD_SQL, String.class))
                .thenReturn("pg_textsearch");
        when(jdbcTemplate.queryForList(Bm25IndexStartupValidator.EXTENSIONS_SQL, String.class))
                .thenReturn(List.of("vector", "zhparser", "pg_textsearch"));
    }
}