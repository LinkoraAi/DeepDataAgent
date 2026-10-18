import { describe, expect, it } from 'vitest';
import { LAYER } from './layers';

/**
 * 弹层层级标度回归测试。
 * 目的：防止后续调整把嵌套层级改得「不高于宿主」——那会让子弹层被宿主盖住（同级靠 DOM 顺序决胜）。
 */
describe('LAYER 弹层层级标度', () => {
  it('should 嵌套层级严格高于宿主层级_when 逐层比较', () => {
    expect(LAYER.drawerNested).toBeGreaterThan(LAYER.drawer);
    expect(LAYER.dialog).toBeGreaterThan(LAYER.drawerNested);
    expect(LAYER.dialogNested).toBeGreaterThan(LAYER.dialog);
    expect(LAYER.dialogNested2).toBeGreaterThan(LAYER.dialogNested);
  });

  it('should 与 TDesign 内置层级保持一致_when 取顶层抽屉与弹窗', () => {
    expect(LAYER.drawer).toBe(1500);
    expect(LAYER.dialog).toBe(2500);
    expect(LAYER.message).toBe(5000);
  });

  it('should 确认框高于全部弹窗且低于轻提示_when 自弹窗内部发起确认', () => {
    expect(LAYER.confirm).toBeGreaterThan(LAYER.dialogNested2);
    expect(LAYER.confirm).toBeLessThan(LAYER.message);
  });
});