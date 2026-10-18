/**
 * 弹层层级（z-index）标度。
 *
 * <p>背景（务必按此理解，勿凭直觉改动）：TDesign 把弹层层级写在 CSS 里且为固定值——
 * {@code .t-drawer} 为 1500、{@code .t-dialog__ctx--fixed} 为 2500；而 {@code t-dialog} /
 * {@code t-drawer} 的 {@code zIndex} 属性默认为 undefined，因此**所有弹窗同为 2500、
 * 所有抽屉同为 1500**。同级元素的绘制顺序退化为 DOM 顺序，而嵌套弹层（例如「元数据浏览」
 * 弹窗内的「表注释」「字段列表」「数据预览」）会先于父弹层被插入 {@code body}，
 * 于是子弹层被父弹层盖住——表现为"子弹窗弹在父弹窗下面"。</p>
 *
 * <p>因此**凡存在嵌套关系的弹层，必须显式抬高 {@code z-index}**，不能依赖 DOM 顺序。
 * 标度约定：每深一层加 100；确认框恒在全部弹窗之上（因为确认框可能自弹窗内部发起）。</p>
 *
 * <pre>
 * 1500  drawer          顶层抽屉（TDesign 默认值）
 * 1700  drawerNested    抽屉内的抽屉
 * 2500  dialog          顶层弹窗（TDesign 默认值）
 * 2600  dialogNested    弹窗内的弹窗（第一层嵌套）
 * 2700  dialogNested2   弹窗内的弹窗（第二层嵌套）
 * 2900  confirm         危险操作确认框（confirmDangerous）
 * 5000  message         轻提示（TDesign 默认值，无需干预）
 * </pre>
 */
export const LAYER = {
  /** 顶层抽屉 */
  drawer: 1500,
  /** 抽屉内的抽屉 */
  drawerNested: 1700,
  /** 顶层弹窗 */
  dialog: 2500,
  /** 弹窗内的弹窗（第一层嵌套） */
  dialogNested: 2600,
  /** 弹窗内的弹窗（第二层嵌套） */
  dialogNested2: 2700,
  /** 危险操作确认框（恒在最上层，可自弹窗内部发起） */
  confirm: 2900,
  /** 轻提示 */
  message: 5000,
} as const;