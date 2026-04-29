package com.linkroa.deepdataagent.memory.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * memory 模块的 Spring Boot 配置映射。
 *
 * <p>所有运行参数统一挂在 {@code app.memory} 下，便于用 application.yaml 或环境变量调整
 * Markdown 根目录、分块策略、检索数量、索引位置和 I/O 缓存行为。</p>
 */
@Getter
@Setter
public class MemoryProperties {

    /**
     * 长期记忆根目录。
     *
     * <p>Markdown 真相源、SQLite 索引库和后续向量索引都会放在该目录下。
     * 默认值 {@code ./data/memory} 适合本地开发；生产环境建议配置到持久化磁盘目录。
     * 对应配置：{@code app.memory.root-path} / {@code APP_MEMORY_ROOT_PATH}。</p>
     */
    private String rootPath = "./data/memory";

    /**
     * Markdown 分块配置。
     *
     * <p>控制单个 Markdown 文件被切分成多少索引块，直接影响检索粒度和索引数量。</p>
     */
    private Chunking chunking = new Chunking();

    /**
     * 召回配置。
     *
     * <p>控制 retrieve 阶段返回多少记忆、最多注入多少字符，以及多路检索融合参数。</p>
     */
    private Retrieve retrieve = new Retrieve();

    /**
     * 记录配置。
     *
     * <p>控制 record 阶段是否记录完整会话、多少条消息才触发一次记忆写入。</p>
     */
    private RecordOptions record = new RecordOptions();

    /**
     * SQLite 索引配置。
     *
     * <p>SQLite 是从 Markdown 派生出的可重建索引层，不是长期记忆真相源。</p>
     */
    private Index index = new Index();

    private Vector vector = new Vector();

    /**
     * 文件 I/O 配置。
     *
     * <p>用于控制 Markdown 文件缓存、刷盘等本地文件访问行为。</p>
     */
    private Io io = new Io();

    /**
     * 时间衰减配置。
     *
     * <p>用于控制被频繁召回的记忆在排序阶段获得多少增强。</p>
     */
    private Temporal temporal = new Temporal();

    /**
     * Markdown 分块参数配置。
     */
    @Getter
    @Setter
    public static class Chunking {

        /**
         * 单个索引块的最大字符数。
         *
         * <p>默认 {@code 1200}。值越小，检索命中越精细，但 chunks 数量和索引成本会增加；
         * 值越大，单次召回上下文更完整，但可能把不相关内容一起带入提示词。
         * 对应配置：{@code app.memory.chunking.max-chars} /
         * {@code APP_MEMORY_CHUNKING_MAX_CHARS}。</p>
         */
        private int maxChars = 1200;

        /**
         * 分块重叠字符数。
         *
         * <p>默认 {@code 120}。预留给后续滑动窗口式分块使用，用于避免关键信息被切在块边界。
         * 当前 MarkdownChunker 主要按标题和 {@link #maxChars} 切块，暂未使用重叠逻辑。
         * 对应配置：{@code app.memory.chunking.overlap-chars} /
         * {@code APP_MEMORY_CHUNKING_OVERLAP_CHARS}。</p>
         */
        private int overlapChars = 120;
    }

    /**
     * 检索参数配置。
     */
    @Getter
    @Setter
    public static class Retrieve {

        /**
         * 单次检索最终返回的记忆条数。
         *
         * <p>默认 {@code 8}。值越大，模型可参考的历史越多，但提示词更长、噪声也更高；
         * 值越小，召回更克制，适合成本敏感或短上下文场景。
         * 对应配置：{@code app.memory.retrieve.top-k} /
         * {@code APP_MEMORY_RETRIEVE_TOP_K}。</p>
         */
        private int topK = 8;

        /**
         * 单次 retrieve 返回文本的最大字符数。
         *
         * <p>默认 {@code 4000}。DeepLongMemory 会在格式化召回结果时按该值截断，
         * 防止长期记忆挤占过多模型上下文。
         * 对应配置：{@code app.memory.retrieve.max-chars} /
         * {@code APP_MEMORY_RETRIEVE_MAX_CHARS}。</p>
         */
        private int maxChars = 4000;

        /**
         * RRF 融合算法的 k 参数。
         *
         * <p>默认 {@code 60}。RRF 使用 {@code 1 / (k + rank)} 融合不同检索通道的排名。
         * 值越大，排名差距被抹平得越多；值越小，Top 排名结果权重更突出。
         * 对应配置：{@code app.memory.retrieve.rrf-k} /
         * {@code APP_MEMORY_RETRIEVE_RRF_K}。</p>
         */
        private int rrfK = 60;

        /**
         * 最低融合分数阈值。
         *
         * <p>默认 {@code 0.0}，表示不过滤。调高后可以减少低相关记忆进入结果，
         * 但也可能降低召回率，尤其是在关键词较短或语义表达不完全一致时。
         * 对应配置：{@code app.memory.retrieve.min-score} /
         * {@code APP_MEMORY_RETRIEVE_MIN_SCORE}。</p>
         */
        private double minScore = 0.0;
    }

    /**
     * 记忆记录参数配置。
     */
    @Getter
    @Setter
    public static class RecordOptions {

        /**
         * 触发 record 的最少消息数。
         *
         * <p>默认 {@code 2}，通常表示至少有一问一答才值得记录。调大可以减少碎片化会话写入；
         * 调小会更积极记录，但可能保存较多无上下文的单条消息。
         * 对应配置：{@code app.memory.record.min-round-size} /
         * {@code APP_MEMORY_RECORD_MIN_ROUND_SIZE}。</p>
         */
        private int minRoundSize = 2;

        /**
         * 是否保存完整会话片段到 episodic 记忆。
         *
         * <p>默认 {@code true}。开启时会把对话摘要和关键原文写入 {@code episodic/}；
         * 关闭时只保留 semantic/skills 等沉淀后的长期信息，适合隐私或存储更敏感的部署场景。
         * 对应配置：{@code app.memory.record.capture-full-conversation} /
         * {@code APP_MEMORY_RECORD_CAPTURE_FULL_CONVERSATION}。</p>
         */
        private boolean captureFullConversation = true;
    }

    /**
     * SQLite 索引层配置。
     */
    @Getter
    @Setter
    public static class Index {

        /**
         * SQLite 索引库路径。
         *
         * <p>默认空字符串，表示使用 {@code rootPath/.index/memory.db}。
         * 只有当你希望把索引库放到单独磁盘或统一数据库目录时才需要显式配置。
         * 该库可从 Markdown 重建，因此不应把它当作唯一备份对象。
         * 对应配置：{@code app.memory.index.db-path} /
         * {@code APP_MEMORY_INDEX_DB_PATH}。</p>
         */
        private String dbPath = "";

        /**
         * 应用启动时是否从 Markdown 全量重建索引。
         *
         * <p>默认 {@code true}，优先保证索引与 Markdown 真相源一致。
         * 记忆文件很多时可以设为 {@code false}，改由增量同步或管理命令触发重建。
         * 对应配置：{@code app.memory.index.rebuild-on-startup} /
         * {@code APP_MEMORY_INDEX_REBUILD_ON_STARTUP}。</p>
         */
        private boolean rebuildOnStartup = true;
    }

    /**
     * 文件 I/O 配置。
     */
    @Getter
    @Setter
    public static class Vector {

        private boolean enabled = true;

        private int dimension = 1536;

        private String distanceMetric = "cosine";

        private String persistencePath = "";

        private int maxDegree = 16;

        private int beamWidth = 100;

        private float neighborOverflow = 1.2f;

        private float alpha = 1.2f;

        private int rebuildThreshold = 0;
    }

    @Getter
    @Setter
    public static class Io {

        /**
         * Markdown 文件缓存配置。
         *
         * <p>缓存层用于减少重复读取 MEMORY.md、USER.md 和检索命中文件的磁盘 I/O。</p>
         */
        private Cache cache = new Cache();
    }

    /**
     * Markdown 文件缓存配置。
     */
    @Getter
    @Setter
    public static class Cache {

        /**
         * 启动时预加载到内存缓存的 Markdown 文件列表。
         *
         * <p>默认预加载 {@code MEMORY.md} 和 {@code USER.md}，因为它们是长期记忆快照的核心文件，
         * 也是最常被读取的文件。可加入其他高频文件，如 {@code semantic/preferences.md}。
         * 对应配置：{@code app.memory.io.cache.preload-files}。</p>
         */
        private List<String> preloadFiles = new ArrayList<>(List.of("MEMORY.md", "USER.md"));

        /**
         * record 结束时是否立即刷盘。
         *
         * <p>默认 {@code true}。开启时写入更可靠，record 完成后 Markdown 真相源已经落盘；
         * 关闭时可减少磁盘写入次数，但进程异常退出时可能丢失尚未 flush 的缓存内容。
         * 对应配置：{@code app.memory.io.cache.flush-on-record} /
         * {@code APP_MEMORY_IO_FLUSH_ON_RECORD}。</p>
         */
        private boolean flushOnRecord = true;

        /**
         * 内存缓存最多保留的文件数量。
         *
         * <p>默认 {@code 200}。值越大，重复检索时磁盘读取越少，但内存占用更高。
         * 淘汰时只会移除干净缓存，不会丢弃尚未刷盘的脏文件。
         * 对应配置：{@code app.memory.io.cache.max-cache-size} /
         * {@code APP_MEMORY_IO_MAX_CACHE_SIZE}。</p>
         */
        private int maxCacheSize = 200;
    }

    /**
     * 时间衰减和召回增强配置。
     */
    @Getter
    @Setter
    public static class Temporal {

        /**
         * 访问次数对召回排序的增强系数。
         *
         * <p>默认 {@code 0.2}。时间衰减排序中会使用 {@code 1 + factor * access_count}
         * 增强常被召回的记忆。调高会让“常用记忆”更稳定地浮到前面；调低则更强调新近性和重要性。
         * 对应配置：{@code app.memory.temporal.recall-boost-factor} /
         * {@code APP_MEMORY_TEMPORAL_RECALL_BOOST_FACTOR}。</p>
         */
        private double recallBoostFactor = 0.2;
    }
}
