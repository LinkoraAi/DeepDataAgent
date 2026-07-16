/**
 * UI 全局状态 store
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useUiStore = defineStore('ui', () => {
    /** 侧栏是否收起 */
    const sidebarCollapsed = ref(false);

    /** 待填入输入框的提示语（替代 window 自定义事件） */
    const pendingSuggestion = ref<string | null>(null);

    /** 切换侧栏收起状态 */
    function toggleSidebar() {
        sidebarCollapsed.value = !sidebarCollapsed.value;
    }

    /** 收起侧栏 */
    function collapseSidebar() {
        sidebarCollapsed.value = true;
    }

    /** 展开侧栏 */
    function expandSidebar() {
        sidebarCollapsed.value = false;
    }

    /** 设置提示语 */
    function setSuggestion(text: string) {
        pendingSuggestion.value = text;
    }

    /** 清空提示语 */
    function clearSuggestion() {
        pendingSuggestion.value = null;
    }

    return {
        sidebarCollapsed,
        pendingSuggestion,
        toggleSidebar,
        collapseSidebar,
        expandSidebar,
        setSuggestion,
        clearSuggestion,
    };
});
