import { describe, expect, it } from 'vitest';
import { errorMessage } from './useAsync';
import { ApiError } from '@/shared/types/api';

/** errorMessage 展示文案归一逻辑单元测试。 */
describe('errorMessage', () => {
  it('should 返回信封文案_when_ApiError 携带 message', () => {
    // given
    const error = new ApiError(409, 'conflict_error', '名称已被使用');

    // when / then
    expect(errorMessage(error)).toBe('名称已被使用');
  });

  it('should 回落状态码文案_when_ApiError 的 message 为空', () => {
    // given
    const error = new ApiError(500, '', '');

    // when / then
    expect(errorMessage(error)).toBe('请求失败(500)');
  });

  it('should 归一为中文提示_when_ApiError 包装了网络层英文错误', () => {
    // given：useAsync.run 把 fetch 的 TypeError 包装为 status=0 的 ApiError
    const error = new ApiError(0, '', 'Failed to fetch');

    // when / then
    expect(errorMessage(error)).toContain('无法连接服务');
  });

  it('should 归一为中文提示_when 直接抛出网络层 TypeError', () => {
    // given
    const error = new TypeError('NetworkError when attempting to fetch resource.');

    // when / then
    expect(errorMessage(error)).toContain('无法连接服务');
  });

  it('should 原样返回业务错误_when 非网络类 Error', () => {
    // given
    const error = new Error('解析配置失败');

    // when / then
    expect(errorMessage(error)).toBe('解析配置失败');
  });

  it('should 返回通用兜底_when 入参不是 Error', () => {
    // when / then
    expect(errorMessage('boom')).toBe('操作失败');
    expect(errorMessage(null)).toBe('操作失败');
  });
});
