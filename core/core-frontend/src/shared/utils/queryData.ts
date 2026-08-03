/**
 * 查询数据解析工具
 * <p>统一处理 execute_sql / execute_api_query 等查询类工具结果文本的解析，
 * 兼容 AgentScope 对工具返回值做 JSON 序列化产生的二次编码格式。</p>
 */

/**
 * 从文本中提取 JSON 数组片段
 * <p>优先整体解析；若整体不是纯 JSON，则截取首个 '[' 到最后一个 ']' 之间的内容。</p>
 *
 * @param text 工具结果文本
 * @returns 提取到的 JSON 数组文本；无法提取时返回 null
 */
function extractJsonArray(text: string): string | null {
  if (!text) return null;
  try {
    JSON.parse(text);
    return text;
  } catch {
    // Not pure JSON
  }
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start !== -1 && end > start) {
    return text.substring(start, end + 1);
  }
  return null;
}

/**
 * 从查询类工具结果文本中解析数据数组
 * <p>兼容以下三种格式：</p>
 * <ul>
 *   <li>纯 JSON 数组：<code>[{...}]</code></li>
 *   <li>带前缀文本：<code>查询返回 N 行数据：\n[{...}]</code></li>
 *   <li>二次序列化（外层为 JSON 字符串）：<code>"查询返回 N 行数据：\\n[{...}]"</code></li>
 * </ul>
 * <p>无法解析（空文本、非数组 JSON、无数组内容）时返回 null，调用方应忽略。</p>
 *
 * @param text 工具结果文本
 * @returns 解析出的数据数组；无法解析时返回 null
 */
export function parseQueryDataArray(text: string): Record<string, any>[] | null {
  if (!text) return null;
  const jsonData = extractJsonArray(text);
  if (!jsonData) {
    return null;
  }
  try {
    const parsed = JSON.parse(jsonData);
    if (typeof parsed === 'string') {
      // 二次序列化：外层是 JSON 字符串，内层可能带 "查询返回 N 行数据：" 前缀
      const innerJson = extractJsonArray(parsed);
      if (!innerJson) {
        return null;
      }
      const reparsed = JSON.parse(innerJson);
      return Array.isArray(reparsed) ? reparsed : null;
    }
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}
