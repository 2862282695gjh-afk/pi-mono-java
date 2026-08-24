/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CAMPUSCLAW_API_BASE?: string;
  readonly VITE_CAMPUSCLAW_CALLER_ID?: string;
  readonly VITE_CAMPUSCLAW_AGENT_ID?: string;
  readonly VITE_CAMPUSCLAW_AGENT_NAME?: string;
  readonly VITE_CAMPUSCLAW_AGENT_DESCRIPTION?: string;
  readonly VITE_CAMPUSCLAW_AGENT_CATEGORY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<object, object, unknown>;
  export default component;
}
