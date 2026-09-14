import { describe, expect, it } from 'vitest';
import { decodeEvent, decodeHistory, matchesReceipt, messageEvent, readEventStream } from './protocol';
import { canReviewPlan, executionState, executionTodos, hasToolConfirmation, mergeEvent, reconcileEvents } from './eventStore';
import { decodeCatalog, decodeCommandResult, filterCommands, freezeInvocation, parseCommand } from './commands';
import { eventFixture, idleFixture, streamResponse, userFixture } from './fixtures';
import type { RuntimeEvent } from '../types/runtime';

const call = eventFixture({ eventId: 'call', type: 'agent.tool_call', sourceEventId: 'root-a', toolCallId: 'tool-a', toolName: 'CallMateTool', arguments: { tool: 'query', args: {} }, requiresConfirmation: true }) as Extract<RuntimeEvent, { type: 'agent.tool_call' }>;
const result = eventFixture({ eventId: 'result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'tool-a', content: [{ type: 'text', text: '' }], isError: false });
const preview = (content: string): RuntimeEvent => ({ eventId: 'answer', type: 'agent.message', phase: 'delta', content, sourceEventId: 'root-a' });
const completed = eventFixture({ ...preview('完整正文'), phase: 'completed' });

describe('Session Events protocol and authoritative store', () => {
  it('accepts completed and preview shapes, text-only tool blocks including empty text', () => {
    [userFixture(), idleFixture(), call, result, preview('增量'), completed].forEach((event) => expect(decodeEvent(event)).toEqual(event));
    expect(decodeEvent({ ...result, content: [{ type: 'text', text: 'one' }, { type: 'text', text: '' }] })).toBeTruthy();
  });
  it.each([
    { ...result, isError: 'false' }, { ...result, isError: true }, { ...result, errorCode: 'TOOL_FAILED' },
    { ...result, content: [] }, { ...result, content: 'text' }, { ...result, content: [{ type: 'image', url: 'https://invalid.test' }] },
    { ...call, requiresConfirmation: undefined }, { ...call, arguments: [] },
    { ...completed, sourceEventId: undefined }, { ...completed, internalSequence: 1 },
    { ...preview('x'), createdAt: '2026-09-08T00:00:00.000Z' }, { ...preview('x'), usage: {} },
    { ...idleFixture('failed') }, { ...idleFixture(), errorCode: 'ERR' },
    { ...userFixture(), createdAt: undefined }, { ...userFixture(), type: 'assistant.message.completed' },
  ])('rejects incompatible wire fields %j', (event) => expect(() => decodeEvent(event)).toThrow());
  it('requires numeric nextPage or null and forbids history deltas', () => {
    expect(decodeHistory({ events: [], nextPage: null })).toEqual({ events: [], nextPage: null });
    [undefined, '2', 0, -1, 1.5].forEach((nextPage) => expect(() => decodeHistory({ events: [], nextPage })).toThrow());
    expect(() => decodeHistory({ events: [preview('x')], nextPage: null })).toThrow();
  });
  it.each([{}, { tool: 'query' }, { tool: 'query', args: null }, { tool: 42, args: [] }])('reads failed model arguments in both SSE and history: %j', async (args) => {
    const failedCall = { ...call, arguments: args, requiresConfirmation: false };
    const failedResult = { ...result, isError: true, errorCode: 'TOOL_EXECUTION_FAILED' };
    const events = [userFixture(), failedCall, failedResult, idleFixture()];
    const received: RuntimeEvent[] = [];
    await readEventStream(streamResponse(events), (event) => { received.push(event); }, new AbortController().signal);
    expect(received).toEqual(events);
    expect(decodeHistory({ events, nextPage: null }).events).toEqual(events);
  });
  it.each(['中'.repeat(4096), '😀'.repeat(2048)])('accepts Runtime denial limits independently of composer limits', (denyMessage) => {
    const receipt = eventFixture({ eventId: 'deny', type: 'user.tool_confirmation', toolCallId: 'tool-a', result: 'deny', denyMessage });
    expect(decodeEvent(receipt)).toEqual(receipt);
    expect(decodeHistory({ events: [receipt], nextPage: null }).events).toEqual([receipt]);
    expect(() => decodeEvent({ ...receipt, denyMessage: denyMessage + 'x' })).toThrow();
  });
  it('keeps confirmations within their message when providers reuse a tool ID', () => {
    const previousCall = { ...call, eventId: 'old-call', sourceEventId: 'old-root' };
    const previousReceipt = eventFixture({ eventId: 'old-confirm', type: 'user.tool_confirmation', toolCallId: call.toolCallId, result: 'allow' });
    const previousResult = { ...result, eventId: 'old-result', sourceEventId: 'old-root' };
    const history = [userFixture('old-root'), previousCall, previousReceipt, previousResult,
      { ...idleFixture(), eventId: 'old-idle', sourceEventId: 'old-root' }, userFixture(), call, idleFixture('confirming')];
    expect(executionState(history)).toMatchObject({ confirming: true, pendingTool: call });
    const currentReceipt = { ...previousReceipt, eventId: 'current-confirm' };
    expect(executionState([...history, currentReceipt]).pendingTool).toBeUndefined();
    expect(hasToolConfirmation([userFixture('old-root'), previousCall, ...history.slice(5), currentReceipt], previousCall)).toBe(false);
  });
  it('validates Unicode message limits and canonical file-only requests', () => {
    expect(messageEvent('😀'.repeat(2048)).type).toBe('user.message');
    expect(() => messageEvent('😀'.repeat(2049))).toThrow();
    const fileId = 'a'.repeat(32);
    expect(messageEvent('', [fileId])).toEqual({ type: 'user.message', content: [{ type: 'file', fileId }] });
    expect(() => messageEvent('', [fileId, fileId])).toThrow();
    expect(() => messageEvent('')).toThrow();
  });
  it('compares receipt fields without depending on JSON object property order', () => {
    expect(matchesReceipt(messageEvent('检查订单'), eventFixture({ ...userFixture(), content: [{ text: '检查订单', type: 'text' }] }))).toBe(true);
    expect(matchesReceipt(messageEvent('另一个内容'), userFixture())).toBe(false);
  });
  it('replaces previews, ignores late deltas and deduplicates interrupt terminal history', () => {
    let events = mergeEvent([], preview('未'));
    events = mergeEvent(events, preview('完整'));
    expect(events[0]).toHaveProperty('content', '未完整');
    events = mergeEvent(events, completed);
    events = mergeEvent(events, preview('迟到'));
    expect(events[0]).toHaveProperty('content', '完整正文');
    const history = [userFixture(), completed, idleFixture()];
    expect(reconcileEvents([...events, idleFixture()], history)).toEqual(history);
  });
  it('keeps history order even when time goes backwards and retains unseen live tail', () => {
    const later = { ...userFixture('later'), createdAt: '2020-01-01T00:00:00.000Z' };
    expect(reconcileEvents([completed], [userFixture(), later]).map((event) => event.eventId)).toEqual(['root-a', 'later', 'answer']);
  });
  it('does not release confirming or let an old idle terminate a newer root', () => {
    expect(executionState([userFixture(), call, idleFixture('confirming')])).toMatchObject({ confirming: true, pendingTool: call, terminal: undefined });
    expect(executionState([userFixture(), idleFixture(), userFixture('root-b')])).toMatchObject({ rootId: 'root-b', terminal: undefined });
    expect(executionState([userFixture(), call, idleFixture('terminated')]).pendingTool).toBeUndefined();
  });
  it('projects an exact plan wait and replaces todo snapshots only within the current root', () => {
    const plan = eventFixture({ eventId: 'plan-a', type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'exit-a', content: '# 方案\n\n步骤' });
    const todo = eventFixture({ eventId: 'todo-a', type: 'agent.todo_updated', sourceEventId: 'root-a', toolCallId: 'todo-call',
      todos: [{ content: '验证结果', status: 'in_progress' }] });
    const waiting = [userFixture(), plan, idleFixture('confirming')];
    expect(decodeHistory({ events: [...waiting, todo], nextPage: null }).events).toHaveLength(4);
    expect(executionState(waiting).pendingPlan).toEqual(plan);
    expect(executionTodos([...waiting, todo])?.todos).toEqual([{ content: '验证结果', status: 'in_progress' }]);
    expect(executionTodos([...waiting, todo, userFixture('root-b')])).toBeUndefined();
    const approved = eventFixture({ eventId: 'approve-a', type: 'user.plan_confirmation', targetEventId: 'plan-a', result: 'approve' });
    expect(executionState([...waiting, approved]).pendingPlan).toBeUndefined();
    expect(matchesReceipt({ type: 'user.plan_confirmation', targetEventId: 'plan-a', result: 'approve' }, approved)).toBe(true);
  });
  it.each([
    { type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'x', content: '没有标题' },
    { type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'x', content: '## 二级标题' },
    { type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'x', content: '  # 缩进标题' },
    { type: 'agent.todo_updated', sourceEventId: 'root-a', toolCallId: 'x', todos: [{ content: '重复', status: 'pending' }, { content: '重复', status: 'completed' }] },
    { type: 'agent.todo_updated', sourceEventId: 'root-a', toolCallId: 'x', todos: [{ content: '一', status: 'in_progress' }, { content: '二', status: 'in_progress' }] },
    { type: 'session.mode_changed', previousMode: 'plan', mode: 'build', reason: 'planApproved' },
  ])('rejects malformed plan mode events %j', (partial) => {
    expect(() => decodeEvent(eventFixture({ eventId: 'invalid', ...partial }))).toThrow();
  });
  it('uses Java String.strip whitespace for Todo content', () => {
    const event = eventFixture({ eventId: 'todo-nbsp', type: 'agent.todo_updated', sourceEventId: 'root-a', toolCallId: 'x',
      todos: [{ content: '\u00a0任务', status: 'pending' }] });
    expect(decodeEvent(event)).toEqual(event);
    expect(decodeEvent({ ...event, todos: [{ content: '\u00a0', status: 'pending' }] })).toMatchObject({
      todos: [{ content: '\u00a0', status: 'pending' }],
    });
    expect(() => decodeEvent({ ...event, todos: [{ content: ' 任务', status: 'pending' }] })).toThrow();
  });
  it('accepts the same standalone carriage-return plan lines as Java String.lines', () => {
    const event = eventFixture({ eventId: 'plan-cr', type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'x',
      content: '\r# 方案\r正文' });
    expect(decodeEvent(event)).toEqual(event);
  });
  it('allows plan review while the Session is running but keeps other control locks', () => {
    const waiting = { mode: 'plan', state: 'running', hasPendingPlan: true, historyReady: true,
      controlLocked: false, uncertain: false };
    expect(canReviewPlan(waiting)).toBe(true);
    expect(canReviewPlan({ ...waiting, state: 'idle' })).toBe(false);
    expect(canReviewPlan({ ...waiting, controlLocked: true })).toBe(false);
    expect(canReviewPlan({ ...waiting, historyReady: false })).toBe(false);
  });
  it('reads data-only SSE split at every byte, comments and CRLF; cancels after terminal', async () => {
    const bytes = new TextEncoder().encode(`: ping\r\n\r\ndata: ${JSON.stringify(userFixture('root-a', '中文😀'))}\r\n\r\n`);
    let cursor = 0;
    const response = new Response(new ReadableStream({ pull(controller) {
      if (cursor < bytes.length) controller.enqueue(bytes.slice(cursor, ++cursor)); else controller.close();
    } }), { headers: { 'Content-Type': 'text/event-stream' } });
    const events: RuntimeEvent[] = [];
    await readEventStream(response, (event) => { events.push(event); }, new AbortController().signal);
    expect(events).toEqual([userFixture('root-a', '中文😀')]);
  });
  it.each(['event: user.message\ndata: {}\n\n', 'id: 1\ndata: {}\n\n', 'data: nope\n\n', 'data: {}', 'data: {}\ndata: {}\n\n'])('fails closed on legacy/malformed SSE %s', async (body) => {
    const response = new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
    await expect(readEventStream(response, () => {}, new AbortController().signal)).rejects.toThrow();
  });
  it('cancels stalled readers when the view is abandoned', async () => {
    let cancelled = false;
    const controller = new AbortController();
    const response = new Response(new ReadableStream({ cancel() { cancelled = true; } }), { headers: { 'Content-Type': 'text/event-stream' } });
    const reading = readEventStream(response, () => {}, controller.signal);
    controller.abort(); await reading;
    expect(cancelled).toBe(true);
    await expect(readEventStream(streamResponse([{}]), () => {}, new AbortController().signal)).rejects.toThrow();
  });
});

describe('command snapshots', () => {
  const catalog = decodeCatalog({ commands: [
    { name: 'model', kind: 'builtin', description: '模型', input: { hint: '[modelId]' } },
    { name: 'help', kind: 'builtin', description: '介绍' },
    { name: 'skill:7-day', kind: 'skill', description: '巡检', input: { hint: '[说明]', acceptsFiles: true } },
  ] });
  it('parses exact ASCII separators and preserves multiline arguments', () => {
    expect(parseCommand(' /help')).toBeNull();
    expect(parseCommand('/model \t a\n b ')).toEqual({ nameToken: 'model', argumentsText: 'a\n b ' });
    expect(filterCommands(catalog, '/')).toEqual(catalog);
    expect(filterCommands(catalog, '/M')).toEqual([]);
  });
  it('freezes model query/mutation before decoding and rejects shape guessing', () => {
    const query = freezeInvocation(catalog[0], '/model');
    const change = freezeInvocation(catalog[0], '/model new-model');
    expect(query).toHaveProperty('expectedResultKind', 'models');
    expect(change).toHaveProperty('expectedResultKind', 'session');
    expect(Object.isFrozen(change.request)).toBe(true);
    expect(() => decodeCommandResult(change, { currentModelId: 'x', models: ['x'] })).toThrow();
    expect(() => freezeInvocation(catalog[1], '/help extra')).toThrow();
    expect(() => freezeInvocation(catalog[1], '/unknown')).toThrow();
  });
  it('keeps Skill SSE separate and Help data as literal text', () => {
    const skill = freezeInvocation(catalog[2], '/skill:7-day 检查');
    expect(skill).toHaveProperty('executionMode', 'skillEvents');
    expect(() => decodeCommandResult(skill, {})).toThrow();
    const value = { displayName: '<script>bad</script>', description: ['![x](https://invalid.test)'], userCases: [] };
    expect(decodeCommandResult(freezeInvocation(catalog[1], '/help'), value)).toHaveProperty('value', value);
  });
});
