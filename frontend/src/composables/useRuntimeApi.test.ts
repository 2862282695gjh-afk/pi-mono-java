import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useRuntimeApi } from './useRuntimeApi';
import type { RuntimeEventData, RuntimeSession } from '../types/runtime';

const SESSION_ID = 'session-550e8400e29b41d4a716446655440003';
const AGENT_ID = 'agent-550e8400e29b41d4a716446655440000';

describe('useRuntimeApi HTTP 1.38 contract', () => {
  beforeEach(() => {
    vi.stubGlobal('navigator', { language: 'zh-CN' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('reads lowerCamelCase session and model responses', async () => {
    const session = runtimeSession();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(resultResponse(session))
      .mockResolvedValueOnce(resultResponse(session, { ETag: '"session-v1"' }))
      .mockResolvedValueOnce(resultResponse({ currentModelId: session.modelId, models: [session.modelId] }));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();

    const created = await runtime.createSession(AGENT_ID);

    expect(created.sessionId).toBe(SESSION_ID);
    expect(runtime.session.value?.agentId).toBe(AGENT_ID);
    expect(runtime.session.value?.modelId).toBe('model-primary');
    expect(runtime.etag.value).toBe('"session-v1"');
    expect(runtime.models.value).toEqual(['model-primary']);
  });

  it('writes modelId and reads acceptedAt with exact lowerCamelCase keys', async () => {
    const updated = runtimeSession({ modelId: 'model-secondary' });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(resultResponse(updated, { ETag: '"session-v2"' }))
      .mockResolvedValueOnce(resultResponse({ sessionId: SESSION_ID, acceptedAt: '2026-08-21T12:00:00Z' }));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession({ state: 'running' });
    runtime.etag.value = '"session-v1"';

    await runtime.changeModel('model-secondary');
    const accepted = await runtime.steer('先定位异常');

    expect(requestJson(fetchMock, 0)).toEqual({ modelId: 'model-secondary' });
    expect(accepted.acceptedAt).toBe('2026-08-21T12:00:00Z');
    expect(runtime.acceptedControls.value[0]?.acceptedAt).toBe('2026-08-21T12:00:00Z');
  });

  it('follows every nextPage returned by history pagination', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(resultResponse(historyPage([
        userEvent('entry-1', 1, '第一条'),
      ], 'page-opaque-2')))
      .mockResolvedValueOnce(resultResponse(historyPage([
        userEvent('entry-2', 2, '第二条'),
      ], null)));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession();

    const events = await runtime.loadHistory();

    expect(events.map((event) => event.data.entryId)).toEqual(['entry-1', 'entry-2']);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('page=page-opaque-2');
  });

  it('keeps the submission uncertain when SSE disconnects before history confirmation', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(interruptedSseResponse())
      .mockResolvedValueOnce(resultResponse(runtimeSession({ state: 'running' })))
      .mockResolvedValueOnce(resultResponse(historyPage([], null)));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession();

    const submission = await runtime.sendMessage('检查订单', ['file-1']);

    await expect(submission.confirmation).resolves.toBe('uncertain');
    expect(requestJson(fetchMock, 0)).toEqual({ message: '检查订单', fileIds: ['file-1'] });
    expect(runtime.lastErrorCode.value).toBe('OUTCOME_UNCERTAIN');
    expect(runtime.lastError.value).toContain('不要重复提交');
  });

  it('confirms a submission from the lowerCamelCase user.message SSE event', async () => {
    const persisted = userEvent('entry-sse', 21, '检查订单');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(sseResponse([
        'id: 21',
        'event: user.message',
        `data: ${JSON.stringify(withoutType(persisted))}`,
        '',
        'event: session.status.idle',
        'data: {"status":"idle"}',
        '',
        'event: stream.end',
        'data: {"reason":"completed"}',
        '',
      ].join('\n')))
      .mockResolvedValueOnce(resultResponse(runtimeSession()))
      .mockResolvedValueOnce(resultResponse(historyPage([persisted], null)));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession();

    const submission = await runtime.sendMessage('检查订单');

    await expect(submission.confirmation).resolves.toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value).toBe(false));
    expect(runtime.events.value[0]?.data.entryId).toBe('entry-sse');
    expect(runtime.lastErrorCode.value).toBe('');
  });

  it('confirms the submission from history after SSE disconnects', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(interruptedSseResponse())
      .mockResolvedValueOnce(resultResponse(runtimeSession({ state: 'running' })))
      .mockResolvedValueOnce(resultResponse(historyPage([
        userEvent('entry-confirmed', 22, '检查订单'),
      ], null)));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession();

    const submission = await runtime.sendMessage('检查订单');

    await expect(submission.confirmation).resolves.toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value).toBe(false));
    expect(runtime.lastErrorCode.value).toBe('STREAM_INTERRUPTED');
    expect(runtime.events.value[0]?.data.entryId).toBe('entry-confirmed');
  });

  it('attaches custom headers only to the initial event POST and lets them override defaults', async () => {
    const persisted = userEvent('entry-headers', 23, '检查订单');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(sseResponse([
        'id: 23',
        'event: user.message',
        `data: ${JSON.stringify(withoutType(persisted))}`,
        '',
        'event: stream.end',
        'data: {"reason":"completed"}',
        '',
      ].join('\n')))
      .mockResolvedValueOnce(resultResponse(runtimeSession()))
      .mockResolvedValueOnce(resultResponse(historyPage([persisted], null)));
    vi.stubGlobal('fetch', fetchMock);
    const runtime = useRuntimeApi();
    runtime.session.value = runtimeSession();
    const customHeaders = new Headers({
      Authorization: 'Bearer fixture-token',
      'access-token': 'fixture-access-token',
      'X-HW-ID': 'debugger-override',
    });

    const submission = await runtime.sendMessage('检查订单', [], customHeaders);

    await expect(submission.confirmation).resolves.toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value).toBe(false));
    expect(requestHeaders(fetchMock, 0).get('authorization')).toBe('Bearer fixture-token');
    expect(requestHeaders(fetchMock, 0).get('access-token')).toBe('fixture-access-token');
    expect(requestHeaders(fetchMock, 0).get('x-hw-id')).toBe('debugger-override');
    expect(requestHeaders(fetchMock, 1).get('authorization')).toBeNull();
    expect(requestHeaders(fetchMock, 1).get('access-token')).toBeNull();
    expect(requestHeaders(fetchMock, 1).get('x-hw-id')).toBe('campusclaw-web');
    expect(requestHeaders(fetchMock, 2).get('authorization')).toBeNull();
    expect(requestHeaders(fetchMock, 2).get('access-token')).toBeNull();
  });
});

function runtimeSession(overrides: Partial<RuntimeSession> = {}): RuntimeSession {
  return {
    sessionId: SESSION_ID,
    agentId: AGENT_ID,
    modelId: 'model-primary',
    state: 'idle',
    thinking: true,
    createdAt: '2026-08-21T10:00:00Z',
    updatedAt: '2026-08-21T10:00:00Z',
    ...overrides,
  };
}

function userEvent(entryId: string, entrySeq: number, message: string): RuntimeEventData {
  return {
    type: 'user.message',
    entryId,
    entrySeq,
    message,
    fileIds: [],
    createdAt: '2026-08-21T10:00:00Z',
  };
}

function historyPage(events: RuntimeEventData[], nextPage: string | null) {
  return { events, nextPage };
}

function resultResponse<T>(result: T, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify({ resCode: '0', resMsg: 'success', result }), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function interruptedSseResponse(): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.error(new Error('connection lost'));
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

function sseResponse(body: string): Response {
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

function withoutType(event: RuntimeEventData): RuntimeEventData {
  const data = { ...event };
  delete data.type;
  return data;
}

function requestJson(fetchMock: ReturnType<typeof vi.fn>, callIndex: number): unknown {
  const init = fetchMock.mock.calls[callIndex]?.[1] as RequestInit | undefined;
  return JSON.parse(String(init?.body));
}

function requestHeaders(fetchMock: ReturnType<typeof vi.fn>, callIndex: number): Headers {
  const init = fetchMock.mock.calls[callIndex]?.[1] as RequestInit | undefined;
  return new Headers(init?.headers);
}
