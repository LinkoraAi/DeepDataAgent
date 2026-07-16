import { MessagePlugin } from 'tdesign-vue-next';

/**
 * Copy text to clipboard
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      MessagePlugin.success('复制成功');
      return true;
    } else {
      // Fallback for older browsers
      const textArea = document.createElement('textarea');
      textArea.value = text;
      textArea.style.position = 'fixed';
      textArea.style.left = '-999999px';
      document.body.appendChild(textArea);
      textArea.select();
      
      try {
        document.execCommand('copy');
        MessagePlugin.success('复制成功');
        return true;
      } catch (err) {
        MessagePlugin.error('复制失败');
        return false;
      } finally {
        document.body.removeChild(textArea);
      }
    }
  } catch (err) {
    console.error('Failed to copy:', err);
    MessagePlugin.error('复制失败');
    return false;
  }
}
