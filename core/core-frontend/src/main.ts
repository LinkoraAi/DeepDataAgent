import { createApp } from 'vue';
import App from './App.vue';
import router from './app/router';
import { setupStore } from './app/store';
import { setupTDesign } from './app/plugins/tdesign';
import './shared/styles/index.css';

/** 应用入口：装配 Pinia、路由与 TDesign 后挂载。 */
const app = createApp(App);
setupStore(app);
setupTDesign(app);
app.use(router);
app.mount('#app');
