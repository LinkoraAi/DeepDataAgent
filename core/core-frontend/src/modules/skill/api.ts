import { get, del, buildQuery } from '@/shared/api/http';
import { uploadSkill } from '@/shared/api/upload';
import { downloadFile } from '@/shared/api/blob';
import type { CursorPage } from '@/shared/types/api';

/**
 * 技能包端点（后端 SkillController，前缀 /api/v1/cloud/skills，云资源域 snake_case）。
 * 契约要点（与后端代码逐字段核对）：
 * - 列表参数：source（catalog/custom，非法 400）/ keyword / limit / after_id / before_id；
 * - 创建与发版均为 multipart（part 名 files，MVP 单 zip）；创建成功 HTTP 201；
 * - 详情返回 {skill, versions} 组合（壳对象 + 全部版本，不含内容全文）；
 * - 版本内容 GET /{id}/versions/{versionNo}/content 为 zip 八位字节流下载；
 * - 删除版本 200（ApiResponse<Void>）；删除技能 204 无 body（http 层空体安全返回）；
 * - 删除被智能体钉版引用 → 409 conflict_error（调用方直显后端 message）。
 */

/** API 前缀（版本化云资源路径）。 */
const BASE = '/api/v1/cloud/skills';

/** 技能来源（catalog=市场本期无数据；custom=自定义上传）。 */
export type SkillSourceWire = 'catalog' | 'custom';

/** 技能壳对象（后端 SkillResponse 序列化形态；latest_version 为 epoch 微秒字符串，全部版本删除后 null）。 */
export interface SkillWire {
  /** 技能业务 ID（skill_ 前缀） */
  id: string;
  /** 对象类型（恒为 skill） */
  type: 'skill';
  /** 展示名（≤255，创建后不可修改） */
  display_title: string;
  /** 技能来源 */
  source: SkillSourceWire | string;
  /** 最新版本键（epoch 微秒字符串；无版本时 null） */
  latest_version: string | null;
  /** 业务自定义元数据（默认空对象） */
  metadata: Record<string, unknown>;
  /** 创建时间（ISO 字符串） */
  created_at: string;
  /** 更新时间（ISO 字符串） */
  updated_at: string;
}

/** 技能内容版本（后端 SkillVersionResponse 序列化形态；不可变快照，不暴露内容全文）。 */
export interface SkillVersionWire {
  /** 版本业务 ID（skillver_ 前缀） */
  id: string;
  /** 对象类型（恒为 skill_version） */
  type: 'skill_version';
  /** 所属技能业务 ID */
  skill_id: string;
  /** 版本键（创建时刻 epoch 微秒字符串） */
  version: string;
  /** frontmatter name（跨版本一致，发版 400 校验锚点） */
  name: string;
  /** frontmatter description */
  description: string;
  /** 包顶级目录名（恒等于 name） */
  directory: string;
  /** 版本创建时间（ISO 字符串） */
  created_at: string;
}

/** 技能详情响应（后端 SkillDetailResponse：壳 + 全部版本，版本键倒序）。 */
export interface SkillDetailWire {
  /** 技能壳 */
  skill: SkillWire;
  /** 内容版本列表（最新在前） */
  versions: SkillVersionWire[];
}

/** 列表查询参数（后端 @RequestParam 真实参数名：source/keyword/limit/after_id）。 */
export interface ListSkillsParams {
  /** 来源过滤（缺省 custom；市场本期无数据） */
  source?: SkillSourceWire;
  /** 展示名模糊关键字 */
  keyword?: string;
  /** 每页条数 */
  limit?: number;
  /** 游标：本页末条资源 ID */
  after_id?: string;
}

/** 版本列表查询参数（游标为版本键）。 */
export interface ListVersionsParams {
  /** 每页条数 */
  limit?: number;
  /** 游标：本页末条版本 ID */
  after_id?: string;
}

/** 游标分页列出技能（缺省 source=custom）。 */
export function listSkills(params: ListSkillsParams = {}): Promise<CursorPage<SkillWire>> {
  const query = buildQuery({
    source: params.source ?? 'custom',
    keyword: params.keyword,
    limit: params.limit,
    after_id: params.after_id,
  });
  return get<CursorPage<SkillWire>>(`${BASE}${query}`);
}

/** 技能详情（壳对象 + 全部版本信息，不含内容全文）。 */
export function getSkillDetail(id: string): Promise<SkillDetailWire> {
  return get<SkillDetailWire>(`${BASE}/${encodeURIComponent(id)}`);
}

/** 游标分页列出版本（最新在前）。 */
export function listVersions(id: string, params: ListVersionsParams = {}): Promise<CursorPage<SkillVersionWire>> {
  const query = buildQuery({ limit: params.limit, after_id: params.after_id });
  return get<CursorPage<SkillVersionWire>>(`${BASE}/${encodeURIComponent(id)}/versions${query}`);
}

/**
 * 上传技能包创建技能（multipart part 名 files；display_title 可选，留空由后端取包名）。
 * <p>包结构违规 400、体积超限 413（调用方保留对话框直显 message）。</p>
 */
export function createSkill(zip: File, displayTitle?: string): Promise<SkillWire> {
  return uploadSkill<SkillWire>(BASE, zip, displayTitle);
}

/** 为既有技能上传技能包发版（新的不可变 epoch 版本；frontmatter name 必须与首版一致，否则 400）。 */
export function createVersion(id: string, zip: File): Promise<SkillVersionWire> {
  return uploadSkill<SkillVersionWire>(`${BASE}/${encodeURIComponent(id)}/versions`, zip);
}

/** 删除特定版本（被智能体显式钉版引用 → 409；头版本删除后 latest_version 重算）。 */
export function deleteVersion(id: string, versionNo: string): Promise<void> {
  return del<void>(`${BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionNo)}`);
}

/** 删除技能（逻辑删除，历史版本保留；仍被未删除智能体版本绑定 → 409；成功 204 空体）。 */
export function deleteSkill(id: string): Promise<void> {
  return del<void>(`${BASE}/${encodeURIComponent(id)}`);
}

/** 下载版本内容 zip 归档（文件名 {id}-{version}.zip，与服务端 Content-Disposition 一致）。 */
export function downloadVersion(id: string, versionNo: string): Promise<void> {
  const path = `${BASE}/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionNo)}/content`;
  return downloadFile(path, `${id}-${versionNo}.zip`);
}
