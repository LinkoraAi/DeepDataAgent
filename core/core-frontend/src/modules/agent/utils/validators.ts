/**
 * Input validation utilities
 */

export const MAX_QUESTION_LENGTH = 2000;
export const MIN_QUESTION_LENGTH = 1;

export interface ValidationResult {
  valid: boolean;
  message?: string;
}

/**
 * Validate user question
 */
export function validateQuestion(question: string): ValidationResult {
  if (!question || !question.trim()) {
    return { valid: false, message: '请输入问题' };
  }
  if (question.length > MAX_QUESTION_LENGTH) {
    return { valid: false, message: `问题长度不能超过 ${MAX_QUESTION_LENGTH} 字` };
  }
  return { valid: true };
}

/**
 * Validate datasource selection
 */
export function validateDatasource(id: number | null): ValidationResult {
  if (!id) {
    return { valid: false, message: '请选择数据源' };
  }
  return { valid: true };
}

/**
 * Validate model selection
 */
export function validateModel(id: number | null): ValidationResult {
  if (!id) {
    return { valid: false, message: '请选择模型' };
  }
  return { valid: true };
}

/**
 * Validate all required fields before analysis
 */
export function validateAnalysisInput(
  question: string,
  datasourceId: number | null,
  modelId: number | null
): ValidationResult {
  // Validate datasource first
  const datasourceValidation = validateDatasource(datasourceId);
  if (!datasourceValidation.valid) {
    return datasourceValidation;
  }

  // Validate model
  const modelValidation = validateModel(modelId);
  if (!modelValidation.valid) {
    return modelValidation;
  }

  // Validate question
  const questionValidation = validateQuestion(question);
  if (!questionValidation.valid) {
    return questionValidation;
  }

  return { valid: true };
}
