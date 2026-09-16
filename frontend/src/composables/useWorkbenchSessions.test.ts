import { createRenderer, nextTick, ref } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useWorkbenchSessions } from './useWorkbenchSessions';
import { SessionIndexRepository } from '../runtime/sessionIndex';
import { memoryStorage } from '../runtime/storageFixture';
import { eventFixture, idleFixture, jsonResponse, sessionFixture, userFixture } from '../runtime/fixtures';
import type { RuntimeEnvironmentSource } from '../runtime/runtimeConfiguration';
const renderer = createRenderer<object, object>({ patchProp() {}, insert() {}, remove() {}, createElement: () => ({}),
  createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null });
const apps: { unmount(): void }[] = [];
const agent = { id: 'agent-a', name: 'Agent', description: '', category: '' };
let storage: Storage;
let fetcher: ReturnType<typeof vi.fn>;
function index() { return new SessionIndexRepository({ apiBase: '', agentId: agent.id, callerId: 'campusclaw-web' }, () => storage, vi.fn()); }
function seed(id = 'session-a') {
  index().recordActivity({ sessionId: id, agentId: agent.id, title: '恢复标题', agentName: 'Agent', lastActivityAt: new Date().toISOString() });
  index().select(id);
}
function mount(environment?: RuntimeEnvironmentSource) {
  let workbench!: ReturnType<typeof useWorkbenchSessions>;
  const app = renderer.createApp({ setup() { workbench = useWorkbenchSessions(agent, environment); return () => null; } });
  apps.push(app); app.mount({}); return { workbench, app };
}
beforeEach(() => {
  storage = memoryStorage();
  vi.stubGlobal('window', { localStorage: storage });
  vi.stubGlobal('navigator', { language: 'zh-CN' });
  vi.stubGlobal('document', { visibilityState: 'visible', addEventListener: vi.fn(), removeEventListener: vi.fn() });
  fetcher = vi.fn(async (input: string) => {
    if (input.includes('/events?')) return jsonResponse({ events: [userFixture(), idleFixture()], nextPage: null });
    if (input.endsWith('/models')) return jsonResponse({ currentModelId: 'model-primary', models: ['model-primary'] });
    const sessionId = /\/sessions\/([^/?]+)/u.exec(input)?.[1] ?? 'session-a';
    return jsonResponse(sessionFixture({ sessionId }), { ETag: '"v1"' });
  });
  vi.stubGlobal('fetch', fetcher);
});
afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); vi.useRealTimers(); vi.unstubAllGlobals(); });
describe('workbench restore and navigation lifecycle', () => {
  it('does not create a session on an empty start and restores ID/history on refresh and reopen', async () => {
    const first = mount();
    await nextTick(); expect(fetcher).not.toHaveBeenCalled();
    await first.workbench.createSession();
    expect(index().read().activeSessionId).toBe('session-a');
    first.workbench.message.value = 'unsent draft';
    first.app.unmount(); apps.splice(apps.indexOf(first.app), 1);
    fetcher.mockClear();
    const second = mount().workbench;
    await vi.waitFor(() => expect(second.restoring.value).toBe(false));
    expect(second.runtime.session.value?.sessionId).toBe('session-a');
    expect(second.runtime.events.value.map((event) => event.eventId)).toEqual(['root-a', 'idle-root-a-done']);
    expect(second.message.value).toBe('');
    expect(fetcher.mock.calls.every((call) => !((call as unknown[])[1] as RequestInit)?.method)).toBe(true);
  });
  it('keeps entries and clears old history when a restore is unavailable, then allows explicit removal', async () => {
    seed();
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ resCode: 'NOT_FOUND', resMsg: 'missing' }), { status: 404 }));
    const w = mount().workbench;
    await vi.waitFor(() => expect(w.restoring.value).toBe(false));
    expect(w.runtime.session.value).toBeNull();
    expect(w.threads.value.map((entry) => entry.sessionId)).toEqual(['session-a']);
    expect(w.unavailable.value).toEqual(['session-a']);
    w.forgetSession('session-a');
    expect(index().read().entries).toEqual([]);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('does not admit a new message after incomplete restoration and preserves a newer selection', async () => {
    const w = mount().workbench;
    fetcher.mockImplementationOnce(async () => jsonResponse(sessionFixture(), { ETag: '"v1"' }));
    fetcher.mockRejectedValueOnce(new Error('offline'));
    await w.resumeSession('session-a');
    expect(w.restoreError.value).toBeTruthy();
    expect(w.canSend.value).toBe(false);
    let finish!: (response: Response) => void;
    fetcher.mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve; }));
    const stale = w.resumeSession('session-a');
    await w.resumeSession('session-b');
    finish(jsonResponse(sessionFixture(), { ETag: '"old"' }));
    await stale;
    expect(w.activeSessionId.value).toBe('session-b');
    expect(w.runtime.session.value?.sessionId).toBe('session-b');
    expect(w.restoreError.value).toBe('');
  });
  it('polls running restored sessions at most six times and stops polling after navigation', async () => {
    vi.useFakeTimers(); seed();
    fetcher.mockImplementation(async (input: string) => input.includes('/events?') ? jsonResponse({ events: [userFixture()], nextPage: null })
      : input.endsWith('/models') ? jsonResponse({ currentModelId: 'model-primary', models: ['model-primary'] })
      : jsonResponse(sessionFixture({ state: 'running' }), { ETag: '"v1"' }));
    const w = mount().workbench;
    await vi.advanceTimersByTimeAsync(50);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(w.recoveryExhausted.value).toBe(true);
    const calls = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetcher).toHaveBeenCalledTimes(calls);
    w.newConversation();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetcher).toHaveBeenCalledTimes(calls);
    expect(index().read().activeSessionId).toBeNull();
  });
  it('retains a successfully created ID even if its first GET fails', async () => {
    const w = mount().workbench;
    fetcher.mockResolvedValueOnce(jsonResponse(sessionFixture())).mockRejectedValueOnce(new Error('read offline'));
    await expect(w.createSession()).rejects.toThrow();
    expect(index().read().activeSessionId).toBe('session-a');
    expect(index().read().entries.map((entry) => entry.sessionId)).toEqual(['session-a']);
    expect(w.canSend.value).toBe(false);
    expect(w.restoreError.value).toContain('未完成');
  });
  it('uses a backend-scoped index and clears the old view synchronously when the environment changes', async () => {
    let connection = { apiBase: '', backendIdentity: 'proxy:http://backend-a', callerId: 'caller-a',
      proxyVersion: 1, proxyKey: 'key-a' };
    const environment: RuntimeEnvironmentSource = {
      ready: ref(false),
      revision: ref(0),
      snapshot: () => ({ ...connection }),
    };
    const scoped = new SessionIndexRepository({ apiBase: connection.backendIdentity, agentId: agent.id,
      callerId: connection.callerId }, () => storage, vi.fn());
    scoped.recordActivity({ sessionId: 'session-a', agentId: agent.id, title: '环境 A', agentName: 'Agent',
      lastActivityAt: new Date().toISOString() });
    scoped.select('session-a');

    const w = mount(environment).workbench;
    await nextTick();
    expect(fetcher).not.toHaveBeenCalled();
    environment.ready.value = true;
    await vi.waitFor(() => expect(w.runtime.session.value?.sessionId).toBe('session-a'));

    connection = { apiBase: '', backendIdentity: 'proxy:http://backend-b', callerId: 'caller-a',
      proxyVersion: 2, proxyKey: 'key-b' };
    environment.revision.value += 1;
    expect(w.runtime.session.value).toBeNull();
    expect(w.threads.value).toEqual([]);
    expect(w.activeSessionId.value).toBeNull();
    expect(scoped.read().activeSessionId).toBe('session-a');
  });
  it('stops background polling while a known confirmation is waiting but still allows manual refresh', async () => {
    vi.useFakeTimers();
    const confirming = [
      userFixture(),
      eventFixture({ eventId: 'plan-a', type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'plan-tool', content: '# Plan' }),
      idleFixture('confirming'),
    ];
    fetcher.mockImplementation(async (input: string) => input.includes('/events?')
      ? jsonResponse({ events: confirming, nextPage: null })
      : input.endsWith('/models')
        ? jsonResponse({ currentModelId: 'model-primary', models: ['model-primary'] })
        : jsonResponse(sessionFixture({ state: 'running', mode: 'plan' }), { ETag: '"v1"' }));
    const w = mount().workbench;
    await w.resumeSession('session-a');
    expect(w.runtime.events.value.map((event) => event.type)).toEqual(['user.message', 'agent.plan', 'session.status_idle']);
    expect(w.runtime.execution.value.confirming).toBe(true);
    const settledCalls = fetcher.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetcher).toHaveBeenCalledTimes(settledCalls);
    await w.refreshState();
    expect(fetcher.mock.calls.length).toBeGreaterThan(settledCalls);
  });
});
