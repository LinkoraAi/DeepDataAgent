package com.linkroa.deepdataagent.knowledgebase.infrastructure.startup;

import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全文检索依赖启动自检（fail-fast）。
 * <p>BM25 通道依赖三件事同时成立：pg_textsearch 经 {@code shared_preload_libraries} 预加载、
 * 扩展与分词配置已建立、bm25 索引存在且访问方法正确。任一项缺失时，召回层会按空结果降级，
 * 系统将以「少一路召回」的方式静默运行——故本组件在应用启动阶段一次性校验，失败即终止启动。</p>
 * <p>执行时机：{@link InitializingBean#afterPropertiesSet()}，早于 Web 容器对外提供服务；
 * 通过 {@code @DependsOn("flywayInitializer")} 保证在 Flyway 完成 V1 迁移（建索引）之后执行。</p>
 */
@Component
@DependsOn("flywayInitializer")
public class Bm25IndexStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexStartupValidator.class);

    /** 预加载库查询（pg_textsearch 必须出现在 shared_preload_libraries 中）。 */
    static final String PRELOAD_SQL = "SHOW shared_preload_libraries";

    /** 必装扩展查询。 */
    static final String EXTENSIONS_SQL =
            "SELECT extname FROM pg_extension WHERE extname IN ('vector', 'zhparser', 'pg_textsearch')";

    /** bm25 索引存在性 + 访问方法校验（索引名取 ChunkMapper 单点常量，防改名漂移）。 */
    static final String INDEX_SQL =
            "SELECT COUNT(*) FROM pg_class c JOIN pg_am a ON a.oid = c.relam "
                    + "WHERE c.relname = '" + ChunkMapper.BM25_INDEX_NAME + "' AND a.amname = 'bm25'";

    /** 分词配置可用性校验（该配置由容器首启脚本建立）。 */
    static final String TEXT_CONFIG_SQL = "SELECT to_tsvector('public.zhparser_cfg', 'fulltext-probe')::text";

    /** 全文检索依赖的扩展清单。 */
    static final List<String> REQUIRED_EXTENSIONS = List.of("vector", "zhparser", "pg_textsearch");

    /** 预加载库标识。 */
    static final String REQUIRED_PRELOAD_LIBRARY = "pg_textsearch";

    /** 查询模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造全文检索依赖自检器。
     *
     * @param jdbcTemplate JDBC 模板（用于读取预加载项、扩展、索引与分词配置状态）
     */
    public Bm25IndexStartupValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * 执行四项校验，任一失败抛出 {@link IllegalStateException} 终止启动。
     *
     * @throws IllegalStateException 缺少预加载项、扩展、bm25 索引或分词配置不可用
     */
    void validate() {
        validatePreload();
        validateExtensions();
        validateIndex();
        validateTextConfig();
        log.info("[全文检索自检] 通过：pg_textsearch 已预加载，三扩展齐全，bm25 索引与 zhparser_cfg 可用");
    }

    /**
     * 校验 pg_textsearch 已随 postmaster 预加载。
     */
    private void validatePreload() {
        String preload = jdbcTemplate.queryForObject(PRELOAD_SQL, String.class);
        if (preload == null || !preload.contains(REQUIRED_PRELOAD_LIBRARY)) {
            throw new IllegalStateException("[全文检索自检] 失败：shared_preload_libraries 未包含 "
                    + REQUIRED_PRELOAD_LIBRARY + "（当前值=" + preload + "），请检查容器启动参数");
        }
    }

    /**
     * 校验三扩展均已安装在当前数据库。
     */
    private void validateExtensions() {
        List<String> installed = jdbcTemplate.queryForList(EXTENSIONS_SQL, String.class);
        for (String required : REQUIRED_EXTENSIONS) {
            if (installed == null || !installed.contains(required)) {
                throw new IllegalStateException("[全文检索自检] 失败：当前数据库缺少扩展 " + required
                        + "（已安装=" + installed + "），请执行 CREATE EXTENSION");
            }
        }
    }

    /**
     * 校验 bm25 索引存在且访问方法为 bm25。
     */
    private void validateIndex() {
        Integer count = jdbcTemplate.queryForObject(INDEX_SQL, Integer.class);
        if (count == null || count < 1) {
            throw new IllegalStateException("[全文检索自检] 失败：未找到 bm25 访问方法的索引 "
                    + ChunkMapper.BM25_INDEX_NAME + "，请确认 V1 迁移已执行");
        }
    }

    /**
     * 校验中文分词配置可执行（zhparser 及其词性映射已就绪）。
     */
    private void validateTextConfig() {
        try {
            jdbcTemplate.queryForObject(TEXT_CONFIG_SQL, String.class);
        } catch (DataAccessException e) {
            throw new IllegalStateException("[全文检索自检] 失败：分词配置 public.zhparser_cfg 不可用", e);
        }
    }
}