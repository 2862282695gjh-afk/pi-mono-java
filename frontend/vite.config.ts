import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [vue()],
    server: {
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
