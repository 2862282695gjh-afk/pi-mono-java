import { ref, shallowRef } from 'vue';
import type { Ref, ShallowRef } from 'vue';

export interface RuntimeConnectionSnapshot {
  apiBase: string;
  backendIdentity: string;
  callerId: string;
  proxyVersion?: number;
  proxyKey?: string;
}

export interface RuntimeEnvironmentSource {
  ready: Ref<boolean>;
  revision: Ref<number>;
  snapshot(): RuntimeConnectionSnapshot;
}

export interface RuntimeEnvironmentBridge extends RuntimeEnvironmentSource {
  current: ShallowRef<RuntimeConnectionSnapshot>;
  apply(snapshot: RuntimeConnectionSnapshot): void;
  setReady(ready: boolean): void;
}

export function normalizeApiBase(value: string): string {
  return value.trim().replace(/\/+$/u, '');
}

export function normalizeBackendUrl(value: string): string {
  return value.trim().replace(/\/+$/u, '');
}

export function backendIdentity(apiBase: string, backendUrl: string, pageOrigin = browserOrigin()): string {
  const normalizedApi = normalizeApiBase(apiBase);
  if (normalizedApi) {
    try {
      return `direct:${new URL(normalizedApi, pageOrigin).href.replace(/\/+$/u, '')}`;
    } catch {
      return `direct:${normalizedApi}`;
    }
  }
  return `proxy:${normalizeBackendUrl(backendUrl)}`;
}

export function defaultRuntimeConnection(): RuntimeConnectionSnapshot {
  const apiBase = normalizeApiBase(import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? '');
  const backendUrl = normalizeBackendUrl(import.meta.env.VITE_BACKEND_URL ?? 'http://localhost:8080');
  return {
    apiBase,
    backendIdentity: backendIdentity(apiBase, backendUrl),
    callerId: import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web',
    ...(apiBase ? {} : { proxyVersion: 0 }),
  };
}

export function createRuntimeEnvironmentBridge(
  initial = defaultRuntimeConnection(),
  initiallyReady = true,
): RuntimeEnvironmentBridge {
  const current = shallowRef(cloneSnapshot(initial));
  const ready = ref(initiallyReady);
  const revision = ref(0);
  return {
    current,
    ready,
    revision,
    snapshot: () => cloneSnapshot(current.value),
    apply(snapshot) {
      const next = cloneSnapshot(snapshot);
      if (sameConnection(current.value, next)) return;
      current.value = next;
      revision.value += 1;
    },
    setReady(value) {
      ready.value = value;
    },
  };
}

function sameConnection(left: RuntimeConnectionSnapshot, right: RuntimeConnectionSnapshot): boolean {
  return left.apiBase === right.apiBase
    && left.backendIdentity === right.backendIdentity
    && left.callerId === right.callerId
    && left.proxyVersion === right.proxyVersion
    && left.proxyKey === right.proxyKey;
}

function cloneSnapshot(value: RuntimeConnectionSnapshot): RuntimeConnectionSnapshot {
  return { ...value };
}

function browserOrigin(): string {
  return typeof location === 'undefined' ? 'http://localhost' : location.origin;
}
