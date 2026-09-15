import { createApp } from 'vue';
import App from './App.vue';
import './style.css';
import './runtime.css';

async function mountWorkbench(): Promise<void> {
  const app = createApp(App);
  if (import.meta.env.DEV) {
    const { createDeveloperSettingsController } = await import('./composables/useDeveloperSettings');
    const settings = createDeveloperSettingsController();
    app.provide('developer-settings', settings);
    import.meta.hot?.dispose(() => { settings.dispose(); app.unmount(); });
    app.mount('#app');
    void settings.initialize();
    return;
  }
  app.mount('#app');
}

void mountWorkbench();
