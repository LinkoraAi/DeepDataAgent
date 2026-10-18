import { Marked } from 'marked';

/**
 * 模型答案的 markdown 渲染（知识问答专用）。
 * <p>模型答案含 markdown 语法（粗体、标题、列表、表格），需渲染为 HTML 后交由气泡展示。
 * 渲染器在组件库默认行为之上做两处安全加固，以封堵「上传恶意文档 → 切片入库 → 检索命中 → 答案回显」
 * 这条注入链路（该链路是本次引入 HTML 渲染时新开的风险面）：</p>
 * <ul>
 *   <li><b>原始 HTML 转义</b>：marked 默认把块级与行内 HTML 原样透传（{@code renderer.html} 直接返回入参），
 *       是 {@code <img onerror>} 这类自动执行注入的入口；此处置为可见文本，标签不再被浏览器执行。</li>
 *   <li><b>链接/图片协议白名单</b>：marked 的 {@code cleanUrl} 只做 URI 编码、不校验协议，
 *       故 {@code [x](javascript:...)} 会生成可点击执行的链接；仅放行 http/https/mailto 与相对路径。</li>
 * </ul>
 * <p>选项口径：{@code breaks: true} 保持与既有 {@code white-space: pre-wrap} 一致的换行观感
 * （答案常按单换行逐条罗列，关闭后会被合并为一整段）；{@code gfm: true} 支持答案中的表格。</p>
 */

/** 允许的链接协议白名单；无协议写法（相对路径、锚点、协议相对）不在此判定范围内，直接放行。 */
const SAFE_URL_PROTOCOLS = ['http:', 'https:', 'mailto:'];

/** 协议判定前需剥离的噪声字符：控制字符与空白，用于阻断 {@code java\tscript:} 之类的缩略绕过。 */
const URL_NOISE_PATTERN = /[\u0000-\u0020\u007f]/g;

/** URL 协议前缀形态（RFC 3986 scheme 部分）。 */
const URL_SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:/;

/** HTML 文本与属性的转义映射表（属性值必须转义引号，避免撑破标签）。 */
const HTML_ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** HTML 转义：同时适用于文本节点与属性值（属性值转义引号后，实体写法也无法二次解码绕过协议判定）。 */
function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => HTML_ESCAPE_MAP[character] ?? character);
}

/**
 * 链接/图片地址协议校验。
 * @param rawHref 原始地址（markdown 语法中的 href）
 * @returns 可安全输出的地址原文；协议不在白名单时返回 null（调用方须降级为不含链接的形态）
 */
function sanitizeUrl(rawHref: string): string | null {
  const href = rawHref.trim();
  if (href === '') {
    return null;
  }
  const normalized = href.replace(URL_NOISE_PATTERN, '').toLowerCase();
  if (!URL_SCHEME_PATTERN.test(normalized)) {
    return href;
  }
  return SAFE_URL_PROTOCOLS.some((protocol) => normalized.startsWith(protocol)) ? href : null;
}

/** 解析器单例（marked 解析器无状态、可复用；惰性创建避免模块加载即付出解析器构建开销）。 */
let markdownParser: Marked | null = null;

/** 构建带安全加固的 markdown 解析器（首次渲染时调用一次）。 */
function createParser(): Marked {
  return new Marked({
    gfm: true,
    breaks: true,
    renderer: {
      // 原始 HTML（块级与行内共用本方法）→ 转义为可见文本，杜绝标签被执行
      html(html: string): string {
        return escapeHtml(html);
      },
      // 危险协议不产出 <a>，仅保留链接文字，避免可点击的脚本地址
      link(href: string, title: string | null | undefined, text: string): string {
        const safeHref = sanitizeUrl(href);
        if (safeHref === null) {
          return text;
        }
        const titleAttribute = title ? ` title="${escapeHtml(title)}"` : '';
        return `<a href="${escapeHtml(safeHref)}"${titleAttribute}>${text}</a>`;
      },
      // 危险协议降级为替代文本，不产出 <img>
      image(href: string, title: string | null, text: string): string {
        const safeHref = sanitizeUrl(href);
        if (safeHref === null) {
          return escapeHtml(text);
        }
        const titleAttribute = title ? ` title="${escapeHtml(title)}"` : '';
        return `<img src="${escapeHtml(safeHref)}" alt="${escapeHtml(text)}"${titleAttribute}>`;
      },
    },
  });
}

/**
 * 将模型答案的 markdown 文本渲染为 HTML。
 * <p>空白输入返回空串（调用方无需额外判空）；解析异常不外抛——由调用方按其展示策略兜底。</p>
 * @param source 模型答案原文（可为 null / 空串）
 * @returns 可直接绑定 v-html 的 HTML 字符串
 */
export function renderAnswerMarkdown(source: string | null | undefined): string {
  if (!source || source.trim() === '') {
    return '';
  }
  markdownParser ??= createParser();
  const html = markdownParser.parse(source, { async: false });
  return typeof html === 'string' ? html : '';
}
