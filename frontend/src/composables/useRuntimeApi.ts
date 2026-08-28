import { computed, ref } from 'vue';
import type {
  AcceptedControl,
  AvailableModels,
  ControlAccepted,
  ErrorBean,
  FollowUpMode,
  MessageSubmission,
  ResultBean,
  RuntimeEventData,
  RuntimeEventEnvelope,
  RuntimeHistoryPage,
  RuntimeSession,
  SubmissionOutcome,
} from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';

const API_PATH = '/campusclaw-service/v1';
const apiBase = trimTrailingSlash(import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? '');
const callerId = import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web';

interface PendingSubmission {
  sessionId: string;
  message: string;
  fileIds: string[];
  knownEntryIds: Set<string>;
  confirmation: Promise<SubmissionOutcome>;
  resolve: (outcome: SubmissionOutcome) => void;
  settled: boolean;
}

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
  let pendingSubmission: PendingSubmission | null = null;

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

  async function getSession(sessionId = session.value?.sessionId): Promise<RuntimeSession> {
    if (!sessionId) throw missingSessionError();
    clearError();
    if (session.value && session.value.sessionId !== sessionId) {
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
    if (session.value) session.value.modelId = available.currentModelId;
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
        body: JSON.stringify({ modelId }),
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
      page = result.nextPage ?? null;
      if (page && seenPages.has(page)) break;
      if (page) seenPages.add(page);
    } while (page);

    events.value = deduplicatePersistentEvents(history);
    reconcileAcceptedControlsFromHistory();
    return events.value;
  }

  async function sendMessage(
    message: string,
    fileIds: string[] = [],
    requestHeaders?: HeadersInit,
  ): Promise<MessageSubmission> {
    const sessionId = requireSessionId();
    clearError();
    const normalizedMessage = message.trim();
    const body: { message?: string; fileIds?: string[] } = {};
    if (normalizedMessage) body.message = normalizedMessage;
    if (fileIds.length > 0) body.fileIds = fileIds;

    const response = await requestRaw(
      `/sessions/${encodeURIComponent(sessionId)}/events`,
      {
        method: 'POST',
        headers: mergeRequestHeaders(
          { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
          requestHeaders,
        ),
        body: JSON.stringify(body),
      },
      true,
    );
    settlePendingSubmission('uncertain');
    const submission = createPendingSubmission(sessionId, normalizedMessage, fileIds);
    const generation = ++streamGeneration;
    streaming.value = true;
    if (session.value?.sessionId === sessionId) session.value.state = 'running';
    void consumeSse(response, sessionId, generation);
    return { confirmation: submission.confirmation };
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
    if (session.value?.sessionId === sessionId) session.value.state = 'idle';
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
    settlePendingSubmission('uncertain');
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
      acceptedAt: accepted.acceptedAt,
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
    if (!response.body) {
      await finishInterruptedStream(sessionId, generation);
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let terminalObserved = false;
    let transportInterrupted = false;

    try {
      while (true) {
        const { done, value } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        const frames = buffer.split(/\r?\n\r?\n/u);
        buffer = frames.pop() ?? '';
        for (const frame of frames) {
          terminalObserved = dispatchSseFrame(frame, sessionId, generation) || terminalObserved;
        }
        if (done) break;
      }
      if (buffer.trim()) {
        terminalObserved = dispatchSseFrame(buffer, sessionId, generation) || terminalObserved;
      }
    } catch {
      transportInterrupted = true;
    } finally {
      if (generation === streamGeneration) {
        streaming.value = false;
        await reconcileAfterStream(sessionId);
        if (hasPendingSubmission(sessionId)) {
          settlePendingSubmission('uncertain');
          publishError(outcomeUncertainError());
        } else if (transportInterrupted || !terminalObserved) {
          publishError(streamInterruptedError());
        }
      }
    }
  }

  async function finishInterruptedStream(sessionId: string, generation: number): Promise<void> {
    if (generation !== streamGeneration) return;
    streaming.value = false;
    await reconcileAfterStream(sessionId);
    if (hasPendingSubmission(sessionId)) {
      settlePendingSubmission('uncertain');
      publishError(outcomeUncertainError());
      return;
    }
    publishError(streamInterruptedError());
  }

  function dispatchSseFrame(frame: string, sessionId: string, generation: number): boolean {
    if (generation !== streamGeneration || session.value?.sessionId !== sessionId) return false;
    let event = 'message';
    let id: string | undefined;
    const dataLines: string[] = [];

    for (const line of frame.split(/\r?\n/u)) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('id:')) id = line.slice(3).trim();
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
    }
    if (dataLines.length === 0) return false;

    const data = parseEventData(dataLines.join('\n'));
    if (event === 'stream.error') {
      publishError(streamError(data));
      return true;
    }
    if (event === 'session.status.idle') {
      if (session.value) session.value.state = 'idle';
      return false;
    }
    if (event === 'stream.end') return true;

    mergeRuntimeEvent({ id, event, data });
    if (event === 'user.message') {
      confirmSubmission(data);
      reconcileAcceptedControl(data);
    }
    return false;
  }

  function mergeRuntimeEvent(envelope: RuntimeEventEnvelope): void {
    const entryId = readString(envelope.data.entryId);
    if (entryId && isPersistentEvent(envelope.event)) {
      const index = events.value.findIndex(
        (item) => item.event === envelope.event && readString(item.data.entryId) === entryId,
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

  function createPendingSubmission(
    sessionId: string,
    message: string,
    fileIds: string[],
  ): PendingSubmission {
    let resolveConfirmation: (outcome: SubmissionOutcome) => void = () => undefined;
    const confirmation = new Promise<SubmissionOutcome>((resolve) => {
      resolveConfirmation = resolve;
    });
    pendingSubmission = {
      sessionId,
      message,
      fileIds: [...fileIds],
      knownEntryIds: new Set(
        events.value.map((event) => readString(event.data.entryId)).filter(Boolean),
      ),
      confirmation,
      resolve: resolveConfirmation,
      settled: false,
    };
    return pendingSubmission;
  }

  function confirmSubmission(data: RuntimeEventData): void {
    const pending = pendingSubmission;
    if (!pending || !matchesPendingSubmission(data, pending)) return;
    settlePendingSubmission('confirmed');
  }

  function confirmSubmissionFromHistory(): void {
    const pending = pendingSubmission;
    if (!pending) return;
    const confirmed = events.value.some((event) => {
      const entryId = readString(event.data.entryId);
      return event.event === 'user.message'
        && !pending.knownEntryIds.has(entryId)
        && matchesPendingSubmission(event.data, pending);
    });
    if (confirmed) settlePendingSubmission('confirmed');
  }

  function matchesPendingSubmission(
    data: RuntimeEventData,
    pending: PendingSubmission,
  ): boolean {
    return readString(data.message) === pending.message
      && arraysEqual(readStringArray(data.fileIds), pending.fileIds);
  }

  function hasPendingSubmission(sessionId: string): boolean {
    return pendingSubmission?.sessionId === sessionId && !pendingSubmission.settled;
  }

  function settlePendingSubmission(outcome: SubmissionOutcome): void {
    const pending = pendingSubmission;
    if (!pending || pending.settled) return;
    pending.settled = true;
    pending.resolve(outcome);
    pendingSubmission = null;
  }

  function reconcileAcceptedControlsFromHistory(): void {
    confirmSubmissionFromHistory();
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
    if (session.value?.sessionId !== sessionId) return;
    const streamErrorMessage = lastError.value;
    const streamErrorCode = lastErrorCode.value;
    try {
      await getSession(sessionId);
      await loadHistory();
    } catch {
      // 保留当前投影和原始流错误，由结果确认状态决定是否可以清空草稿。
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
        headers: mergeRequestHeaders({
          Accept: 'application/json',
          'Accept-Language': navigator.language.startsWith('zh') ? 'zh-CN' : 'en-US',
          'X-HW-ID': callerId,
        }, init.headers),
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
    const sessionId = session.value?.sessionId;
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
  return { id: String(data.entrySeq ?? ''), event, data: normalized };
}

function deduplicatePersistentEvents(events: RuntimeEventEnvelope[]): RuntimeEventEnvelope[] {
  const seen = new Set<string>();
  return events.filter((event) => {
    const key = `${event.event}:${readString(event.data.entryId)}`;
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
  return outcomeUncertain
    ? outcomeUncertainError()
    : new RuntimeApiError({
      message: '暂时无法连接服务，请检查网络后重试。',
      code: 'NETWORK_ERROR',
    });
}

function outcomeUncertainError(): RuntimeApiError {
  return new RuntimeApiError({
    message: '请求可能已被服务接受，但持久化历史中尚未确认。已保留草稿；请先刷新会话，不要重复提交。',
    code: 'OUTCOME_UNCERTAIN',
    outcomeUncertain: true,
  });
}

function streamInterruptedError(): RuntimeApiError {
  return new RuntimeApiError({
    message: '消息已确认，但执行流已中断；执行可能仍在继续。请刷新会话查看最新结果，不要重复提交。',
    code: 'STREAM_INTERRUPTED',
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

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === 'string');
}

function arraysEqual(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/u, '');
}

function mergeRequestHeaders(...sources: Array<HeadersInit | undefined>): Headers {
  const merged = new Headers();
  sources.forEach((source) => {
    if (!source) return;
    new Headers(source).forEach((value, key) => merged.set(key, value));
  });
  return merged;
}
