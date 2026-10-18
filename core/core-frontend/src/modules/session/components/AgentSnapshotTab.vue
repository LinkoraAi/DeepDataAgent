<script setup lang="ts">
import { computed } from 'vue';
import EmptyGuide from '@/shared/components/EmptyGuide.vue';
import { TOOL_PERMISSION_POLICIES } from '@/shared/enums/dict';
import type { SessionWire } from '../types';

/**
 * 智能体只读快照 Tab：渲染会话创建时冻结的 agent 配置（模型、描述、system、工具与权限策略、技能）。
 * <p>快照键集随版本演进，全程宽松读取（非预期形态回落占位），与该智能体后续新版本无关。
 * MUST NOT 出现环境变量 / 密钥（后端不回显）。</p>
 */

const props = defineProps<{
  /** 会话对象（agent 为内嵌冻结快照） */
  session: SessionWire | null;
}>();

/** 工具集条目 type 词汇（内置）。 */
const TOOLSET_TYPE = 'agent_toolset_20260401';

/** 智能体快照（宽松对象）。 */
const agent = computed<Record<string, unknown>>(() => {
  const raw = props.session?.agent;
  return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};
});

/** 模型 id（字符串简写或 {id} 对象兼容）。 */
const modelId = computed<string>(() => {
  const model = agent.value.model;
  if (typeof model === 'string') {
    return model;
  }
  if (model && typeof model === 'object' && 'id' in model) {
    const id = (model as Record<string, unknown>).id;
    return typeof id === 'string' ? id : '';
  }
  return '';
});

/** effort 档位（对象形态可选）。 */
const modelEffort = computed<string>(() => {
  const model = agent.value.model;
  if (model && typeof model === 'object' && 'effort' in model) {
    const effort = (model as Record<string, unknown>).effort;
    return typeof effort === 'string' ? effort : '';
  }
  return '';
});

/** 名称（快照可能缺名回落 ID）。 */
const name = computed<string>(() => {
  const raw = agent.value.name;
  const id = agent.value.id;
  if (typeof raw === 'string' && raw) {
    return raw;
  }
  return typeof id === 'string' ? id : '(未知智能体)';
});

/** 版本。 */
const version = computed<string>(() => {
  const raw = agent.value.version;
  return typeof raw === 'number' ? `v${raw}` : '-';
});

/** 描述。 */
const description = computed<string>(() => (typeof agent.value.description === 'string' ? agent.value.description : ''));

/** 系统提示词。 */
const system = computed<string>(() => (typeof agent.value.system === 'string' ? agent.value.system : ''));

/** 工具条目（宽松数组）。 */
interface ToolView {
  /** 展示名 */
  label: string;
  /** 类型词 */
  kind: string;
  /** 权限策略 */
  policy?: string;
  /** 描述 */
  desc?: string;
}

/** 解析 tools 列表：内置工具集条目展开 configs（逐工具带策略），custom 条目单列。 */
const tools = computed<ToolView[]>(() => {
  const out: ToolView[] = [];
  const entries = agent.value.tools;
  if (!Array.isArray(entries)) {
    return out;
  }
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') {
      continue;
    }
    const item = entry as Record<string, unknown>;
    const type = typeof item.type === 'string' ? item.type : '';
    if (type === TOOLSET_TYPE) {
      // 内置工具集：configs 逐工具（带 permission_policy），否则按 enabled_tools 列出
      const configs = item.configs;
      if (Array.isArray(configs) && configs.length) {
        for (const config of configs) {
          if (!config || typeof config !== 'object') {
            continue;
          }
          const c = config as Record<string, unknown>;
          out.push({
            label: typeof c.name === 'string' ? c.name : '(工具)',
            kind: '内置',
            policy: typeof c.permission_policy === 'string' ? c.permission_policy : undefined,
          });
        }
      }
      const enabled = item.enabled_tools;
      if (Array.isArray(enabled)) {
        for (const tool of enabled) {
          const label = String(tool ?? '');
          if (label && !out.some((view) => view.label === label)) {
            out.push({ label, kind: '内置' });
          }
        }
      }
    } else if (type === 'custom') {
      const label = typeof item.name === 'string' ? item.name : '(自定义工具)';
      out.push({
        label,
        kind: '自定义',
        desc: typeof item.description === 'string' ? item.description : undefined,
      });
    } else if (type) {
      out.push({ label: type, kind: type });
    }
  }
  return out;
});

/** 技能绑定（宽松）。 */
interface SkillView {
  /** 技能 ID */
  skillId: string;
  /** 版本（动态版显示"最新"） */
  version: string;
}

const skills = computed<SkillView[]>(() => {
  const out: SkillView[] = [];
  const entries = agent.value.skills;
  if (!Array.isArray(entries)) {
    return out;
  }
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') {
      continue;
    }
    const item = entry as Record<string, unknown>;
    const skillId = typeof item.skill_id === 'string' ? item.skill_id : '';
    if (!skillId) {
      continue;
    }
    const version = typeof item.version === 'string' && item.version ? item.version : '最新';
    out.push({ skillId, version });
  }
  return out;
});

/** 权限策略中文标签。 */
function policyLabel(policy?: string): string {
  if (!policy) {
    return '默认';
  }
  return TOOL_PERMISSION_POLICIES.find((item) => item.value === policy)?.label ?? policy;
}

/** 策略标签主题。 */
function policyTheme(policy?: string): 'default' | 'primary' | 'success' | 'warning' | 'danger' {
  if (policy === 'always_ask') {
    return 'warning';
  }
  if (policy === 'always_deny') {
    return 'danger';
  }
  if (policy === 'always_allow') {
    return 'success';
  }
  return 'default';
}

/** 是否有可展示的快照。 */
const hasSnapshot = computed(() => Object.keys(agent.value).length > 0);
</script>

<template>
  <div class="snapshot">
    <EmptyGuide v-if="!hasSnapshot" description="该会话未内嵌智能体快照（数据缺失）" />
    <template v-else>
      <!-- 纵向描述列表：小号加粗标签 + 值，段间细线分隔 -->
      <div class="snapshot__list">
        <div class="snapshot__field">
          <span class="snapshot__label">名称</span>
          <span class="snapshot__value">{{ name }}</span>
        </div>
        <div class="snapshot__field">
          <span class="snapshot__label">版本</span>
          <span class="snapshot__value">{{ version }}</span>
        </div>
        <div class="snapshot__field">
          <span class="snapshot__label">模型</span>
          <span class="snapshot__value snapshot__value--mono">{{ modelId || '—' }}</span>
        </div>
        <div class="snapshot__field">
          <span class="snapshot__label">推理档位</span>
          <span class="snapshot__value">{{ modelEffort || '目录缺省' }}</span>
        </div>
        <div class="snapshot__field">
          <span class="snapshot__label">描述</span>
          <span class="snapshot__value">{{ description || '—' }}</span>
        </div>
        <div class="snapshot__field">
          <span class="snapshot__label">系统提示词</span>
          <div class="snapshot__value">
            <t-collapse v-if="system" :default-expand-all="false">
              <t-collapse-panel header="展开查看 system">
                <pre class="snapshot__pre">{{ system }}</pre>
              </t-collapse-panel>
            </t-collapse>
            <p v-else class="snapshot__empty">（未配置）</p>
          </div>
        </div>
      </div>

      <!-- 工具区块：描边卡片 + 标题行（图标 / 标题 / 数量徽标）+ 工具胶囊网格 -->
      <div class="snapshot__tools">
        <div class="snapshot__tools-head">
          <span class="snapshot__tools-icon"><t-icon name="layers" /></span>
          <span class="snapshot__tools-title">内置工具</span>
          <span class="snapshot__badge">{{ tools.length }}</span>
        </div>
        <div v-if="tools.length" class="snapshot__tools-grid">
          <span v-for="tool in tools" :key="tool.label" class="snapshot__tool-pill" :title="tool.desc || tool.label">
            <span class="snapshot__tool-name">{{ tool.label }}</span>
            <span class="snapshot__tool-policy" :class="`is-${policyTheme(tool.policy)}`">{{ policyLabel(tool.policy) }}</span>
          </span>
        </div>
        <p v-else class="snapshot__empty">（未配置工具）</p>
      </div>

      <!-- 技能区块 -->
      <div class="snapshot__section">
        <div class="snapshot__section-title">技能（{{ skills.length }}）</div>
        <t-space v-if="skills.length" size="small" break-line>
          <t-tag v-for="skill in skills" :key="skill.skillId" variant="light" size="small">
            {{ skill.skillId }} · {{ skill.version }}
          </t-tag>
        </t-space>
        <p v-else class="snapshot__empty">（未绑定技能）</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.snapshot {
  height: 100%;
  padding: 16px;
  overflow-y: auto;
}

.snapshot__list {
  margin-bottom: 16px;
}

.snapshot__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--td-border-level-2-color);
}

.snapshot__field:first-child {
  padding-top: 0;
}

.snapshot__field:last-child {
  border-bottom: none;
}

.snapshot__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--td-text-color-secondary);
}

.snapshot__value {
  font-size: 13px;
  word-break: break-word;
  color: var(--td-text-color-primary);
}

.snapshot__value--mono {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.snapshot__pre {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.snapshot__empty {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-placeholder);
}

/* ---------- 工具区块 ---------- */
.snapshot__tools {
  margin-bottom: 16px;
  padding: 14px 16px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-border);
  border-radius: var(--td-radius-large);
}

.snapshot__tools-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.snapshot__tools-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
  border-radius: 8px;
}

.snapshot__tools-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.snapshot__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--td-text-color-secondary);
  background: var(--td-bg-color-component);
  border-radius: var(--td-radius-round);
}

.snapshot__tools-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.snapshot__tool-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--td-component-border);
  border-radius: var(--td-radius-round);
}

.snapshot__tool-name {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
  color: var(--td-text-color-primary);
}

.snapshot__tool-policy {
  padding: 0 8px;
  font-size: 11px;
  border-radius: var(--td-radius-round);
}

.snapshot__tool-policy.is-success {
  color: var(--td-success-color);
  background: var(--td-success-color-1);
}

.snapshot__tool-policy.is-warning {
  color: var(--td-warning-color);
  background: var(--td-warning-color-1);
}

.snapshot__tool-policy.is-danger {
  color: var(--td-error-color);
  background: var(--td-error-color-1);
}

.snapshot__tool-policy.is-default {
  color: var(--td-text-color-secondary);
  background: var(--td-bg-color-component);
}

/* ---------- 技能区块 ---------- */
.snapshot__section-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--td-text-color-secondary);
}
</style>
