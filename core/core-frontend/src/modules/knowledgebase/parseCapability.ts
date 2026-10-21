/**
 * 解析引擎能力提示（纯展示层）。
 * <p>各引擎可解析的文件类型清单，与后端解析器的声明集合对齐，仅用于知识库配置界面的提示文案；
 * 上传准入与解析路由以后端能力集合为准。</p>
 */

/** 各解析引擎声明的支持类型（数组顺序即界面展示顺序）。 */
export const PARSE_PROVIDER_FILE_TYPES: Record<string, string[]> = {
  LOCAL: ['PDF', 'DOC', 'DOCX', 'XLS', 'XLSX', 'PPT', 'PPTX', 'TXT', 'MD', 'CSV'],
  MINERU: ['PDF', 'DOC', 'DOCX', 'XLS', 'XLSX', 'PPT', 'PPTX', 'PNG', 'JPG', 'JPEG'],
};

/** 各引擎对「未在引擎声明集内、但可兜底处理」的类型的说明（仅 LOCAL 有兜底）。 */
const PARSE_PROVIDER_FALLBACK_HINTS: Record<string, string> = {
  LOCAL: '图片（PNG/JPG/JPEG）以视觉直通处理',
};

/**
 * 生成解析引擎支持类型的提示文案。
 * @param provider 解析引擎提供方（LOCAL / MINERU）
 * @returns 可直接展示的提示文案；未知提供方返回空串（界面不渲染提示）
 */
export function describeParseProviderTypes(provider: string): string {
  const types = PARSE_PROVIDER_FILE_TYPES[provider];
  if (types === undefined) {
    return '';
  }
  const base = `支持格式：${types.join('、')}`;
  const fallback = PARSE_PROVIDER_FALLBACK_HINTS[provider];
  return fallback === undefined ? base : `${base}；${fallback}`;
}