/**
 * 技能资源管理接口模块（对齐后端 SkillController：/api/v1/agent/skills）。
 * <p>上传 / 发布版本采用 multipart/form-data：{@code file} 为技能包二进制（zip），
 * {@code meta} 为 JSON 元数据部分；内容下载为八位字节流（无统一响应包装）。</p>
 */
import { fetchJson, type PaginatedResponse } from '@/shared/api/http';

/** 技能类型（1=自定义 2=官方预留）。 */
export const SKILL_TYPES = [1, 2] as const;

/** 技能资源 DTO（不返回二进制内容）。 */
export interface SkillDto {
  skillId: string;
  versionNumber: number;
  name: string;
  description: string | null;
  skillType: number | null;
  storageType: string;
  contentSha256: string;
  contentSize: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

/** 技能详情 DTO（元数据 + 全部版本）。 */
export interface SkillDetailDto {
  skillId: string;
  versions: SkillDto[];
}

/** 技能元数据（multipart 的 meta 字段）。 */
export interface SkillMetaPayload {
  name: string;
  description?: string | null;
  skillType?: number | null;
  /** 客户端声明的 SHA-256 校验值（可选）。 */
  sha256?: string;
}

const SKILLS_API_BASE = '/api/v1/agent/skills';

/** 上传技能（首传生成 v1）。 */
export function createSkill(file: File, meta: SkillMetaPayload): Promise<SkillDto> {
  const form = new FormData();
  form.append('file', file);
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
  return fetchJson<SkillDto>(SKILLS_API_BASE, { method: 'POST', body: form });
}

/** 发布技能新版本（MAX+1）。 */
export function publishSkillVersion(skillId: string, file: File, meta: SkillMetaPayload): Promise<SkillDto> {
  const form = new FormData();
  form.append('file', file);
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
  return fetchJson<SkillDto>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions`, {
    method: 'POST',
    body: form,
  });
}

/** 分页查询技能列表（每技能仅返回最新版本）。 */
export function listSkills(options?: {
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<PaginatedResponse<SkillDto>> {
  const query = new URLSearchParams({
    page: String(options?.page ?? 1),
    // 后端接口参数名为 limit（SkillController.list），对齐不传 size
    limit: String(options?.size ?? 20),
  });
  if (options?.keyword) {
    query.set('keyword', options.keyword);
  }
  return fetchJson<PaginatedResponse<SkillDto>>(`${SKILLS_API_BASE}?${query}`);
}

/** 技能详情（全部版本）。 */
export function getSkill(skillId: string): Promise<SkillDetailDto> {
  return fetchJson<SkillDetailDto>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}`);
}

/** 下载指定版本内容（zip 八位字节流）。 */
export async function downloadSkillContent(skillId: string, version: number): Promise<void> {
  const response = await fetch(
    `${SKILLS_API_BASE}/${encodeURIComponent(skillId)}/versions/${version}/content`,
    { method: 'GET' },
  );
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

/** 删除技能（级联删除全部版本与内容）。 */
export async function deleteSkill(skillId: string): Promise<void> {
  await fetchJson<void>(`${SKILLS_API_BASE}/${encodeURIComponent(skillId)}`, { method: 'DELETE' });
}