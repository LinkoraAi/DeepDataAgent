import type { App } from 'vue';
import { createPinia } from 'pinia';

/** Pinia 装配点。 */
export function setupStore(app: App): void {
  app.use(createPinia());
}
