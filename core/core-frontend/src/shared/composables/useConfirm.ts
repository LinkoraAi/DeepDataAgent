import { DialogPlugin } from 'tdesign-vue-next';
import { LAYER } from '@/shared/constants/layers';

/**
 * Promise 化危险操作确认框（删除/重解析/归档等破坏性动作 MUST 经本入口）。
 * @param options.header 标题
 * @param options.body 后果说明（必填语义场景要写清不可逆性）
 * @param options.theme 主题（danger 用于删除类）
 *
 * 层级：显式抬到 {@link LAYER.confirm}。确认框可能自抽屉/弹窗内部发起，
 * 而 TDesign 默认所有弹窗同为 2500（同级靠 DOM 顺序决胜），不抬高会被宿主盖住。
 */
export function confirmDangerous(options: {
  header: string;
  body: string;
  theme?: 'danger' | 'warning' | 'info';
  confirmText?: string;
}): Promise<boolean> {
  return new Promise((resolve) => {
    const dialog = DialogPlugin.confirm({
      header: options.header,
      body: options.body,
      theme: options.theme ?? 'danger',
      confirmBtn: options.confirmText ?? '确认',
      cancelBtn: '取消',
      // 与全站业务弹窗统一居中定位（组件库默认贴顶，混用观感不一）
      placement: 'center',
      zIndex: LAYER.confirm,
      onConfirm: () => {
        dialog.destroy();
        resolve(true);
      },
      onClose: () => {
        dialog.destroy();
        resolve(false);
      },
    });
  });
}
