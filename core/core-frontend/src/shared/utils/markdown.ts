import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import DOMPurify from 'dompurify';

/**
 * 创建 Markdown 渲染器实例
 * <p>启用 GFM（GitHub Flavored Markdown）支持，包括表格、删除线、任务列表等。</p>
 * <p>关闭 typographer 选项，避免字符替换干扰表格解析。</p>
 */
const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,  // 关闭排版替换，避免干扰表格等 markdown 结构解析
  breaks: true,        // 支持换行符（GFM 规范）
  highlight: function (str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
        }</code></pre>`;
      } catch (__) {}
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`;
  },
});

// 确保 GFM 表格、删除线等规则启用（markdown-it v14 默认启用，这里显式设置以防被覆盖）
md.enable('table');
md.enable('strikethrough');

/**
 * 规范化 Markdown 文本
 * <p>处理后端发送的转义字符，将字面的 \n、\t、\\ 替换为实际字符。</p>
 * <p>markdown-it 需要真正的换行符才能正确解析块级结构（标题、表格、列表等）。</p>
 * <p>作为安全兜底，剥离可能存在的外层引号包裹。</p>
 *
 * @param text 原始 Markdown 文本
 * @returns 规范化后的文本
 */
function normalizeMarkdown(text: string): string {
  if (!text) return '';
  
  // 安全兜底：剥离外层引号包裹
  // 例如：'"## 分析报告"' -> '## 分析报告'
  let result = text.trim();
  if (result.length >= 2 && result.startsWith('"') && result.endsWith('"')) {
    result = result.slice(1, -1);
  } else if (result.length >= 2 && result.startsWith("'") && result.endsWith("'")) {
    result = result.slice(1, -1);
  }
  
  // 将字面的 \n 替换为真正的换行符
  return result
    .replace(/\\r\\n/g, '\n')    // Windows 换行符
    .replace(/\\n/g, '\n')       // Unix 换行符
    .replace(/\\t/g, '\t')       // 制表符
    .replace(/\\"/g, '"')        // 转义双引号
    .replace(/\\'/g, "'");       // 转义单引号
}

/**
 * 渲染 Markdown 文本为 HTML
 * <p>在渲染前先规范化文本，处理后端发送的转义字符。</p>
 *
 * @param text Markdown 文本
 * @returns 渲染后的 HTML
 */
export function renderMarkdown(text: string): string {
  const normalized = normalizeMarkdown(text);
  return DOMPurify.sanitize(md.render(normalized));
}

export default md;
