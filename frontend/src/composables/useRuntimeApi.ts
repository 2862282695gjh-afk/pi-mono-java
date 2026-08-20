import { computed, ref } from 'vue';
import type {
  AcceptedControl,
  AvailableModels,
  ControlAccepted,
  ErrorBean,
  FollowUpMode,
  ResultBean,
  RuntimeEventData,
  RuntimeEventEnvelope,
  RuntimeHistoryPage,
  RuntimeSession,
} from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';

const API_PATH = '/campusclaw-service/v1';
const apiBase = trimTrailingSlash(import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? '');
const callerId = import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web';

export function useRuntimeApi() {
  const session = ref<RuntimeSession | null>(null);
  const etag = ref('');
  const models = ref<string[]>([]);
  const events = ref<RuntimeEventEnvelope[]>([]);
  const streaming = ref(false);
  const lastError = ref('');
  const lastErrorCode = ref('');
  const acceptedControls = ref<AcceptedControl[]>([]);
  const hasSession = computed(() => session.value !== null);
  let streamGeneration = 0;

  async function createSession(agentId: string): Promise<RuntimeSession> {
    clearError();
    detachCurrentStream();
    const created = await requestResult<RuntimeSession>(
      `/agents/${encodeURIComponent(agentId)}/sessions`,
      { method: 'POST' },
      true,
    );
    session.value = created;
    events.value = [];
    acceptedControls.value = [];
    etag.value = '';
    await refreshSessionMetadata();
    return created;
  }

  async function getSession(sessionId = session.value?.session_id): Promise<RuntimeSession> {
    if (!sessionId) throw missingSessionError();
    clearError();
    if (session.value && session.value.session_id !== sessionId) {
      detachCurrentStream();
      events.value = [];
      acceptedControls.value = [];
    }
    const current = await requestResult<RuntimeSession>(`/sessions/${encodeURIComponent(sessionId)}`);
    session.value = current;
    return current;
  }

  async function deleteSession(): Promise<void> {
    const sessionId = requireSessionId();
    clearError();
    await requestEmpty(`/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }, true);
    streamGeneration += 1;
    streaming.value = false;
    session.value = null;
    etag.value = '';
    models.value = [];
    events.value = [];
    acceptedControls.value = [];
  }

  async function listModels(): Promise<AvailableModels> {
    const sessionId = requireSessionId();
    clearError();
    const available = await requestResult<AvailableModels>(
      `/sessions/${encodeURIComponent(sessionId)}/models`,
    );
    models.value = available.models;
    if (session.value) session.value.model_id = available.current_model_id;
    return available;
  }

  async function changeModel(modelId: string): Promise<RuntimeSession> {
    const sessionId = requireSessionId();
    clearError();
    await ensureEtag();
    const updated = await requestResult<RuntimeSession>(
      `/sessions/${encodeURIComponent(sessionId)}/model`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'If-Match': etag.value },
        body: JSON.stringify({ model_id: modelId }),
      },
      true,
    );
    session.value = updated;
    return updated;
  }

  async function changeThinking(thinking: boolean): Promise<RuntimeSession> {
    const sessionId = requireSessionId();
    clearError();
    await ensureEtag();
    const updated = await requestResult<RuntimeSession>(
      `/sessions/${encodeURIComponent(sessionId)}/thinking`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'If-Match': etag.value },
        body: JSON.stringify({ thinking }),
      },
      true,
    );
    session.value = updated;
    await loadHistory();
    return updated;
  }

  async function loadHistory(): Promise<RuntimeEventEnvelope[]> {
    const sessionId = requireSessionId();
    clearError();
    const history: RuntimeEventEnvelope[] = [];
    const seenPages = new Set<string>();
    let page: string | null = null;

    do {
      const query = new URLSearchParams({ limit: '200' });
      if (page) query.set('page', page);
      const result = await requestResult<RuntimeHistoryPage>(
        `/sessions/${encodeURIComponent(sessionId)}/events?${query.toString()}`,
      );
      history.push(...result.events.map(normalizeHistoryEvent));
      page = result.next_page ?? null;
      if (page && seenPages.has(page)) break;
      if (page) seenPages.add(page);
    } while (page);

    events.value = deduplicatePersistentEvents(history);
    reconcileAcceptedControlsFromHistory();
    return events.value;
  }

  async function sendMessage(message: string, fileIds: string[] = []): Promise<void> {
    const sessionId = requireSessionId();
    clearError();
    const body: { message?: string; file_ids?: string[] } = {};
    if (message.trim()) body.message = message.trim();
    if (fileIds.length > 0) body.file_ids = fileIds;

    const response = await requestRaw(
      `/sessions/${encodeURIComponent(sessionId)}/events`,
      {
        method: 'POST',
        headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      },
      true,
    );
    const generation = ++streamGeneration;
    streaming.value = true;
    if (session.value?.session_id === sessionId) session.value.state = 'running';
    void consumeSse(response, sessionId, generation).catch((error: unknown) => {
      if (generation !== streamGeneration) return;
      publishError(normalizeError(error, false));
    });
  }

  async function steer(message: string): Promise<ControlAccepted> {
    return appendControl('steer', message);
  }

  async function followUp(message: string): Promise<ControlAccepted> {
    return appendControl('queue', message);
  }

  async function abort(): Promise<void> {
    const sessionId = requireSessionId();
    clearError();
    await requestEmpty(`/sessions/${encodeURIComponent(sessionId)}/abort`, { method: 'POST' }, true);
    detachCurrentStream();
    acceptedControls.value = [];
    if (session.value?.session_id === sessionId) session.value.state = 'idle';
  }

  function clearError(): void {
    lastError.value = '';
    lastErrorCode.value = '';
  }

  function clearSessionView(): void {
    detachCurrentStream();
    session.value = null;
    etag.value = '';
    models.value = [];
    events.value = [];
    acceptedControls.value = [];
    clearError();
  }

  function detachCurrentStream(): void {
    streamGeneration += 1;
    streaming.value = false;
  }

  async function appendControl(mode: FollowUpMode, message: string): Promise<ControlAccepted> {
    const sessionId = requireSessionId();
    clearError();
    const resource = mode === 'steer' ? 'steers' : 'follow-ups';
    const accepted = await requestResult<ControlAccepted>(
      `/sessions/${encodeURIComponent(sessionId)}/${resource}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message.trim() }),
      },
      true,
    );
    acceptedControls.value.push({
      key: crypto.randomUUID(),
      message: message.trim(),
      mode,
      acceptedAt: accepted.accepted_at,
    });
    return accepted;
  }

  async function refreshSessionMetadata(): Promise<void> {
    await getSession();
    await listModels();
  }

  async function ensureEtag(): Promise<void> {
    if (!etag.value) await getSession();
  }

  async function consumeSse(
    response: Response,
    sessionId: string,
    generation: number,
  ): Promise<void> {
    if (!response.body) throw new RuntimeApiError({ message: '服务未返回可读取的执行流。' });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        const frames = buffer.split(/\r?\n\r?\n/u);
        buffer = frames.pop() ?? '';
        for (const frame of frames) dispatchSseFrame(frame, sessionId, generation);
        if (done) break;
      }
      if (buffer.trim()) dispatchSseFrame(buffer, sessionId, generation);
    } finally {
      if (generation === streamGeneration) {
        streaming.value = false;
        await reconcileAfterStream(sessionId);
      }
    }
  }

  function dispatchSseFrame(frame: string, sessionId: string, generation: number): void {
    if (generation !== streamGeneration || session.value?.session_id !== sessionId) return;
    let event = 'message';
    let id: string | undefined;
    const dataLines: string[] = [];

    for (const line of frame.split(/\r?\n/u)) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('id:')) id = line.slice(3).trim();
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
    }
    if (dataLines.length === 0) return;

    const data = parseEventData(dataLines.join('\n'));
    if (event === 'stream.error') {
      publishError(streamError(data));
      return;
    }
    if (event === 'session.status.idle') {
      if (session.value) session.value.state = 'idle';
      return;
    }
    if (event === 'stream.end') return;

    mergeRuntimeEvent({ id, event, data });
    if (event === 'user.message') reconcileAcceptedControl(data);
  }

  function mergeRuntimeEvent(envelope: RuntimeEventEnvelope): void {
    const entryId = readString(envelope.data.entry_id);
    if (entryId && isPersistentEvent(envelope.event)) {
      const index = events.value.findIndex(
        (item) => item.event === envelope.event && readString(item.data.entry_id) === entryId,
      );
      if (index >= 0) {
        events.value[index] = envelope;
        return;
      }
    }
    events.value.push(envelope);
  }

  function reconcileAcceptedControl(data: RuntimeEventData): void {
    const message = readString(data.message);
    const index = acceptedControls.value.findIndex((control) => control.message === message);
    if (index >= 0) acceptedControls.value.splice(index, 1);
  }

  function reconcileAcceptedControlsFromHistory(): void {
    if (session.value?.state === 'idle') {
      acceptedControls.value = [];
      return;
    }
    const deliveredMessages = new Set(
      events.value
        .filter((event) => event.event === 'user.message')
        .map((event) => readString(event.data.message)),
    );
    acceptedControls.value = acceptedControls.value.filter(
      (control) => !deliveredMessages.has(control.message),
    );
  }

  async function reconcileAfterStream(sessionId: string): Promise<void> {
    if (session.value?.session_id !== sessionId) return;
    const streamErrorMessage = lastError.value;
    const streamErrorCode = lastErrorCode.value;
    try {
      await getSession(sessionId);
      await loadHistory();
    } catch {
      // 已展示适配层生成的安全错误，保留当前投影供用户恢复。
    } finally {
      if (streamErrorMessage) {
        lastError.value = streamErrorMessage;
        lastErrorCode.value = streamErrorCode;
      }
    }
  }

  async function requestResult<T>(
    path: string,
    init: RequestInit = {},
    mutating = false,
  ): Promise<T> {
    const response = await requestRaw(path, init, mutating);
    const body = await readJson<ResultBean<T> | ErrorBean>(response);
    if (!isResultBean<T>(body)) {
      throw publishAndReturn(new RuntimeApiError({
        message: '服务响应格式不符合约定，请刷新后重试。',
        status: response.status,
        code: 'INVALID_RESPONSE',
      }));
    }
    return body.result;
  }

  async function requestEmpty(
    path: string,
    init: RequestInit,
    mutating = false,
  ): Promise<void> {
    await requestRaw(path, init, mutating);
  }

  async function requestRaw(
    path: string,
    init: RequestInit = {},
    mutating = false,
  ): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(`${apiBase}${API_PATH}${path}`, {
        ...init,
        headers: {
          Accept: 'application/json',
          'Accept-Language': navigator.language.startsWith('zh') ? 'zh-CN' : 'en-US',
          'X-HW-ID': callerId,
          ...init.headers,
        },
      });
    } catch (error) {
      throw publishAndReturn(normalizeError(error, mutating));
    }

    const responseEtag = response.headers.get('ETag');
    if (responseEtag) etag.value = responseEtag;
    if (!response.ok) throw publishAndReturn(await responseError(response));
    return response;
  }

  async function responseError(response: Response): Promise<RuntimeApiError> {
    const body = await readJson<ErrorBean>(response);
    const code = body?.resCode || `HTTP_${response.status}`;
    return new RuntimeApiError({
      message: friendlyError(code, body?.resMsg),
      status: response.status,
      code,
      retryAfter: response.headers.get('Retry-After') ?? undefined,
    });
  }

  function publishAndReturn(error: RuntimeApiError): RuntimeApiError {
    publishError(error);
    return error;
  }

  function publishError(error: RuntimeApiError): void {
    lastError.value = error.message;
    lastErrorCode.value = error.code;
  }

  function requireSessionId(): string {
    const sessionId = session.value?.session_id;
    if (!sessionId) throw publishAndReturn(missingSessionError());
    return sessionId;
  }

  return {
    acceptedControls,
    abort,
    changeModel,
    changeThinking,
    clearError,
    clearSessionView,
    createSession,
    deleteSession,
    etag,
    events,
    followUp,
    getSession,
    hasSession,
    lastError,
    lastErrorCode,
    listModels,
    loadHistory,
    models,
    sendMessage,
    session,
    steer,
    streaming,
  };
}

function normalizeHistoryEvent(data: RuntimeEventData): RuntimeEventEnvelope {
  const event = readString(data.type);
  const normalized = { ...data };
  delete normalized.type;
  return { id: String(data.entry_seq ?? ''), event, data: normalized };
}

function deduplicatePersistentEvents(events: RuntimeEventEnvelope[]): RuntimeEventEnvelope[] {
  const seen = new Set<string>();
  return events.filter((event) => {
    const key = `${event.event}:${readString(event.data.entry_id)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function parseEventData(value: string): RuntimeEventData {
  try {
    const parsed: unknown = JSON.parse(value);
    return isRecord(parsed) ? parsed : { value: parsed };
  } catch {
    return { message: value };
  }
}

function streamError(data: RuntimeEventData): RuntimeApiError {
  const code = readString(data.resCode) || readString(data.code) || 'STREAM_ERROR';
  const fallback = readString(data.resMsg) || readString(data.message);
  return new RuntimeApiError({ message: friendlyError(code, fallback), code });
}

function normalizeError(error: unknown, outcomeUncertain: boolean): RuntimeApiError {
  if (error instanceof RuntimeApiError) return error;
  return new RuntimeApiError({
    message: outcomeUncertain
      ? '请求结果暂时无法确认。请先刷新会话，不要重复提交。'
      : '暂时无法连接服务，请检查网络后重试。',
    code: outcomeUncertain ? 'OUTCOME_UNCERTAIN' : 'NETWORK_ERROR',
    outcomeUncertain,
  });
}

function friendlyError(code: string, fallback = ''): string {
  const messages: Record<string, string> = {
    CONTROL_QUEUE_FULL: '待处理要求已满，请等待当前要求开始执行后再添加。',
    SESSION_BUSY: '任务正在执行。请使用“调整方向”或“加入队列”。',
    SESSION_EXECUTION_UNAVAILABLE: '执行连接正在恢复，请稍后刷新会话。',
    SESSION_NOT_RUNNING: '任务已结束，请直接发送一条新消息。',
    SESSION_VERSION_MISMATCH: '会话设置已变化，请刷新后重新选择。',
    SESSION_NOT_FOUND: '这个会话已不存在，请新建会话。',
    AGENT_NOT_AVAILABLE: '这个 Agent 当前不可用，请稍后重试。',
    MANAGER_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
  };
  return messages[code] || fallback || '操作没有完成，请稍后重试。';
}

function missingSessionError(): RuntimeApiError {
  return new RuntimeApiError({ message: '请先新建或恢复一个会话。', code: 'SESSION_REQUIRED' });
}

async function readJson<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

function isResultBean<T>(value: ResultBean<T> | ErrorBean | null): value is ResultBean<T> {
  return value !== null && 'result' in value;
}

function isPersistentEvent(event: string): boolean {
  return [
    'user.message',
    'assistant.thinking.completed',
    'assistant.message.completed',
    'tool.result',
  ].includes(event);
}

function isRecord(value: unknown): value is RuntimeEventData {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/u, '');
}
