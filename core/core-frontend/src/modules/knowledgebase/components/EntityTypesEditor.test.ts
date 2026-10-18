// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import TDesign, { Button, TagInput } from 'tdesign-vue-next';
import EntityTypesEditor from './EntityTypesEditor.vue';
import { BUILTIN_ENTITY_TYPES } from '@/shared/enums/dict';
import { MAX_ENTITY_TYPES } from '../kbDraft';

/**
 * 自定义实体类型编辑器组件测试。
 * <p>核心回归点：达到上限时已添加标签的删除入口 MUST 仍可用（历史实现用 t-tag-input 的整体
 * disabled 连带隐藏了关闭图标，导致既不能加也不能删的死局）。</p>
 */

beforeAll(() => {
  // jsdom 未实现 ResizeObserver，TDesign 组件挂载时可能探测该 API，此处做最小兜底
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
  // jsdom 未实现 scrollIntoView，TDesign Input 聚焦时可能调用，避免抛错中断聚焦
  HTMLElement.prototype.scrollIntoView = () => {};
});

/** 已挂载的包装器：每个用例结束后统一卸载，避免 DOM 残留。 */
const mountedWrappers: ReturnType<typeof mount>[] = [];

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = '';
});

/**
 * 挂载编辑器（自定义类型列表 + 可选字段错误）。
 * <p>attachTo body：焦点断言依赖元素真实进入文档（游离节点调用 focus 不会改变 activeElement）。</p>
 */
function mountEditor(modelValue: string[], error?: string): ReturnType<typeof mount> {
  const wrapper = mount(EntityTypesEditor, {
    props: { modelValue, error },
    global: { plugins: [TDesign] },
    attachTo: document.body,
  });
  mountedWrappers.push(wrapper);
  return wrapper;
}

/** 造 n 条自定义类型（覆盖上限场景）。 */
function names(count: number): string[] {
  return Array.from({ length: count }, (_, i) => `type_${i}`);
}

/** 标签胶囊根类（TDesign Tag 根类即为组件名前缀名）。 */
const CHIP = '.t-tag';
/** 删除入口图标：仅 closable 时渲染，是"删除入口是否可用"的渲染级证据。 */
const CLOSE_ICON = '.t-tag__icon-close';

/** 可编辑行容器（自定义类型）。 */
function customRow(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.kb-entity__custom');
}

/** 只读行容器（内置类型）。 */
function builtinRow(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.kb-entity__builtin');
}

/** 点击「＋」进入新增态并取回输入框元素。 */
async function openInput(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('.kb-entity__add').trigger('click');
  await flushPromises();
  return wrapper.find('.kb-entity__input input');
}

describe('EntityTypesEditor', () => {
  describe('已添加项的展示与删除', () => {
    it('should_renderChipPerItem_when_render_given_twoCustomTypes', () => {
      // given
      const wrapper = mountEditor(['person', 'geo']);

      // when
      const chips = customRow(wrapper).findAll(CHIP);

      // then（每项一个可关闭标签胶囊）
      expect(chips).toHaveLength(2);
      expect(chips[0].text()).toBe('person');
      expect(chips[1].text()).toBe('geo');
    });

    it('should_keepChipsClosable_when_render_given_maxedList', () => {
      // given（上限态：历史实现此处会让关闭图标整体消失）
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES));

      // when
      const row = customRow(wrapper);

      // then（胶囊与删除入口数量一致 = 每项删除入口恒可用，这是本次修复的核心不变量）
      expect(row.findAll(CHIP)).toHaveLength(MAX_ENTITY_TYPES);
      expect(row.findAll(CLOSE_ICON)).toHaveLength(MAX_ENTITY_TYPES);
    });

    it('should_emitShrunkList_when_closeChip_given_maxedList', async () => {
      // given
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES));

      // when（点击第一项的删除图标，验证上限态下删除确实能生效）
      await customRow(wrapper).findAll(CLOSE_ICON)[0].trigger('click');
      await flushPromises();

      // then
      const emitted = wrapper.emitted('update:modelValue');
      expect(emitted).toHaveLength(1);
      expect(emitted?.[0][0]).toEqual(names(MAX_ENTITY_TYPES).slice(1));
    });

    it('should_showUsedCount_when_render_given_partialList', () => {
      // given / when
      const wrapper = mountEditor(names(3));

      // then
      expect(wrapper.text()).toContain(`最多 ${MAX_ENTITY_TYPES} 个，已用 3`);
    });
  });

  describe('新增入口（＋ 原位展开输入框）', () => {
    it('should_showFocusedInput_when_clickAdd_given_belowLimit', async () => {
      // given
      const wrapper = mountEditor(['person']);

      // when
      const input = await openInput(wrapper);

      // then
      expect(input.exists()).toBe(true);
      expect(document.activeElement).toBe(input.element);
    });

    it('should_disableAddOnly_when_render_given_maxedList', () => {
      // given / when
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES));

      // then（仅「＋」被禁用，编辑器与标签不受影响）
      expect(wrapper.findComponent(Button).props('disabled')).toBe(true);
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
    });

    it('should_ignoreClick_when_clickAdd_given_maxedList', async () => {
      // given / when（禁用态点击不应进入新增态）
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES));
      await wrapper.find('.kb-entity__add').trigger('click');
      await flushPromises();

      // then
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
      expect(wrapper.emitted('update:modelValue')).toBeUndefined();
    });

    it('should_emitAppendedList_when_enter_given_validDraft', async () => {
      // given
      const wrapper = mountEditor(['person']);
      const input = await openInput(wrapper);

      // when
      await input.setValue('geo');
      await input.trigger('keydown.enter');
      await flushPromises();

      // then
      expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual(['person', 'geo']);
    });

    it('should_stayInInputMode_when_enter_given_belowLimitAfterAppend', async () => {
      // given
      const wrapper = mountEditor(['person']);
      const input = await openInput(wrapper);

      // when（连续录入：提交后仍停留在输入态）
      await input.setValue('geo');
      await input.trigger('keydown.enter');
      await flushPromises();

      // then
      expect(wrapper.find('.kb-entity__input input').exists()).toBe(true);
    });

    it('should_trimName_when_enter_given_draftWithSpaces', async () => {
      // given
      const wrapper = mountEditor([]);
      const input = await openInput(wrapper);

      // when
      await input.setValue('  geo  ');
      await input.trigger('keydown.enter');
      await flushPromises();

      // then
      expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual(['geo']);
    });

    it('should_emitTrimmedName_when_blur_given_nonBlankDraft', async () => {
      // given
      const wrapper = mountEditor(['person']);
      const input = await openInput(wrapper);

      // when
      await input.setValue(' event ');
      await input.trigger('blur');
      await flushPromises();

      // then
      expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual(['person', 'event']);
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
    });
  });

  describe('取消与空白输入', () => {
    it('should_exitWithoutEmit_when_escape_given_draft', async () => {
      // given
      const wrapper = mountEditor(['person']);
      const input = await openInput(wrapper);

      // when
      await input.setValue('geo');
      await input.trigger('keydown', { key: 'Escape' });
      await flushPromises();

      // then（Esc 取消：不入列表且退出输入态）
      expect(wrapper.emitted('update:modelValue')).toBeUndefined();
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
    });

    it('should_exitWithoutEmit_when_blur_given_blankDraft', async () => {
      // given
      const wrapper = mountEditor(['person']);
      const input = await openInput(wrapper);

      // when
      await input.setValue('   ');
      await input.trigger('blur');
      await flushPromises();

      // then（空白视为取消，与历史 filter 空串口径一致）
      expect(wrapper.emitted('update:modelValue')).toBeUndefined();
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
    });
  });

  describe('上限与提示', () => {
    it('should_closeInput_when_enter_given_reachingLimitAfterAppend', async () => {
      // given（已 9 条，补第 10 条即达上限）
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES - 1));
      const input = await openInput(wrapper);

      // when
      await input.setValue('last');
      await input.trigger('keydown.enter');
      await flushPromises();
      // 模拟宿主 v-model 回写（上限态依赖 props，本地不自持状态）
      await wrapper.setProps({ modelValue: [...names(MAX_ENTITY_TYPES - 1), 'last'] });
      await flushPromises();

      // then（达上限即收尾，输入框让位给被禁用的「＋」）
      expect(wrapper.find('.kb-entity__input').exists()).toBe(false);
      expect(wrapper.findComponent(Button).props('disabled')).toBe(true);
    });

    it('should_showLimitHintWithWayOut_when_render_given_maxedList', () => {
      // given / when
      const wrapper = mountEditor(names(MAX_ENTITY_TYPES));

      // then（提示必须给出可行出路，避免死局文案）
      expect(wrapper.find('.kb-field__hint').text()).toContain('已达自定义实体类型上限');
      expect(wrapper.find('.kb-field__hint').text()).toContain('删除后可再添加');
    });

    it('should_errorReplaceHint_when_render_given_errorProp', () => {
      // given / when
      const wrapper = mountEditor([], '实体类型「a」长度须在 2~30 个字符之间');

      // then
      expect(wrapper.find('.kb-field__error').text()).toContain('长度须在 2~30');
      expect(wrapper.find('.kb-field__hint').exists()).toBe(false);
    });
  });

  describe('内置类型只读展示', () => {
    it('should_renderAllBuiltinsWithoutDeleteEntry_when_render_given_anyList', () => {
      // given / when
      const wrapper = mountEditor(['person']);

      // then（内置 12 类全部展示，且无删除入口）
      const row = builtinRow(wrapper);
      expect(row.findAll(CHIP)).toHaveLength(BUILTIN_ENTITY_TYPES.length);
      expect(row.findAll(CLOSE_ICON)).toHaveLength(0);
      for (const builtin of BUILTIN_ENTITY_TYPES) {
        expect(row.text()).toContain(builtin);
      }
    });

    it('should_notEmitUpdate_when_clickBuiltinChip_given_anyList', async () => {
      // given（内置类型无删除入口：误触也不应产生变更）
      const wrapper = mountEditor(['person']);

      // when
      await builtinRow(wrapper).findAll(CHIP)[0].trigger('click');
      await flushPromises();

      // then
      expect(wrapper.emitted('update:modelValue')).toBeUndefined();
    });

    it('should_keepCustomChipsEditable_when_render_given_mixedRows', () => {
      // given / when（两行并存时，删除入口只归属自定义行）
      const wrapper = mountEditor(['person', 'geo']);

      // then
      expect(customRow(wrapper).findAll(CLOSE_ICON)).toHaveLength(2);
      expect(builtinRow(wrapper).findAll(CLOSE_ICON)).toHaveLength(0);
    });

    it('should_notUseTagInput_when_render_given_emptyList', () => {
      // given / when
      const wrapper = mountEditor([]);

      // then（旧 t-tag-input 已退役，防回归）
      expect(wrapper.findComponent(TagInput).exists()).toBe(false);
    });
  });
});
