import { describe, it, expect } from 'vitest';
import { parseQueryDataArray } from '../queryData';

/**
 * 查询数据解析工具测试
 * <p>覆盖 execute_sql / execute_api_query 工具结果文本的三种格式：
 * 纯 JSON 数组、带 "查询返回 N 行数据：" 前缀的文本、
 * 经 AgentScope 二次序列化（外层为 JSON 字符串）的格式，
 * 以及无法解析时应返回 null 的边界场景。</p>
 */
describe('parseQueryDataArray', () => {
  it('should_returnArray_when_parseQueryDataArray_given_pureJsonArray', () => {
    // given
    const text = '[{"name":"张三","age":30},{"name":"李四","age":25}]';

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toEqual([
      { name: '张三', age: 30 },
      { name: '李四', age: 25 },
    ]);
  });

  it('should_returnArray_when_parseQueryDataArray_given_prefixedText', () => {
    // given
    const text = '查询返回 2 行数据：\n[{"name":"张三","age":30},{"name":"李四","age":25}]';

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toEqual([
      { name: '张三', age: 30 },
      { name: '李四', age: 25 },
    ]);
  });

  it('should_returnArray_when_parseQueryDataArray_given_doubleEncodedPrefixedText', () => {
    // given：AgentScope 对工具返回值做 JSON 序列化，落库内容为二次编码的 JSON 字符串
    const text = JSON.stringify('查询返回 2 行数据：\n[{"name":"张三","age":30},{"name":"李四","age":25}]');

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toEqual([
      { name: '张三', age: 30 },
      { name: '李四', age: 25 },
    ]);
  });

  it('should_returnArray_when_parseQueryDataArray_given_doubleEncodedPureJsonArray', () => {
    // given：二次序列化但内层无前缀，仅为 JSON 数组
    const text = JSON.stringify('[{"id":1},{"id":2}]');

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toEqual([{ id: 1 }, { id: 2 }]);
  });

  it('should_returnArray_when_parseQueryDataArray_given_apiPrefixedText', () => {
    // given：API 数据源工具返回的带前缀文本
    const text = 'API 返回 1 行数据：\n[{"code":"200"}]';

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toEqual([{ code: '200' }]);
  });

  it('should_returnNull_when_parseQueryDataArray_given_doubleEncodedTextWithoutArray', () => {
    // given：二次序列化的纯文本（如 "查询结果为空。可能原因：1)..."），内层无 JSON 数组
    const text = JSON.stringify('查询结果为空。可能原因：1) 查询条件过于严格 2) 数据表中确实没有匹配数据');

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toBeNull();
  });

  it('should_returnNull_when_parseQueryDataArray_given_plainTextWithoutArray', () => {
    // given：普通中文文本，不含 JSON 数组
    const text = 'SQL 执行失败: 数据源不存在';

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toBeNull();
  });

  it('should_returnNull_when_parseQueryDataArray_given_nonArrayJson', () => {
    // given：JSON 对象而非数组
    const text = '{"total": 10}';

    // when
    const result = parseQueryDataArray(text);

    // then
    expect(result).toBeNull();
  });

  it('should_returnNull_when_parseQueryDataArray_given_emptyOrNullText', () => {
    // when：空文本与 null
    const emptyResult = parseQueryDataArray('');
    const nullResult = parseQueryDataArray(null as unknown as string);

    // then
    expect(emptyResult).toBeNull();
    expect(nullResult).toBeNull();
  });
});
