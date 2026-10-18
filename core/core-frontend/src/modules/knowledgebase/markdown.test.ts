// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { renderAnswerMarkdown } from './markdown';

/**
 * 答案 markdown 渲染器单测。
 * <p>重点不在覆盖 markdown 语法全集（那是组件库职责），而在守住两条加固口径：
 * 原始 HTML 必须转义、危险协议链接必须被拦；以及换行/表格这两个影响观感的选项生效。</p>
 */

describe('renderAnswerMarkdown 基础语法', () => {
  it('should_renderStrongAndHeading_when_renderAnswerMarkdown_given_markdownSource', () => {
    // given
    const source = '**当前地点与时间**：广东省广州市';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).toContain('<strong>当前地点与时间</strong>');
  });

  it('should_renderHeadingAndList_when_renderAnswerMarkdown_given_headingWithList', () => {
    // given
    const source = '### References\n* [1] 今天天气不好.docx';

    // when
    const html = renderAnswerMarkdown(source);

    // then（标题与无序列表各自成块，而非露出 ### 与 * 字面量）
    expect(html).toContain('<h3');
    expect(html).toContain('<ul>');
    expect(html).toContain('<li>');
  });

  it('should_renderLineBreakPerNewline_when_renderAnswerMarkdown_given_singleNewlines', () => {
    // given（答案常按单换行逐条罗列，breaks 关闭会被合并成一整段）
    const source = '第一行\n第二行';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).toContain('<br');
  });

  it('should_renderTable_when_renderAnswerMarkdown_given_gfmTable', () => {
    // given
    const source = '| 日期 | 天气 |\n| --- | --- |\n| 今天 | 多云 |';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).toContain('<table>');
    expect(html).toContain('<th>日期</th>');
  });
});

describe('renderAnswerMarkdown 安全加固', () => {
  it('should_escapeRawHtml_when_renderAnswerMarkdown_given_blockLevelScript', () => {
    // given（块级原始 HTML：marked 默认原样透传）
    const source = '<script>alert(1)</script>';

    // when
    const html = renderAnswerMarkdown(source);

    // then（转义为可见文本，标签不再可执行）
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('should_escapeRawHtml_when_renderAnswerMarkdown_given_inlineImgWithOnerror', () => {
    // given（行内原始 HTML：与块级共用同一 renderer.html，必须一并拦住）
    const source = '前文 <img src=x onerror="alert(1)"> 后文';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).not.toContain('<img src=x');
    expect(html).toContain('&lt;img src=x');
  });

  it('should_dropLink_when_renderAnswerMarkdown_given_javascriptHref', () => {
    // given（marked 的 cleanUrl 不校验协议，会渲染出可点击执行的链接）
    const source = '[点我](javascript:alert(1))';

    // when
    const html = renderAnswerMarkdown(source);

    // then（不产出 <a>，仅保留链接文字）
    expect(html).not.toContain('javascript:');
    expect(html).not.toContain('<a ');
    expect(html).toContain('点我');
  });

  it('should_dropLink_when_renderAnswerMarkdown_given_dataHref', () => {
    // given
    const source = '[点我](data:text/html;base64,PHNjcmlwdD4=)';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).not.toContain('<a ');
  });

  it('should_keepLink_when_renderAnswerMarkdown_given_httpsHref', () => {
    // given
    const source = '[官网](https://example.com/docs)';

    // when
    const html = renderAnswerMarkdown(source);

    // then（白名单内协议正常渲染，且属性值经转义）
    expect(html).toContain('<a href="https://example.com/docs"');
  });

  it('should_keepLink_when_renderAnswerMarkdown_given_relativeHref', () => {
    // given
    const source = '[切片](./chunks/1)';

    // when
    const html = renderAnswerMarkdown(source);

    // then（相对路径无协议，放行）
    expect(html).toContain('<a href="./chunks/1"');
  });

  it('should_keepImage_when_renderAnswerMarkdown_given_httpsImage', () => {
    // given
    const source = '![示意图](https://example.com/a.png)';

    // when
    const html = renderAnswerMarkdown(source);

    // then
    expect(html).toContain('<img src="https://example.com/a.png"');
  });

  it('should_dropImage_when_renderAnswerMarkdown_given_dataImage', () => {
    // given
    const source = '![示意图](data:image/svg+xml;base64,PHN2Zz4=)';

    // when
    const html = renderAnswerMarkdown(source);

    // then（降级为替代文本，不产出 <img>）
    expect(html).not.toContain('<img');
    expect(html).toContain('示意图');
  });
});

describe('renderAnswerMarkdown 边界输入', () => {
  it('should_returnEmpty_when_renderAnswerMarkdown_given_blankSource', () => {
    // given / when / then（调用方无需额外判空）
    expect(renderAnswerMarkdown('')).toBe('');
    expect(renderAnswerMarkdown('   ')).toBe('');
    expect(renderAnswerMarkdown(null)).toBe('');
    expect(renderAnswerMarkdown(undefined)).toBe('');
  });

  it('should_renderPlainText_when_renderAnswerMarkdown_given_textWithoutSyntax', () => {
    // given
    const source = '本次未能生成答案（模型降级）';

    // when
    const html = renderAnswerMarkdown(source);

    // then（无语法时仅产出段落包裹，文本原样保留）
    expect(html).toContain('<p>本次未能生成答案（模型降级）</p>');
  });
});
