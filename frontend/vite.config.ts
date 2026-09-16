import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';
import { devServicesPlugin } from './dev/authMiddleware';
import { DevConfigService, LocalConfigRepository } from './dev/localConfig';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const configFile = fileURLToPath(new URL('../.campusclaw/frontend-dev-settings.json', import.meta.url));
  const configService = new DevConfigService(new LocalConfigRepository(configFile, {
    backendUrl: env.VITE_BACKEND_URL ?? 'http://localhost:8080',
    account: env.CAMPUSCLAW_DEV_AUTH_ACCOUNT ?? '',
    password: env.CAMPUSCLAW_DEV_AUTH_PASSWORD ?? '',
  }));
  return {
    plugins: [vue(), devServicesPlugin(configService)],
    server: {
      host: '127.0.0.1',
      port: 5173,
      fs: {
        deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/.campusclaw/**'],
      },
    },
  };
});
