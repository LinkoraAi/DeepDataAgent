/**
 * 技能资产接口模块（对齐后端 SkillController：/api/v1/cloud/skills）。
 * <p>Skill 内容以<b>文件包</b>上传（multipart 的 {@code files} 字段，可重复）：单个
 * {@code .zip}（≤50MB）或裸文件树（多个 files part，filename 携带相对路径）。
 * 不再提供 JSON 正文创建 / 更新端点，展示名（display_title）创建后不可修改。</p>
 * <p>版本为不可变快照，版本键为<b>创建时刻 epoch 微秒字符串</b>（字典序与时间序一致）；
 * 内容经版本级 zip 端点下载；被 Agent 显式钉版引用的版本不可删除（409）。</p>
 */
import {
  authHeaders,
  fetchJson,
  interceptUnauthorized,
  type CursorPageResponse,
} from '@/shared/api/http';

/** 技能来源词汇（catalog=平台目录 / custom=自建资产；本期仅产生 custom）。 */
export type SkillSourceValue = 'catalog' | 'custom';

/** 来源过滤下拉选项（含「全部」空值）。 */
export const SKILL_SOURCE_OPTIONS: { label: string; value: SkillSourceValue }[] = [
  { label: '自建技能', value: 'custom' },
  { label: '目录技能', value: 'catalog' },
];

/** 动态版绑定哨兵值：省略 version 与显式传 "latest" 等价。 */
export const SKILL_BINDING_LATEST = 'latest';

/**
 * Agent 版本的技能绑定项（{@code skills[]} 条目）。
 * <p>{@code version} 为非空 epoch 微秒字符串时为<b>钉版</b>（源发版不影响绑定）；
 * 省略或为 {@code "latest"} 时为<b>动态版</b>（沙箱准备期解析当时最新版本）。</p>
 */
export interface SkillBinding {
  type: SkillSourceValue;
  skill_id: string;
  version?: string;
}

/** 技能壳 DTO（列表 / 详情摘要；对外字段名 snake_case，与后端规格一致）。 */
export interface SkillDto {
  id: string;
  type: string;
  /** 展示名（≤255，创建后不可改） */
  display_title: string;
  source: string;
  /** 最新版本键（epoch 微秒字符串；全部版本删除后为 null） */
  latest_version: string | null;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

/** 技能版本 DTO（不可变快照元数据，不含内容全文）。 */
export interface SkillVersionDto {
  id: string;
  type: string;
  skill_id: string;
  /** 版本键（epoch 微秒字符串） */
  version: string;
  /** frontmatter name（跨版本一致） */
  name: string;
  description: string;
  /** 包顶级目录名（恒等于 name） */
  directory: string;
  created_at: string;
}

/** 技能详情 DTO（壳对象 + 全部版本信息）。 */
export interface SkillDetailDto {
  skill: SkillDto;
  versions: SkillVersionDto[];
}

/** 列表过滤与游标参数。 */
export interface ListSkillsOptions {
  source?: SkillSourceValue;
  keyword?: string;
  limit?: number;
  afterId?: string;
  beforeId?: string;
}

const SKILLS_API_BASE = '/api/v1/cloud/skills';

/**
 * 组装技能包 multipart 表单（files 字段可重复，filename 携带相对路径）。
 * <p>裸文件树依赖 filename 的相对路径重建顶级目录结构，故取
 * {@code webkitRelativePath}（目录选择）优先，回落 {@code name}。</p>
 */
function toPackageForm(files: File[], displayTitle?: string): FormData {
  const form = new FormData();
  for (const file of files) {
    const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath;
    form.append('files', file, relativePath || file.name);
  }
  if (displayTitle?.trim()) {
    form.append('display_title', displayTitle.trim());
  }
  return form;
}

/** 上传技能包创建技能（首版随建；display_title 缺省取 zip 名或 frontmatter name）。 */
export function createSkill(files: File[], displayTitle?: string): Promise<SkillDto> {
  return fetchJson<SkillDto>(SKILLS_API_BASE, {
    method: 'POST',
    body: toPackageForm(files, displayTitle),
  });
}

/** 游标分页技能列表（source / keyword 过滤，创建时间降序）。 */
export function listSkills(options?: ListSkillsOptions): Promise<CursorPageResponse<SkillDto>> {
  const query = new URLSearchParams();
  if (options?.source) {
    query.set('source', options.source);
  }
  if (options?.keyword) {
    query.set('keyword', options.keyword);
  }
  if (options?.limit != null) {
    query.set('limit', String(options.limit));
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return fetchJson<CursorPageResponse<SkillDto>>(`${SKILLS_API_BASE}${suffix}`);
}

/** 技能详情（壳对象 + 全部版本信息，不含内容全文）。 */
export function getSkill(skillId: string): Promise<SkillDetailDto> {
  return fetchJson<SkillDetailDto>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}`);
}

/** 游标分页版本历史（最新在前；游标 after_id/before_id 为版本键）。 */
export function listSkillVersions(
  skillId: string,
  options?: { limit?: number; afterId?: string; beforeId?: string },
): Promise<CursorPageResponse<SkillVersionDto>> {
  const query = new URLSearchParams();
  if (options?.limit != null) {
    query.set('limit', String(options.limit));
  }
  if (options?.afterId) {
    query.set('after_id', options.afterId);
  }
  if (options?.beforeId) {
    query.set('before_id', options.beforeId);
  }
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return fetchJson<CursorPageResponse<SkillVersionDto>>(
    `${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions${suffix}`,
  );
}

/** 上传技能包发布新版本（epoch 微秒版本；frontmatter name 须与首版一致）。 */
export function createSkillVersion(skillId: string, files: File[]): Promise<SkillVersionDto> {
  return fetchJson<SkillVersionDto>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions`, {
    method: 'POST',
    body: toPackageForm(files),
  });
}

/** 版本元数据详情。 */
export function getSkillVersion(skillId: string, version: string): Promise<SkillVersionDto> {
  return fetchJson<SkillVersionDto>(
    `${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}`,
  );
}

/** 删除特定版本（仍被 Agent 显式钉版绑定 → 409；至少保留一个版本）。 */
export async function deleteSkillVersion(skillId: string, version: string): Promise<void> {
  await fetchJson<null>(
    `${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}`,
    { method: 'DELETE' },
  );
}

/** 删除技能（逻辑删除，历史版本数据保留；仍被 Agent 版本绑定 → 409）。 */
export async function deleteSkill(skillId: string): Promise<void> {
  await fetchJson<void>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}`, { method: 'DELETE' });
}

/** 下载版本内容存档（zip：SKILL.md + 资源文件；裸 fetch 亦须携带 Bearer token）。 */
export async function downloadSkillVersion(skillId: string, version: string): Promise<void> {
  const contentUrl = `${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(version)}/content`;
  const response = await fetch(contentUrl, { method: 'GET', headers: authHeaders() });
  interceptUnauthorized(contentUrl, response.status);
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`下载失败(${response.status}): ${text.slice(0, 200)}`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${skillId}-${version}.zip`;
  anchor.click();
  URL.revokeObjectURL(url);
}