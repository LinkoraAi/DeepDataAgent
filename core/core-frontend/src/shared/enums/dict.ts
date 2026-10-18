/**
 * 后端枚举前端字典：编码 → 中文标签 + TDesign Tag 主题。
 * 编码一律使用后端真实枚举名（大小写敏感），未知编码由 StatusTag 兜底原样展示。
 */

/** 字典项 */
export interface EnumItem {
  /** 中文标签 */
  label: string;
  /** t-tag theme（default/primary/success/warning/danger） */
  theme: 'default' | 'primary' | 'success' | 'warning' | 'danger';
}

/** 字典表 */
export type EnumDict = Record<string, EnumItem>;

/** 知识库生命周期（LifecycleStatus） */
export const KB_LIFECYCLE: EnumDict = {
  ACTIVE: { label: '正常', theme: 'success' },
  DELETING: { label: '删除中', theme: 'warning' },
  DELETE_FAILED: { label: '删除失败', theme: 'danger' },
};

/** 文档六态（DocumentStatus） */
export const DOC_STATUS: EnumDict = {
  PENDING: { label: '排队中', theme: 'default' },
  PROCESSING: { label: '解析中', theme: 'primary' },
  PROCESSED: { label: '已完成', theme: 'success' },
  FAILED: { label: '失败', theme: 'danger' },
  DELETING: { label: '删除中', theme: 'warning' },
  DELETE_FAILED: { label: '删除失败', theme: 'danger' },
};

/** 处理中文档状态集合（列表轮询触发条件） */
export const DOC_ACTIVE_STATUSES = ['PENDING', 'PROCESSING', 'DELETING'];

/** 文件类型白名单（FileType，13 类） */
export const FILE_TYPES = ['PDF', 'DOC', 'DOCX', 'XLS', 'XLSX', 'PPT', 'PPTX', 'TXT', 'MD', 'CSV', 'PNG', 'JPG', 'JPEG'] as const;

/** 知识库语言（KbLanguage，首字母大写全名，默认 Chinese） */
export const KB_LANGUAGES: Array<{ value: string; label: string }> = [
  { value: 'Chinese', label: '中文' },
  { value: 'English', label: '英语' },
  { value: 'Japanese', label: '日语' },
  { value: 'Korean', label: '韩语' },
  { value: 'Vietnamese', label: '越南语' },
  { value: 'French', label: '法语' },
  { value: 'German', label: '德语' },
  { value: 'Spanish', label: '西班牙语' },
  { value: 'Arabic', label: '阿拉伯语' },
  { value: 'Turkish', label: '土耳其语' },
  { value: 'Dutch', label: '荷兰语' },
];

/** 切片模式（DocumentChunkMode，7 种；仅 GENERAL 携带参数） */
export const CHUNK_MODES: Array<{ value: string; label: string; hint: string }> = [
  { value: 'GENERAL', label: '通用', hint: '按块大小/重叠率/分隔符切分，适用全部文件类型' },
  { value: 'QA', label: '问答', hint: '问答对抽取，适用 DOC/DOCX/XLS/XLSX/TXT/MD/CSV' },
  { value: 'BOOK', label: '书籍', hint: '长文层级切分，适用 PDF/DOC/DOCX/TXT/MD' },
  { value: 'LAWS', label: '法律', hint: '法条结构切分，适用 PDF/DOC/DOCX/TXT/MD' },
  { value: 'TABLE', label: '表格', hint: '表格数据切分，适用 XLS/XLSX/TXT/CSV' },
  { value: 'PRESENTATION', label: '演示', hint: '幻灯片页切分，适用 PDF/PPT/PPTX' },
  { value: 'ONE', label: '整篇', hint: '整文档单块（不含 CSV/图片）' },
];

/** 解析引擎提供方（ParseEngineProvider） */
export const PARSE_PROVIDERS: Array<{ value: string; label: string }> = [
  { value: 'LOCAL', label: '内置解析' },
  { value: 'MINERU', label: 'MinerU（远程版面解析）' },
];

/** 去重匹配轴（DedupMatchRule） */
export const DEDUP_MATCH_RULES: Array<{ value: string; label: string }> = [
  { value: 'BY_NAME_OR_CONTENT', label: '按文件名或内容' },
  { value: 'BY_NAME', label: '按文件名' },
  { value: 'BY_CONTENT', label: '按文件内容' },
  { value: 'NONE', label: '不检测' },
];

/** 去重冲突动作（DedupConflictAction；NONE 时不生效） */
export const DEDUP_ACTIONS: Array<{ value: string; label: string }> = [
  { value: 'REJECT', label: '拒绝（报错）' },
  { value: 'SKIP', label: '跳过（保留旧文档）' },
  { value: 'OVERWRITE', label: '覆盖（旧文档删除）' },
];

/** 检索策略类型（RetrievalStrategyType） */
export const RETRIEVAL_STRATEGIES: Array<{ value: string; label: string; hint: string }> = [
  { value: 'NAIVE', label: 'NAIVE', hint: '向量 + BM25 双路召回' },
  { value: 'MIX', label: 'MIX', hint: '向量 + BM25 + 知识图谱三路召回' },
];

/** 融合策略（FusionStrategyType） */
export const FUSION_TYPES: Array<{ value: string; label: string }> = [
  { value: 'RRF', label: 'RRF（倒数排名融合）' },
  { value: 'WEIGHTED_SUM', label: '加权求和' },
];

/** 召回通道（RetrievalChannel，加权时的三键） */
export const RETRIEVAL_CHANNELS: Array<{ value: string; label: string }> = [
  { value: 'VECTOR', label: '向量' },
  { value: 'BM25', label: '全文（BM25）' },
  { value: 'GRAPH', label: '图谱' },
];

/** 切片内容类型（ChunkContentType） */
export const CHUNK_CONTENT_TYPES: EnumDict = {
  TEXT: { label: '文本', theme: 'default' },
  IMAGE: { label: '图片', theme: 'primary' },
  TABLE: { label: '表格', theme: 'primary' },
  EQUATION: { label: '公式', theme: 'default' },
  GENERIC: { label: '通用', theme: 'default' },
};

/** 切片来源（ChunkSource；仅 MANUAL 可人工删除） */
export const CHUNK_SOURCES: EnumDict = {
  PARSED: { label: '解析', theme: 'default' },
  MANUAL: { label: '手工', theme: 'warning' },
};

/** 内置实体类型（BuiltinEntityType，只读展示用；不可配置，与自定义类型重名校验共用同一集合） */
export const BUILTIN_ENTITY_TYPES = [
  'PERSON（人）', 'ORGANIZATION（组织）', 'LOCATION（地点）', 'EVENT（事件）', 'CONCEPT（概念）',
  'METHOD（方法）', 'CONTENT（内容）', 'DATA（数据）', 'ARTIFACT（制品）', 'ORGANISM（生物）',
  'NATURAL_OBJECT（自然物）', 'OTHER（其他）',
];

/** 数据源状态（DatasourceStatus） */
export const DATASOURCE_STATUS: EnumDict = {
  ENABLED: { label: '已启用', theme: 'success' },
  DISABLED: { label: '已禁用', theme: 'default' },
};

/** 会话四态（AgentSessionStatus，线格式小写） */
export const SESSION_STATUS: EnumDict = {
  idle: { label: '空闲', theme: 'success' },
  running: { label: '运行中', theme: 'primary' },
  rescheduling: { label: '重调度', theme: 'warning' },
  terminated: { label: '已终止', theme: 'danger' },
};

/** 智能体列表状态过滤（active/archived） */
export const AGENT_STATUS: EnumDict = {
  active: { label: '活跃', theme: 'success' },
  archived: { label: '已归档', theme: 'default' },
};

/** 模型类型（ModelType，线格式 int：1=CHAT、2=EMBEDDING） */
export const MODEL_TYPES = [
  { value: 1, label: '对话模型' },
  { value: 2, label: '嵌入模型' },
] as const;

/** API 格式（ApiFormat，大写枚举名，大小写敏感） */
export const API_FORMATS = ['OPENAI', 'BAILIAN', 'AGENTSCOPE', 'OTHER'];

/** 模型配置状态（ModelProfileStatus） */
export const MODEL_PROFILE_STATUS: EnumDict = {
  ENABLED: { label: '已启用', theme: 'success' },
  DISABLED: { label: '已禁用', theme: 'default' },
};

/** 环境类型（config.type） */
export const ENVIRONMENT_TYPES = [
  { value: 'cloud', label: '云端托管' },
  { value: 'self_hosted', label: '自托管（暂不可装配会话）' },
];

/** 技能来源（SkillSource；catalog 本期无数据生产方） */
export const SKILL_SOURCES: EnumDict = {
  custom: { label: '自定义', theme: 'success' },
  catalog: { label: '市场', theme: 'default' },
};

/** 工具权限策略（permission_policy） */
export const TOOL_PERMISSION_POLICIES = [
  { value: 'always_allow', label: '总是允许' },
  { value: 'always_ask', label: '每次询问（人工审批）' },
  { value: 'always_deny', label: '总是拒绝' },
];

/** 内置工具全集（11 个真实工具名，AgentTool 白名单） */
export const BUILTIN_TOOLS = [
  'Bash', 'Read', 'Write', 'Edit', 'Glob', 'Grep', 'WebFetch', 'WebSearch', 'ImageGen', 'ImageSearch', 'DeliverArtifacts',
];

/** 文件用途（FilePurpose，管理端上传固定 user_upload） */
export const FILE_PURPOSES: EnumDict = {
  user_upload: { label: '用户上传', theme: 'primary' },
  tool_output: { label: '工具产物', theme: 'default' },
  skill_output: { label: '技能产物', theme: 'default' },
  session_resource: { label: '会话资源', theme: 'default' },
  agent_output: { label: '智能体产物', theme: 'default' },
};

/** 按字典取标签（未知编码原样返回） */
export function dictLabel(dict: EnumDict, code: string | null | undefined): string {
  if (!code) {
    return '-';
  }
  return dict[code]?.label ?? code;
}

/** 按字典取主题（未知返回 default） */
export function dictTheme(dict: EnumDict, code: string | null | undefined): EnumItem['theme'] {
  return (code && dict[code]?.theme) || 'default';
}
