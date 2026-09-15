import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { useRuntimeApi } from './useRuntimeApi';
import { eventFixture, idleFixture, jsonResponse, questionFixture, sessionFixture, streamResponse, userFixture } from '../runtime/fixtures';
import { memoryStorage } from '../runtime/storageFixture';
import { SessionIndexRepository } from '../runtime/sessionIndex';
import { freezeInvocation } from '../runtime/commands';
import type { RuntimeEvent } from '../types/runtime';
import type { RuntimeEnvironmentSource } from '../runtime/runtimeConfiguration';

function setup(history: RuntimeEvent[] = [], state: 'idle' | 'running' = 'idle') {
  const fetcher = vi.fn<(url: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
  fetcher.mockImplementation(async (input) => {
    const url = String(input);
    if (url.includes('/events?')) return jsonResponse({ events: history, nextPage: null });
    if (url.endsWith('/commands')) return jsonResponse({ commands: [{ name: 'help', kind: 'builtin', description: '介绍' }] });
    if (url.endsWith('/models')) return jsonResponse({ currentModelId: 'query-only', models: ['model-primary'] }, { ETag: '"not-a-session-version"' });
    return jsonResponse(sessionFixture({ state }), { ETag: '"v1"' });
  });
  vi.stubGlobal('fetch', fetcher);
  const runtime = useRuntimeApi();
  runtime.session.value = sessionFixture();
  runtime.etag.value = '"v0"';
  runtime.historyReady.value = true;
  return { runtime, fetcher };
}
function requestAt(fetcher: ReturnType<typeof vi.fn>, index = 0) {
  const init = fetcher.mock.calls[index][1] as RequestInit;
  return { ...init, json: init.body ? JSON.parse(String(init.body)) : undefined, headers: new Headers(init.headers) };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
beforeEach(() => vi.stubGlobal('navigator', { language: 'zh-CN' }));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('Claw Runtime Session Events coordinator', () => {
  it('sends the event wrapper and confirms only the first complete request receipt', async () => {
    const events = [userFixture(), idleFixture()];
    const { runtime, fetcher } = setup(events);
    fetcher.mockResolvedValueOnce(streamResponse(events));
    const submission = await runtime.sendMessage('检查订单');
    await expect(submission.confirmation).resolves.toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(requestAt(fetcher).json).toEqual({ event: { type: 'user.message', content: [{ type: 'text', text: '检查订单' }] } });
    expect(requestAt(fetcher).headers.get('accept')).toBe('text/event-stream');
    expect(runtime.events.value).toEqual(events);
  });
  it('does not infer a lost receipt from identical history, or retry a POST', async () => {
    const { runtime, fetcher } = setup([userFixture()]);
    fetcher.mockResolvedValueOnce(streamResponse([]));
    const submission = await runtime.sendMessage('检查订单');
    expect(await submission.confirmation).toBe('uncertain');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(runtime.receiptUnknown.value).toBe(true);
    expect(runtime.canSend.value).toBe(false);
    expect(runtime.uncertainty.value).toContain('相同文本');
    expect(fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST')).toHaveLength(1);
  });
  it('retains uncertainty on a network failure before headers', async () => {
    const { runtime, fetcher } = setup();
    fetcher.mockRejectedValueOnce(new TypeError('offline'));
    await expect(runtime.sendMessage('检查订单')).rejects.toMatchObject({ code: 'OUTCOME_UNCERTAIN' });
    expect(runtime.receiptUnknown.value).toBe(true);
    expect(runtime.messagePending.value).toBe(false);
  });
  it('blocks a second message before the first receipt and while confirming', async () => {
    const { runtime, fetcher } = setup([userFixture(), idleFixture('confirming')], 'running');
    const delayed = deferred<Response>();
    fetcher.mockReturnValueOnce(delayed.promise);
    const first = runtime.sendMessage('检查订单');
    await expect(runtime.sendMessage('第二条')).rejects.toMatchObject({ code: 'SESSION_BUSY' });
    delayed.resolve(streamResponse([userFixture(), idleFixture('confirming')]));
    expect(await (await first).confirmation).toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(runtime.canSend.value).toBe(false);
    expect(runtime.messagePending.value).toBe(false);
    expect(runtime.execution.value.confirming).toBe(true);
  });
  it('keeps Models separate from the atomic Session / ETag resource', async () => {
    const { runtime } = setup();
    await runtime.listModels();
    expect(runtime.models.value).toEqual(['model-primary']);
    expect(runtime.session.value?.modelId).toBe('model-primary');
    expect(runtime.etag.value).toBe('"v0"');
  });
  it('renames a running current session with only displayName and its current ETag', async () => {
    const { runtime, fetcher } = setup([], 'running');
    runtime.session.value = sessionFixture({ state: 'running' });
    fetcher.mockResolvedValueOnce(jsonResponse(sessionFixture({ state: 'running', displayName: '新名称' }), { ETag: '"v1"' }));
    await runtime.renameSession('session-a', '新名称');
    expect(requestAt(fetcher).json).toEqual({ displayName: '新名称' });
    expect(requestAt(fetcher).headers.get('if-match')).toBe('"v0"');
    expect(runtime.session.value?.displayName).toBe('新名称');
  });
  it('refreshes a detached rename conflict, preserves 412, and never replays the POST', async () => {
    const { fetcher } = setup();
    const observed = vi.fn();
    const runtime = useRuntimeApi(undefined, undefined, observed);
    runtime.session.value = sessionFixture({ sessionId: 'session-a' });
    runtime.etag.value = '"active"'; runtime.historyReady.value = true;
    fetcher.mockResolvedValueOnce(jsonResponse(sessionFixture({ sessionId: 'session-b', displayName: '原名称' }), { ETag: '"b1"' }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ resCode: 'SESSION_VERSION_MISMATCH', resMsg: 'changed' }), {
        status: 412, headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(jsonResponse(sessionFixture({ sessionId: 'session-b', displayName: '其他页签名称' }), { ETag: '"b2"' }));
    await expect(runtime.renameSession('session-b', '我的草稿')).rejects.toMatchObject({ status: 412,
      code: 'SESSION_VERSION_MISMATCH' });
    const posts = fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST');
    expect(posts).toHaveLength(1);
    expect(requestAt(fetcher, 1).json).toEqual({ displayName: '我的草稿' });
    expect(requestAt(fetcher, 1).headers.get('if-match')).toBe('"b1"');
    expect(observed).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'session-b', displayName: '其他页签名称' }));
    expect(runtime.session.value?.sessionId).toBe('session-a');
  });
  it('freezes detached read and delete requests to their invocation connection without changing the active view', async () => {
    let connection = { apiBase: 'https://backend-a.test', backendIdentity: 'direct:https://backend-a.test', callerId: 'caller-a' };
    const environment: RuntimeEnvironmentSource = {
      ready: ref(true), revision: ref(0), snapshot: () => ({ ...connection }),
    };
    const first = deferred<Response>();
    const second = deferred<Response>();
    const fetcher = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    vi.stubGlobal('fetch', fetcher);
    const runtime = useRuntimeApi(undefined, undefined, undefined, environment);
    runtime.session.value = sessionFixture({ sessionId: 'active-session' });

    const reading = runtime.readNamedSession('detached-session');
    connection = { apiBase: 'https://backend-b.test', backendIdentity: 'direct:https://backend-b.test', callerId: 'caller-b' };
    environment.revision.value += 1;
    first.resolve(jsonResponse(sessionFixture({ sessionId: 'detached-session' }), { ETag: '"a1"' }));
    await expect(reading).resolves.toMatchObject({ sessionId: 'detached-session' });
    expect(String(fetcher.mock.calls[0][0])).toBe('https://backend-a.test/campusclaw-service/v1/sessions/detached-session');
    expect(requestAt(fetcher, 0).headers.get('x-hw-id')).toBe('caller-a');
    expect(runtime.session.value?.sessionId).toBe('active-session');

    const deleting = runtime.deleteNamedSession('detached-session');
    second.resolve(new Response(null, { status: 204 }));
    await deleting;
    expect(String(fetcher.mock.calls[1][0])).toBe('https://backend-b.test/campusclaw-service/v1/sessions/detached-session');
    expect(requestAt(fetcher, 1).headers.get('x-hw-id')).toBe('caller-b');
    expect(runtime.session.value?.sessionId).toBe('active-session');
  });
  it('loads integer pages and rejects repeated or opaque continuations without replacing visible history', async () => {
    const { runtime, fetcher } = setup();
    fetcher.mockResolvedValueOnce(jsonResponse({ events: [userFixture()], nextPage: 2 }))
      .mockResolvedValueOnce(jsonResponse({ events: [idleFixture()], nextPage: null }));
    await runtime.loadHistory();
    expect(String(fetcher.mock.calls[1][0])).toContain('page=2');
    fetcher.mockResolvedValueOnce(jsonResponse({ events: [], nextPage: 1 }));
    await expect(runtime.loadHistory()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    expect(runtime.events.value).toHaveLength(2);
  });
  it('discards delayed Session / catalog / history from the previous view', async () => {
    const { runtime, fetcher } = setup();
    const delayed = deferred<Response>();
    fetcher.mockReturnValueOnce(delayed.promise);
    const reading = runtime.getSession();
    runtime.clearSessionView(); runtime.session.value = sessionFixture({ sessionId: 'session-b' });
    delayed.resolve(jsonResponse(sessionFixture(), { ETag: '"old"' }));
    await expect(reading).rejects.toThrow();
    expect(runtime.session.value.sessionId).toBe('session-b');
    expect(runtime.etag.value).toBe('');
    expect(runtime.lastError.value).toBe('');
  });
  it('cancels detached readers and does not apply their final recovery to a new Session', async () => {
    const { runtime, fetcher } = setup();
    let cancelled = false;
    fetcher.mockResolvedValueOnce(new Response(new ReadableStream({ cancel() { cancelled = true; } }), { headers: { 'Content-Type': 'text/event-stream' } }));
    const submission = await runtime.sendMessage('检查订单');
    runtime.clearSessionView();
    expect(await submission.confirmation).toBe('uncertain');
    expect(cancelled).toBe(true);
    expect(runtime.session.value).toBeNull();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('freezes interrupt target and awaits actual terminal, not its receipt', async () => {
    const { runtime, fetcher } = setup([], 'running');
    runtime.session.value = sessionFixture({ state: 'running' });
    runtime.events.value = [userFixture()];
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start(controller) { stream = controller; } });
    fetcher.mockResolvedValueOnce(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }));
    const submission = await runtime.interrupt({ 'access-token': 'fixture-stop' });
    stream.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(eventFixture({ eventId: 'stop', type: 'user.interrupt', targetEventId: 'root-a' }))}\n\n`));
    expect(await submission.confirmation).toBe('confirmed');
    expect(runtime.stopping.value).toBe(true);
    expect(runtime.session.value.state).toBe('running');
    expect(requestAt(fetcher).json).toEqual({ event: { type: 'user.interrupt', targetEventId: 'root-a' } });
    runtime.clearSessionView();
  });
  it('sends each confirmation credential snapshot independently, omits allow denyMessage', async () => {
    const call = eventFixture({ eventId: 'call', type: 'agent.tool_call', toolCallId: 't', toolName: 'Read', arguments: {}, requiresConfirmation: true, sourceEventId: 'root-a' });
    const receipt = eventFixture({ eventId: 'confirm', type: 'user.tool_confirmation', toolCallId: 't', result: 'allow' });
    const { runtime, fetcher } = setup([userFixture(), call, receipt, idleFixture()]);
    runtime.session.value = sessionFixture({ state: 'running' });
    runtime.events.value = [userFixture(), call, idleFixture('confirming')];
    fetcher.mockResolvedValueOnce(streamResponse([receipt, idleFixture()]));
    const custom = new Headers({ 'access-token': 'fixture-confirm', 'If-Match': 'unexpected' });
    const submission = await runtime.confirmTool('t', 'allow', '', custom);
    expect(await submission.confirmation).toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(requestAt(fetcher).json).toEqual({ event: { type: 'user.tool_confirmation', toolCallId: 't', result: 'allow' } });
    expect(requestAt(fetcher).headers.get('access-token')).toBe('fixture-confirm');
    expect(requestAt(fetcher).headers.has('if-match')).toBe(false);
    expect(requestAt(fetcher, 1).headers.has('access-token')).toBe(false);
  });
  it('refreshes on 412 and never repeats the mutation', async () => {
    const { runtime, fetcher } = setup();
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ resCode: 'SESSION_VERSION_MISMATCH', resMsg: 'changed' }), { status: 412 }));
    await expect(runtime.changeThinking(false)).rejects.toMatchObject({ code: 'SESSION_VERSION_MISMATCH' });
    expect(runtime.session.value?.thinking).toBe(true);
    expect(requestAt(fetcher).headers.get('if-match')).toBe('"v0"');
    expect(fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST')).toHaveLength(1);
    expect(String(fetcher.mock.calls[0][0])).toMatch(/\/sessions\/session-a$/u);
    expect(requestAt(fetcher).json).toEqual({ thinking: false });
  });
  it('submits an exact plan decision and keeps review separate from the message draft', async () => {
    const plan = eventFixture({ eventId: 'plan-a', type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'exit-a', content: '# 方案\n\n执行步骤' });
    const receipt = eventFixture({ eventId: 'review-a', type: 'user.plan_confirmation', targetEventId: 'plan-a', result: 'approve' });
    const mode = eventFixture({ eventId: 'mode-a', type: 'session.mode_changed', previousMode: 'plan', mode: 'build', reason: 'planApproved',
      sourceEventId: 'root-a', confirmationEventId: 'review-a' });
    const { runtime, fetcher } = setup([userFixture(), plan, idleFixture('confirming'), receipt, mode, idleFixture()], 'running');
    runtime.session.value = sessionFixture({ state: 'running', mode: 'plan' });
    runtime.events.value = [userFixture(), plan, idleFixture('confirming')];
    fetcher.mockResolvedValueOnce(streamResponse([receipt, mode, idleFixture()]));
    const submission = await runtime.confirmPlan('plan-a', 'approve', '', { 'access-token': 'fixture-plan' });
    expect(await submission.confirmation).toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(requestAt(fetcher).json).toEqual({ event: { type: 'user.plan_confirmation', targetEventId: 'plan-a', result: 'approve' } });
    expect(runtime.events.value.some((event) => event.type === 'user.plan_confirmation')).toBe(true);
  });
  it('submits one complete question answer and confirms only its exact receipt', async () => {
    const question = questionFixture();
    const waiting = [userFixture(), question, idleFixture('confirming')];
    const answer = eventFixture({ eventId: 'question-answer', type: 'user.question_answer', targetEventId: question.eventId,
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '正文' }] }) as Extract<
        RuntimeEvent, { type: 'user.question_answer' }>;
    const { runtime, fetcher } = setup([...waiting, answer, idleFixture()], 'running');
    runtime.session.value = sessionFixture({ state: 'running' });
    runtime.events.value = waiting;
    fetcher.mockResolvedValueOnce(streamResponse([answer, idleFixture()]));
    const submission = await runtime.answerQuestion(question.eventId, answer.answers, { 'access-token': 'fixture-question' });
    await expect(submission.confirmation).resolves.toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(requestAt(fetcher).json).toEqual({ event: { type: 'user.question_answer', targetEventId: question.eventId,
      answers: answer.answers } });
    expect(runtime.events.value).toContainEqual(answer);
  });
  it('locks duplicate answer writes and never retries a missing receipt', async () => {
    const question = questionFixture();
    const waiting = [userFixture(), question, idleFixture('confirming')];
    const { runtime, fetcher } = setup(waiting, 'running');
    runtime.session.value = sessionFixture({ state: 'running' }); runtime.events.value = waiting;
    const delayed = deferred<Response>();
    fetcher.mockReturnValueOnce(delayed.promise);
    const answers = [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '正文' }];
    const first = runtime.answerQuestion(question.eventId, answers);
    await expect(runtime.answerQuestion(question.eventId, answers)).rejects.toMatchObject({ code: 'SESSION_BUSY' });
    delayed.resolve(streamResponse([]));
    expect(await (await first).confirmation).toBe('uncertain');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST')).toHaveLength(1);
    expect(runtime.receiptUnknown.value).toBe(true);
  });
  it('keeps a previously ready question read-only after failed recovery until a complete retry', async () => {
    const question = questionFixture();
    const waiting = [userFixture(), question, idleFixture('confirming')];
    const { runtime, fetcher } = setup(waiting, 'running');
    runtime.session.value = sessionFixture({ state: 'running' }); runtime.events.value = waiting;
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ resCode: 'TEMPORARY', resMsg: 'unavailable' }), { status: 503 }));
    await expect(runtime.recover()).rejects.toThrow();
    expect(runtime.historyReady.value).toBe(false);
    const answers = [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '正文' }];
    expect(() => runtime.answerQuestion(question.eventId, answers)).toThrowError();
    expect(fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST')).toHaveLength(0);
    await runtime.recover();
    expect(runtime.historyReady.value).toBe(true);
    const receipt = eventFixture({ eventId: 'question-answer', type: 'user.question_answer', targetEventId: question.eventId,
      answers });
    fetcher.mockResolvedValueOnce(streamResponse([receipt, idleFixture()]));
    await (await runtime.answerQuestion(question.eventId, answers)).confirmation;
    expect(fetcher.mock.calls.filter((call) => (call[1] as RequestInit).method === 'POST')).toHaveLength(1);
  });
  it('coalesces catalog reads and keeps Builtin results outside event history', async () => {
    const { runtime, fetcher } = setup();
    await Promise.all([runtime.listCommands(), runtime.listCommands()]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    fetcher.mockResolvedValueOnce(jsonResponse({ displayName: '<script>x</script>', description: [], userCases: [] }));
    await runtime.executeCommand(freezeInvocation(runtime.commands.value[0], '/help'));
    expect(runtime.commandResult.value).toHaveProperty('kind', 'agentGuide');
    expect(runtime.events.value).toEqual([]);
    expect(String(fetcher.mock.calls[1][0])).toMatch(/\/command$/u);
    expect(requestAt(fetcher, 1).headers.get('accept')).toBe('application/json');
  });
  it('uses Skill SSE receipt and no Builtin result', async () => {
    const { runtime, fetcher } = setup([userFixture('root-a', '/skill:inspect'), idleFixture()]);
    runtime.catalogStatus.value = 'ready';
    fetcher.mockResolvedValueOnce(streamResponse([userFixture('root-a', '/skill:inspect'), idleFixture()]));
    const invocation = freezeInvocation({ name: 'skill:inspect', kind: 'skill', description: '巡检' }, '/skill:inspect');
    expect(await runtime.executeCommand(invocation)).toBe('confirmed');
    expect(runtime.commandResult.value).toBeNull();
    expect(requestAt(fetcher).json).toEqual({ name: 'skill:inspect' });
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
  });
  it('does not use a previous confirming idle to complete an interrupted continuation', async () => {
    const call = eventFixture({ eventId: 'call', type: 'agent.tool_call', toolCallId: 't', toolName: 'Read', arguments: {}, requiresConfirmation: true, sourceEventId: 'root-a' });
    const receipt = eventFixture({ eventId: 'confirmation', type: 'user.tool_confirmation', toolCallId: 't', result: 'allow' });
    const oldHistory = [userFixture(), call, idleFixture('confirming'), receipt];
    const { runtime, fetcher } = setup(oldHistory, 'running');
    runtime.events.value = oldHistory.slice(0, -1);
    runtime.session.value = sessionFixture({ state: 'running' });
    fetcher.mockResolvedValueOnce(streamResponse([receipt]));
    const submission = await runtime.confirmTool('t', 'allow', '');
    expect(await submission.confirmation).toBe('confirmed');
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(runtime.uncertainty.value).toContain('观察流中断');
    expect(runtime.execution.value.confirming).toBe(false);
    expect(runtime.canSend.value).toBe(false);
  });
  it('reloads a catalog invalidated while its old GET was in flight', async () => {
    const { runtime, fetcher } = setup();
    const delayed = deferred<Response>();
    fetcher.mockReturnValueOnce(delayed.promise);
    const reading = runtime.listCommands();
    runtime.invalidateCatalog();
    delayed.resolve(jsonResponse({ commands: [] }));
    await reading;
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(runtime.catalogStatus.value).toBe('ready');
    expect(runtime.commands.value[0].name).toBe('help');
  });
  it('blocks idle resources without a matching root terminal, including manual acknowledgement', async () => {
    const { runtime, fetcher } = setup([userFixture()]);
    fetcher.mockResolvedValueOnce(streamResponse([userFixture()]));
    await (await runtime.sendMessage('检查订单')).confirmation;
    await vi.waitFor(() => expect(runtime.streaming.value || runtime.recovering.value).toBe(false));
    expect(runtime.canSend.value).toBe(false);
    runtime.acknowledgeUnknown();
    expect(runtime.canSend.value).toBe(false);
    expect(runtime.canStop.value).toBe(false);
  });
  it('restores accepted request identities, waits for the correct terminal and never replays POST', async () => {
    const { fetcher } = setup([userFixture()]);
    const store = new SessionIndexRepository({ apiBase: '', agentId: 'a', callerId: 'c' }, () => storage, vi.fn());
    const storage = memoryStorage();
    store.recordActivity({ sessionId: 'session-a', agentId: 'a', title: '历史', agentName: 'A', lastActivityAt: new Date().toISOString() });
    store.put('session-a', { id: 'pending', kind: 'message', rootId: 'root-a', receiptId: 'root-a' });
    const runtime = useRuntimeApi(store);
    await runtime.getSession('session-a'); await runtime.recover();
    expect(runtime.canSend.value).toBe(false);
    expect(store.list('session-a')).toHaveLength(1);
    fetcher.mockImplementation(async (input) => String(input).includes('/events?')
      ? jsonResponse({ events: [userFixture(), idleFixture()], nextPage: null })
      : jsonResponse(sessionFixture(), { ETag: '"v2"' }));
    await runtime.recover();
    expect(runtime.canSend.value).toBe(true);
    expect(store.list('session-a')).toEqual([]);
    expect(fetcher.mock.calls.every((call) => !call[1]?.method || call[1]?.method === 'GET')).toBe(true);
  });
  it('writes a pending marker before POST and retains it if a view detaches before receipt', async () => {
    const { fetcher } = setup();
    const marks = new Map();
    const store = { list: () => [], put: vi.fn((_id, value) => { marks.set(value.id, value); }), remove: vi.fn((_id, id) => { marks.delete(id); }) };
    const runtime = useRuntimeApi(store);
    runtime.session.value = sessionFixture(); runtime.historyReady.value = true;
    fetcher.mockImplementationOnce(async () => {
      expect(marks.size).toBe(1);
      expect([...marks.values()][0]).toEqual({ id: expect.any(String), kind: 'message' });
      return new Response(new ReadableStream(), { headers: { 'Content-Type': 'text/event-stream' } });
    });
    const submission = await runtime.sendMessage('秘密正文', [], { 'access-token': 'synthetic-private' });
    runtime.clearSessionView();
    expect(await submission.confirmation).toBe('uncertain');
    expect(marks.size).toBe(1);
    expect(JSON.stringify([...marks.values()])).not.toMatch(/秘密|synthetic-private/);
  });

});
