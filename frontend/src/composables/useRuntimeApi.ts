import { computed, ref } from 'vue';
import type {
  AvailableModels,
  ControlAccepted,
  ErrorBean,
  ResultBean,
  RuntimeAuth,
  RuntimeHistoryPage,
  RuntimeSession,
  RuntimeSseEvent,
} from '../types/runtime';

const BASE_PATH = '/campusclaw-service/v1';

export function useRuntimeApi() {
  const apiBase = ref('');
  const auth = ref<RuntimeAuth>({
    credentialId: 'mate-service',
    credentialMode: 'jwt',
    credentialSecret: '',
  });
  const session = ref<RuntimeSession | null>(null);
  const etag = ref<string | null>(null);
  const models = ref<string[]>([]);
  const events = ref<RuntimeSseEvent[]>([]);
  const streaming = ref(false);
  const lastError = ref('');
  const activeRequest = ref<AbortController | null>(null);
  const hasSession = computed(() => session.value !== null);

  function authHeaders(): Record<string, string> {
    const headers: Record<string, string> = { 'X-HW-ID': auth.value.credentialId };
    if (auth.value.credentialMode === 'jwt') {
      headers.Authorization = `Bearer ${auth.value.credentialSecret}`;
    } else {
      headers['X-HW-APPKEY'] = auth.value.credentialSecret;
    }
    return headers;
  }

  function url(path: string): string {
    return `${apiBase.value.replace(/\/$/u, '')}${BASE_PATH}${path}`;
  }

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    lastError.value = '';
    const headers = new Headers(authHeaders());
    new Headers(init.headers).forEach((value, name) => headers.set(name, value));
    const response = await fetch(url(path), { ...init, headers });
    if (response.status === 204) return undefined as T;
    const body = (await response.json()) as ResultBean<T> | ErrorBean;
    if (!response.ok || !('result' in body)) {
      const message = `${body.resCode}: ${body.resMsg}`;
      lastError.value = message;
      throw new Error(message);
    }
    etag.value = response.headers.get('ETag') ?? etag.value;
    return body.result;
  }

  async function createSession(agentId: string): Promise<void> {
    session.value = await request<RuntimeSession>(
      `/agents/${encodeURIComponent(agentId)}/sessions`,
      { method: 'POST' },
    );
    events.value = [];
  }

  async function getSession(sessionId?: string): Promise<void> {
    const id = sessionId || session.value?.session_id;
    if (!id) return;
    session.value = await request<RuntimeSession>(`/sessions/${encodeURIComponent(id)}`);
  }

  async function deleteSession(): Promise<void> {
    if (!session.value) return;
    await request<void>(`/sessions/${encodeURIComponent(session.value.session_id)}`, {
      method: 'DELETE',
    });
    session.value = null;
    etag.value = null;
    events.value = [];
  }

  async function listModels(): Promise<void> {
    if (!session.value) return;
    const result = await request<AvailableModels>(
      `/sessions/${encodeURIComponent(session.value.session_id)}/models`,
    );
    models.value = result.models;
  }

  async function changeModel(modelId: string): Promise<void> {
    await updateSession('model', { model_id: modelId });
  }

  async function changeThinking(thinking: boolean): Promise<void> {
    await updateSession('thinking', { thinking });
  }

  async function updateSession(path: string, body: Record<string, unknown>): Promise<void> {
    if (!session.value || !etag.value) throw new Error('Refresh Session before updating it.');
    session.value = await request<RuntimeSession>(
      `/sessions/${encodeURIComponent(session.value.session_id)}/${path}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'If-Match': etag.value },
        body: JSON.stringify(body),
      },
    );
  }

  async function loadHistory(page?: string): Promise<RuntimeHistoryPage | null> {
    if (!session.value) return null;
    const query = new URLSearchParams({ limit: '100' });
    if (page) query.set('page', page);
    const result = await request<RuntimeHistoryPage>(
      `/sessions/${encodeURIComponent(session.value.session_id)}/events?${query}`,
    );
    events.value = result.events.map((data) => ({ event: String(data.type ?? 'entry'), data }));
    return result;
  }

  async function sendMessage(message: string, fileIds: string[]): Promise<void> {
    if (!session.value) return;
    const controller = new AbortController();
    activeRequest.value = controller;
    streaming.value = true;
    lastError.value = '';
    try {
      const response = await fetch(
        url(`/sessions/${encodeURIComponent(session.value.session_id)}/events`),
        {
          method: 'POST',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({ type: 'user.message', message, file_ids: fileIds }),
          signal: controller.signal,
        },
      );
      if (!response.ok || !response.body) await throwHttpError(response);
      await consumeSse(response);
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        lastError.value = (error as Error).message;
        throw error;
      }
    } finally {
      activeRequest.value = null;
      streaming.value = false;
      await refreshSessionAfterStream();
    }
  }

  async function refreshSessionAfterStream(): Promise<void> {
    try {
      await getSession();
    } catch {
      // 保留流式请求的原始结果，刷新失败已通过 lastError 展示。
    }
  }

  async function consumeSse(response: Response): Promise<void> {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/gu, '\n');
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        dispatchSseFrame(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf('\n\n');
      }
      if (done) break;
    }
    if (buffer.trim()) dispatchSseFrame(buffer);
  }

  function dispatchSseFrame(frame: string): void {
    if (!frame || frame.startsWith(':')) return;
    const lines = frame.split('\n');
    const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
    const id = lines.find((line) => line.startsWith('id:'))?.slice(3).trim();
    const dataText = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n');
    if (!event || !dataText) return;
    events.value.push({ id: id || undefined, event, data: JSON.parse(dataText) as Record<string, unknown> });
  }

  async function control(path: 'steers' | 'follow-ups', message: string): Promise<ControlAccepted> {
    if (!session.value) throw new Error('Session is required.');
    return request<ControlAccepted>(
      `/sessions/${encodeURIComponent(session.value.session_id)}/${path}`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message }) },
    );
  }

  async function abort(): Promise<void> {
    if (!session.value) return;
    await request<void>(`/sessions/${encodeURIComponent(session.value.session_id)}/abort`, { method: 'POST' });
  }

  function disconnectStream(): void {
    activeRequest.value?.abort();
  }

  async function throwHttpError(response: Response): Promise<never> {
    const body = (await response.json()) as ErrorBean;
    throw new Error(`${body.resCode}: ${body.resMsg}`);
  }

  return {
    apiBase,
    auth,
    session,
    etag,
    models,
    events,
    streaming,
    lastError,
    hasSession,
    createSession,
    getSession,
    deleteSession,
    listModels,
    changeModel,
    changeThinking,
    loadHistory,
    sendMessage,
    steer: (message: string) => control('steers', message),
    followUp: (message: string) => control('follow-ups', message),
    abort,
    disconnectStream,
  };
}
