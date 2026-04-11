import { createApp } from 'vue';
import { createPinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import TDesignChat from '@tdesign-vue-next/chat';
import App from './App.vue';
import router from './app/router';
import './shared/styles/index.css';
import 'tdesign-vue-next/es/style/index.css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(TDesign);
app.use(TDesignChat);
app.mount('#app');
