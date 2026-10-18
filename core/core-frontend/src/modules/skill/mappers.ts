/**
 * 技能包域纯函数工具（前端拦截校验，避免把明显不合规的包发给后端）。
 * 仅做扩展名与体积校验；包结构（唯一顶级目录 / SKILL.md / frontmatter）由后端校验并回 400。
 */

/** 技能包体积上限（50MB，对齐后端 multipart 限制；超限后端 413）。 */
export const MAX_SKILL_ZIP_BYTES = 50 * 1024 * 1024;

/** 文件最小结构约定（File 子集，node 测试环境无需 DOM File 构造）。 */
export interface ZipFileLike {
  /** 文件名（含扩展名） */
  name: string;
  /** 字节大小 */
  size: number;
}

/** 技能包前端校验结果。 */
export interface SkillZipValidation {
  /** 是否通过 */
  ok: boolean;
  /** 未通过原因（通过时为 undefined，供 UI 直显） */
  reason?: string;
}

/**
 * 校验技能包文件：仅接受 .zip 扩展名（大小写不敏感）且 ≤50MB。
 * @param file 待校验文件（File 或结构等价对象）
 * @returns 校验结果；失败时 reason 为可直接展示的中文文案
 */
export function validateSkillZip(file: ZipFileLike): SkillZipValidation {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    return { ok: false, reason: '技能包仅支持 .zip 格式压缩包' };
  }
  if (file.size > MAX_SKILL_ZIP_BYTES) {
    return { ok: false, reason: '技能包大小超过 50MB 限制，请精简包内容后重试' };
  }
  return { ok: true };
}
