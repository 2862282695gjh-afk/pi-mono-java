import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { devAuthPlugin } from './dev/authMiddleware';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [vue(), devAuthPlugin({ account: env.CAMPUSCLAW_DEV_AUTH_ACCOUNT ?? '', password: env.CAMPUSCLAW_DEV_AUTH_PASSWORD ?? '' })],
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/campusclaw-service': {
          target: env.VITE_BACKEND_URL ?? 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  };
});
