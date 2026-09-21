import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import type { CommandInvocation } from '../runtime/commands';
import type { DeveloperSettingsController, PrepareWriteResult } from '../types/developerSettings';
import type { SubmissionOutcome } from '../types/runtime';
import { useWorkbenchWriteActions } from './useWorkbenchWriteActions';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function createHarness(development = true) {
  const token = deferred<PrepareWriteResult>();
  const prepareWrite = vi.fn(() => token.promise);
  const settings = {
    prepareWrite,
    cancelToken: vi.fn(),
  } as unknown as DeveloperSettingsController;
  const session = ref({
    sessionId: 'session-a', agentId: 'agent', displayName: null, modelId: 'model', state: 'running' as const,
    thinking: false, mode: 'plan' as const, createdAt: '2026-09-19T00:00:00Z',
  });
  const execution = ref({
    rootId: 'root-a', confirming: true, terminal: undefined, interruptPending: false,
    pendingTool: { eventId: 'tool-event', type: 'agent.tool_call' as const, sourceEventId: 'root-a',
      toolCallId: 'tool-a', toolName: 'Read', arguments: {}, requiresConfirmation: true },
    pendingPlan: { eventId: 'plan-event', type: 'agent.plan' as const, sourceEventId: 'root-a',
      toolCallId: 'plan-tool', content: 'plan' },
    pendingQuestion: { eventId: 'question-event', type: 'agent.question' as const, sourceEventId: 'root-a',
      toolCallId: 'question-tool', questions: [] },
  });
  const sendMessage = vi.fn().mockResolvedValue({ confirmation: Promise.resolve('confirmed') });
  const executeCommand = vi.fn().mockResolvedValue('confirmed');
  const interrupt = vi.fn().mockResolvedValue({ confirmation: Promise.resolve('confirmed') });
  const confirmTool = vi.fn().mockResolvedValue({ confirmation: Promise.resolve('confirmed') });
  const confirmPlan = vi.fn().mockResolvedValue({ confirmation: Promise.resolve('confirmed') });
  const answerQuestion = vi.fn().mockResolvedValue({ confirmation: Promise.resolve('confirmed') });
  const runtime = {
    session,
    execution,
    canStop: ref(true),
    historyReady: ref(true),
    stopping: ref(false),
    controlPending: ref(false),
    recovering: ref(false),
    receiptUnknown: ref(false),
    uncertainty: ref(''),
    catalogStatus: ref<'stale' | 'loading' | 'ready' | 'error'>('ready'),
    commands: ref([{ name: 'skill:deploy', kind: 'skill' as const, description: 'Deploy' }]),
    sendMessage,
    executeCommand,
    interrupt,
    confirmTool,
    confirmPlan,
    answerQuestion,
  };
  const message = ref('保留这条消息');
  const canSend = ref(true);
  const touchCurrentThread = vi.fn();
  const actions = useWorkbenchWriteActions({
    development,
    settings,
    runtime: runtime as unknown as Parameters<typeof useWorkbenchWriteActions>[0]['runtime'],
    canSend,
    message,
    touchCurrentThread,
    controlLocked: () => false,
    runControl: async (action) => {
      try { await action(); return true; } catch { return false; }
    },
  });
  return {
    actions,
    token,
    prepareWrite,
    settings,
    runtime,
    message,
    canSend,
    touchCurrentThread,
    calls: { sendMessage, executeCommand, interrupt, confirmTool, confirmPlan, answerQuestion },
  };
}

const command: CommandInvocation = Object.freeze({
  request: Object.freeze({ name: 'skill:deploy' }),
  startedAt: '2026-09-19T00:00:00Z',
  executionMode: 'skillEvents',
});

function prepared(token = 'fresh-token'): PrepareWriteResult {
  return { ok: true, headers: new Headers({ 'access-token': token }), isCurrent: () => true };
}

describe('workbench write actions', () => {
  it('keeps the production write path unchanged and does not call the development authenticator', async () => {
    const harness = createHarness(false);
    await harness.actions.submit();

    expect(harness.prepareWrite).not.toHaveBeenCalled();
    expect(harness.calls.sendMessage).toHaveBeenCalledOnce();
    expect(harness.calls.sendMessage.mock.calls[0][2]).toBeUndefined();
  });

  it.each([
    ['message', (h: ReturnType<typeof createHarness>) => h.actions.submit(), 'sendMessage'],
    ['command', (h: ReturnType<typeof createHarness>) => h.actions.executeCommand(command), 'executeCommand'],
    ['stop', (h: ReturnType<typeof createHarness>) => h.actions.stop(), 'interrupt'],
    ['tool decision', (h: ReturnType<typeof createHarness>) => h.actions.decideTool('allow', ''), 'confirmTool'],
    ['plan decision', (h: ReturnType<typeof createHarness>) => h.actions.decidePlan('plan-event', 'approve', ''), 'confirmPlan'],
    ['question answer', (h: ReturnType<typeof createHarness>) => h.actions.answerQuestion('question-event', []), 'answerQuestion'],
  ] as const)('refreshes and freezes headers before dispatching one %s request', async (_name, invoke, method) => {
    const harness = createHarness();
    const pending = invoke(harness);

    expect(harness.actions.preparing.value).toBe(true);
    expect(harness.prepareWrite).toHaveBeenCalledOnce();
    expect(harness.calls[method]).not.toHaveBeenCalled();

    harness.token.resolve(prepared());
    await pending;

    expect(harness.calls[method]).toHaveBeenCalledOnce();
    const headers = harness.calls[method].mock.calls[0].at(-1) as Headers;
    expect(headers.get('access-token')).toBe('fresh-token');
    expect(harness.actions.pending.value).toBe(false);
  });

  it('keeps the send button in its send state while preparation is pending and rejects duplicate clicks', async () => {
    const harness = createHarness();
    const first = harness.actions.submit();
    const duplicate = harness.actions.submit();

    expect(harness.actions.messageProgress.value).toBe('正在发送…');
    expect(harness.actions.messageSubmitting.value).toBe(false);
    expect(harness.prepareWrite).toHaveBeenCalledOnce();
    harness.token.resolve(prepared());
    await Promise.all([first, duplicate]);

    expect(harness.calls.sendMessage).toHaveBeenCalledOnce();
    expect(harness.touchCurrentThread).toHaveBeenCalledWith('保留这条消息');
    expect(harness.message.value).toBe('');
  });

  it('distinguishes stop preparation from other control locks', async () => {
    const tool = createHarness();
    const toolPending = tool.actions.decideTool('allow', '');
    expect(tool.actions.controlPending.value).toBe(true);
    expect(tool.actions.stopPending.value).toBe(false);
    tool.token.resolve(prepared());
    await toolPending;

    const stop = createHarness();
    const stopPending = stop.actions.stop();
    expect(stop.actions.stopPending.value).toBe(true);
    stop.token.resolve(prepared());
    await stopPending;
  });

  it('keeps the draft and performs no business request when authentication fails', async () => {
    const harness = createHarness();
    const pending = harness.actions.submit();
    harness.token.resolve({ ok: false, reason: 'authentication', message: 'upstream unavailable' });

    await pending;

    expect(harness.calls.sendMessage).not.toHaveBeenCalled();
    expect(harness.message.value).toBe('保留这条消息');
    expect(harness.actions.notice.value).toBe('认证暂不可用，消息未发送，请重试。');
  });

  it('does not dispatch a stale request after session A to B to A navigation', async () => {
    const harness = createHarness();
    const pending = harness.actions.submit();
    harness.runtime.session.value = { ...harness.runtime.session.value, sessionId: 'session-b' };
    harness.actions.cancel();
    harness.runtime.session.value = { ...harness.runtime.session.value, sessionId: 'session-a' };
    harness.token.resolve(prepared('late-token'));

    await pending;

    expect(harness.calls.sendMessage).not.toHaveBeenCalled();
    expect(harness.settings.cancelToken).toHaveBeenCalledOnce();
    expect(harness.message.value).toBe('保留这条消息');
  });

  it('rechecks a control target after refresh and never retries an already dispatched request', async () => {
    const harness = createHarness();
    const stale = harness.actions.decidePlan('plan-event', 'approve', '');
    harness.runtime.execution.value = { ...harness.runtime.execution.value,
      pendingPlan: { ...harness.runtime.execution.value.pendingPlan, eventId: 'new-plan-event' } };
    harness.token.resolve(prepared());
    await stale;
    expect(harness.calls.confirmPlan).not.toHaveBeenCalled();

    const current = createHarness();
    current.calls.confirmPlan.mockRejectedValueOnce(new Error('network failed'));
    const attempted = current.actions.decidePlan('plan-event', 'approve', '');
    current.token.resolve(prepared());
    await attempted;
    expect(current.prepareWrite).toHaveBeenCalledOnce();
    expect(current.calls.confirmPlan).toHaveBeenCalledOnce();
  });

  it('does not clear or touch an A draft when an old dispatched confirmation arrives after A to B to A navigation', async () => {
    const harness = createHarness();
    const confirmation = deferred<SubmissionOutcome>();
    harness.calls.sendMessage.mockResolvedValueOnce({ confirmation: confirmation.promise });
    const pending = harness.actions.submit();
    harness.token.resolve(prepared());
    await vi.waitFor(() => expect(harness.calls.sendMessage).toHaveBeenCalledOnce());

    harness.runtime.session.value = { ...harness.runtime.session.value, sessionId: 'session-b' };
    harness.actions.cancel();
    harness.runtime.session.value = { ...harness.runtime.session.value, sessionId: 'session-a' };
    harness.message.value = 'A 中的新草稿';
    confirmation.resolve('confirmed');
    await pending;

    expect(harness.touchCurrentThread).not.toHaveBeenCalled();
    expect(harness.message.value).toBe('A 中的新草稿');
  });

  it('accepts a valid receipt after dispatch changes send and catalog admission state', async () => {
    const messageHarness = createHarness();
    messageHarness.calls.sendMessage.mockImplementationOnce(async () => {
      messageHarness.canSend.value = false;
      messageHarness.runtime.session.value = { ...messageHarness.runtime.session.value, state: 'running' };
      return { confirmation: Promise.resolve('confirmed') };
    });
    const messagePending = messageHarness.actions.submit();
    messageHarness.token.resolve(prepared());
    await messagePending;
    expect(messageHarness.touchCurrentThread).toHaveBeenCalledWith('保留这条消息');
    expect(messageHarness.message.value).toBe('');

    const commandHarness = createHarness();
    commandHarness.calls.executeCommand.mockImplementationOnce(async () => {
      commandHarness.runtime.catalogStatus.value = 'stale';
      return 'confirmed';
    });
    const commandPending = commandHarness.actions.executeCommand(command);
    commandHarness.token.resolve(prepared());
    await commandPending;
    expect(commandHarness.touchCurrentThread).toHaveBeenCalledWith('/skill:deploy');
  });

  it('freezes command and answer payloads before the asynchronous preparation', async () => {
    const commandHarness = createHarness();
    const mutableCommand = {
      request: { name: 'skill:deploy', arguments: 'original' },
      startedAt: '2026-09-19T00:00:00Z',
      executionMode: 'skillEvents',
    } as CommandInvocation;
    const commandPending = commandHarness.actions.executeCommand(mutableCommand);
    (mutableCommand.request as { name: string; arguments?: string }).arguments = 'changed';
    commandHarness.token.resolve(prepared());
    await commandPending;
    expect(commandHarness.calls.executeCommand.mock.calls[0][0].request.arguments).toBe('original');

    const answerHarness = createHarness();
    const answers = [{ id: 'question-1', selected: ['original'] }];
    const answerPending = answerHarness.actions.answerQuestion('question-event', answers);
    answers[0].selected[0] = 'changed';
    answerHarness.token.resolve(prepared());
    await answerPending;
    expect(answerHarness.calls.answerQuestion.mock.calls[0][1]).toEqual([
      { id: 'question-1', selected: ['original'] },
    ]);
  });

  it('rejects stale control admission before login and rechecks the prepared configuration before dispatch', async () => {
    const stale = createHarness();
    stale.runtime.receiptUnknown.value = true;
    await stale.actions.decidePlan('plan-event', 'approve', '');
    expect(stale.prepareWrite).not.toHaveBeenCalled();

    const changed = createHarness();
    const pending = changed.actions.submit();
    changed.token.resolve({ ok: true, headers: new Headers({ 'access-token': 'fresh-token' }), isCurrent: () => false });
    await pending;
    expect(changed.calls.sendMessage).not.toHaveBeenCalled();
    expect(changed.message.value).toBe('保留这条消息');
  });
});
