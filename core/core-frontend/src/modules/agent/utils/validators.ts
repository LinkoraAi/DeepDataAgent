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

/**
 * 判断图表配置是否具有直观价值
 * <p>避免展示无意义的图表，如数据量过少/过多、单一值、线性增长或表格类配置。</p>
 */
export function hasChartValue(chartConfig: any, chartType?: string | null): boolean {
  if (!chartConfig) return false;

  let config: any;
  if (typeof chartConfig === 'string') {
    try {
      config = JSON.parse(chartConfig);
    } catch {
      return false;
    }
  } else {
    config = chartConfig;
  }

  const rawType = (chartType || config.series?.[0]?.type || '').toLowerCase();
  if (rawType === 'table') return false;

  const dataCount = config.series?.[0]?.data?.length || 0;
  if (dataCount < 2 || dataCount > 20) return false;

  const values = (config.series?.[0]?.data || []).map((item: any) => item.value ?? item);
  if (values.length === 0) return false;

  const allSame = values.every((v: number) => v === values[0]);
  if (allSame) return false;

  if (values.length >= 3) {
    const diff1 = values[1] - values[0];
    const isLinear = values.every((v: number, i: number) => i === 0 || v === values[i - 1] + diff1);
    if (isLinear) return false;
  }

  return true;
}
