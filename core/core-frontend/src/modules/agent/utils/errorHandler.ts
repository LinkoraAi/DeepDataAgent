/**
 * Error classification and handling utilities
 */

export type ErrorType =
  | 'validation'
  | 'connection'
  | 'timeout'
  | 'tool_execution'
  | 'model_error'
  | 'unknown';

export interface AppError {
  type: ErrorType;
  message: string;
  suggestion?: string;
  retryable: boolean;
}

/**
 * Classify error based on error message
 */
export function classifyError(raw: string): AppError {
  if (!raw) {
    return {
      type: 'unknown',
      message: '发生未知错误',
      suggestion: '请稍后重试或联系管理员',
      retryable: true,
    };
  }

  const lowerMsg = raw.toLowerCase();

  // Timeout errors
  if (lowerMsg.includes('timeout') || lowerMsg.includes('超时')) {
    return {
      type: 'timeout',
      message: raw,
      suggestion: '查询超时，请缩小查询范围或简化问题',
      retryable: true,
    };
  }

  // Connection errors
  if (
    lowerMsg.includes('connection') ||
    lowerMsg.includes('连接') ||
    lowerMsg.includes('network') ||
    lowerMsg.includes('网络')
  ) {
    return {
      type: 'connection',
      message: raw,
      suggestion: '连接失败，请检查网络后重试',
      retryable: true,
    };
  }

  // SQL/tool execution errors
  if (
    lowerMsg.includes('sql') ||
    lowerMsg.includes('执行') ||
    lowerMsg.includes('syntax') ||
    lowerMsg.includes('语法')
  ) {
    return {
      type: 'tool_execution',
      message: raw,
      suggestion: 'SQL 执行失败，请尝试换一种方式描述问题',
      retryable: true,
    };
  }

  // Model errors
  if (
    lowerMsg.includes('model') ||
    lowerMsg.includes('模型') ||
    lowerMsg.includes('api') ||
    lowerMsg.includes('quota')
  ) {
    return {
      type: 'model_error',
      message: raw,
      suggestion: '模型调用失败，请检查模型配置或稍后重试',
      retryable: true,
    };
  }

  // Validation errors
  if (
    lowerMsg.includes('validation') ||
    lowerMsg.includes('验证') ||
    lowerMsg.includes('invalid') ||
    lowerMsg.includes('无效')
  ) {
    return {
      type: 'validation',
      message: raw,
      suggestion: '输入验证失败，请检查输入内容',
      retryable: false,
    };
  }

  // Default unknown error
  return {
    type: 'unknown',
    message: raw,
    suggestion: '请稍后重试或联系管理员',
    retryable: true,
  };
}

/**
 * Get error icon based on type
 */
export function getErrorIcon(type: ErrorType): string {
  switch (type) {
    case 'validation':
      return 'error-circle';
    case 'connection':
      return 'wifi';
    case 'timeout':
      return 'time';
    case 'tool_execution':
      return 'code';
    case 'model_error':
      return 'robot';
    default:
      return 'error';
  }
}
