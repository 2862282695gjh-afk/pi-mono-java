import { describe, expect, it } from 'vitest';
import { decodeEvent, decodeHistory, matchesReceipt, messageEvent, readEventStream } from './protocol';
import { canAnswerQuestion, canReviewPlan, executionState, executionTodos, hasToolConfirmation, mergeEvent, reconcileEvents } from './eventStore';
import { decodeCatalog, decodeCommandResult, filterCommands, freezeInvocation, parseCommand } from './commands';
import { businessConfirmationQuestionFixture, eventFixture, idleFixture, questionFixture, streamResponse,
  userFixture } from './fixtures';
import type { RuntimeEvent } from '../types/runtime';

const call = eventFixture({ eventId: 'call', type: 'agent.tool_call', sourceEventId: 'root-a', toolCallId: 'tool-a', toolName: 'CallTool', arguments: { tool: 'query', args: {} }, requiresConfirmation: true }) as Extract<RuntimeEvent, { type: 'agent.tool_call' }>;
const result = eventFixture({ eventId: 'result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'tool-a', content: [{ type: 'text', text: '' }], isError: false });
const preview = (content: string): RuntimeEvent => ({ eventId: 'answer', type: 'agent.message', phase: 'delta', content, sourceEventId: 'root-a' });
const completed = eventFixture({ ...preview('完整正文'), phase: 'completed' });
const planPreview = (content: string): Extract<RuntimeEvent, { type: 'agent.plan' }> => ({
  eventId: 'plan-a', type: 'agent.plan', phase: 'delta', content, sourceEventId: 'root-a',
});
const planCompleted = eventFixture({ eventId: 'plan-a', type: 'agent.plan', phase: 'completed', sourceEventId: 'root-a',
  toolCallId: 'exit-a', content: '# 权威方案\n\n步骤' });

describe('Session Events protocol and authoritative store', () => {
  it.each([
    'USER_QUESTION_ENTRY_UNSUPPORTED',
    'USER_QUESTION_PORT_MISSING',
    'USER_QUESTION_ADAPTER_UNBOUND',
    'USER_QUESTION_BINDING_MISSING',
    'USER_QUESTION_BINDING_AMBIGUOUS',
    'USER_QUESTION_PERMISSION_DENIED',
    'USER_QUESTION_PERMISSION_REQUIRED',
    'USER_QUESTION_DISCOVERY_FAILED',
    'USER_QUESTION_TOOL_NOT_FOUND',
    'USER_QUESTION_TOOL_ID_MISMATCH',
    'USER_QUESTION_PREPARATION_CONFLICT',
    'USER_QUESTION_NOT_PREPARED',
    'USER_QUESTION_CALL_MISMATCH',
    'USER_QUESTION_ANSWER_INVALID',
    'USER_QUESTION_MULTIPLE_TOOL_CALLS',
    'USER_QUESTION_CALL_NOT_FINALIZED',
    'USER_QUESTION_PENDING_CONFLICT',
    'USER_QUESTION_WAIT_MISSING',
  ])('preserves question failure code %s in live events and history', async (errorCode) => {
    const failure = { ...result, isError: true, errorCode, content: [{ type: 'text', text: '具体失败原因' }] };
    const received: RuntimeEvent[] = [];
    await readEventStream(streamResponse([failure]), (event) => { received.push(event); }, new AbortController().signal);
    expect(received).toEqual([failure]);
    expect(decodeHistory({ events: [failure], nextPage: null }).events).toEqual([failure]);
  });
  it('accepts completed and preview shapes, text-only tool blocks including empty text', () => {
    [userFixture(), idleFixture(), call, result, preview('增量'), completed].forEach((event) => expect(decodeEvent(event)).toEqual(event));
    expect(decodeEvent({ ...result, content: [{ type: 'text', text: 'one' }, { type: 'text', text: '' }] })).toBeTruthy();
  });
  it('normalizes a valid tool displayName and degrades invalid optional values to absence', () => {
    expect(decodeEvent({ ...call, displayName: '  查询教室  ' })).toMatchObject({ displayName: '查询教室' });
    for (const displayName of [undefined, null, 42, {}, ' \t ']) {
      expect(decodeEvent({ ...call, displayName })).not.toHaveProperty('displayName');
    }
    expect(() => decodeEvent({ ...call, displayLabel: '未知字段' })).toThrow();
  });
  it('keeps the first committed tool displayName when a duplicate event arrives', () => {
    const original = { ...call, displayName: '原名称' };
    expect(mergeEvent([original], { ...original, displayName: '后来的目录名称' })[0]).toEqual(original);
  });
  it('decodes complete questions and answers identically in SSE and history', async () => {
    const question = questionFixture();
    const answer = eventFixture({ eventId: 'answer-question-a', type: 'user.question_answer', targetEventId: 'question-a',
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '补充' }] });
    expect(decodeHistory({ events: [question, answer], nextPage: null }).events).toEqual([question, answer]);
    const received: RuntimeEvent[] = [];
    await readEventStream(streamResponse([question, answer]), (event) => { received.push(event); }, new AbortController().signal);
    expect(received).toEqual([question, answer]);
  });
  it('decodes the mutually exclusive business confirmation label/context payload without rewriting raw values', async () => {
    const question = businessConfirmationQuestionFixture();
    const withoutContext = { ...question, eventId: 'business-confirmation-empty', label: '' };
    delete (withoutContext as Record<string, unknown>).context;
    expect(decodeHistory({ events: [question, withoutContext], nextPage: null }).events).toEqual([question, withoutContext]);
    const received: RuntimeEvent[] = [];
    await readEventStream(streamResponse([question]), (event) => { received.push(event); }, new AbortController().signal);
    expect(received).toEqual([question]);
    expect(decodeEvent({ ...question, eventId: 'business-confirmation-whitespace', label: '  \n', context: {} })).toMatchObject({
      label: '  \n', context: {},
    });
  });
  it.each([
    { ...businessConfirmationQuestionFixture(), questions: [{ id: 'x', question: '重复载荷', multiSelect: false }] },
    { ...businessConfirmationQuestionFixture(), label: 42 },
    { ...businessConfirmationQuestionFixture(), context: null },
    { ...businessConfirmationQuestionFixture(), context: [] },
    { ...businessConfirmationQuestionFixture(), label: undefined },
    { ...questionFixture(), context: {} },
  ])('rejects malformed business confirmation question payloads %j', (event) => expect(() => decodeEvent(event)).toThrow());
  it.each([
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', multiSelect: false, unknown: true }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: ' 问题', multiSelect: false }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', multiSelect: true }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', options: [], multiSelect: false }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', options: [{ label: 'A' }, { label: 'A' }], multiSelect: false }] },
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: [] }] }),
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: [' A'] }] }),
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: ['A', 'A'] }] }),
  ])('rejects malformed question contracts %j', (event) => expect(() => decodeEvent(event)).toThrow());
  it('uses Java strip and UTF-16 boundaries for question text and custom answers', () => {
    const plain = eventFixture({ eventId: 'plain', type: 'agent.question', sourceEventId: 'root-a', toolCallId: 'ask',
      questions: [{ id: 'plain', question: '\u00a0', multiSelect: false }] });
    expect(decodeEvent(plain)).toEqual(plain);
    expect(() => decodeEvent({ ...plain, questions: [{ id: 'plain', question: ' x', multiSelect: false }] })).toThrow();
    expect(() => decodeEvent({ ...plain, questions: [{ id: 'plain', question: '😀'.repeat(1025), multiSelect: false }] })).toThrow();
    const custom = eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'plain',
      answers: [{ id: 'plain', selected: [], custom: '😀'.repeat(2048) }] });
    expect(decodeEvent(custom)).toEqual(custom);
    expect(() => decodeEvent({ ...custom, answers: [{ id: 'plain', selected: [], custom: '😀'.repeat(2049) }] })).toThrow();
  });
  it('decodes complete questions and answers identically in SSE and history', async () => {
    const question = questionFixture();
    const answer = eventFixture({ eventId: 'answer-question-a', type: 'user.question_answer', targetEventId: 'question-a',
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '补充' }] });
    expect(decodeHistory({ events: [question, answer], nextPage: null }).events).toEqual([question, answer]);
    const received: RuntimeEvent[] = [];
    await readEventStream(streamResponse([question, answer]), (event) => { received.push(event); }, new AbortController().signal);
    expect(received).toEqual([question, answer]);
  });
  it.each([
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', multiSelect: false, unknown: true }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: ' 问题', multiSelect: false }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', multiSelect: true }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', options: [], multiSelect: false }] },
    { ...questionFixture(), questions: [{ id: 'scope', question: '问题', options: [{ label: 'A' }, { label: 'A' }], multiSelect: false }] },
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: [] }] }),
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: [' A'] }] }),
    eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a', answers: [{ id: 'scope', selected: ['A', 'A'] }] }),
  ])('rejects malformed question contracts %j', (event) => expect(() => decodeEvent(event)).toThrow());
  it('uses Java strip and UTF-16 boundaries for question text and custom answers', () => {
    const plain = eventFixture({ eventId: 'plain', type: 'agent.question', sourceEventId: 'root-a', toolCallId: 'ask',
      questions: [{ id: 'plain', question: '\u00a0', multiSelect: false }] });
    expect(decodeEvent(plain)).toEqual(plain);
    expect(() => decodeEvent({ ...plain, questions: [{ id: 'plain', question: ' x', multiSelect: false }] })).toThrow();
    expect(() => decodeEvent({ ...plain, questions: [{ id: 'plain', question: '😀'.repeat(1025), multiSelect: false }] })).toThrow();
    const custom = eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'plain',
      answers: [{ id: 'plain', selected: [], custom: '😀'.repeat(2048) }] });
    expect(decodeEvent(custom)).toEqual(custom);
    expect(() => decodeEvent({ ...custom, answers: [{ id: 'plain', selected: [], custom: '😀'.repeat(2049) }] })).toThrow();
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
  it('merges plan deltas by event id and replaces them with the authoritative completion', () => {
    let events = mergeEvent([], planPreview('# 权'));
    events = mergeEvent(events, planPreview('威方案'));
    expect(events).toEqual([expect.objectContaining({ content: '# 权威方案', phase: 'delta' })]);
    events = mergeEvent(events, planCompleted);
    events = mergeEvent(events, planPreview('迟到'));
    expect(events).toEqual([planCompleted]);
    expect(() => mergeEvent(events, { ...planPreview('错源'), sourceEventId: 'root-b' })).toThrow();
  });
  it('accepts completed-only and legacy plans but rejects preview-only history and invalid phases', () => {
    expect(decodeEvent(planPreview('# 方'))).toEqual(planPreview('# 方'));
    expect(decodeEvent(planCompleted)).toEqual(planCompleted);
    const legacy = { ...planCompleted } as Record<string, unknown>;
    delete legacy.phase;
    expect(decodeEvent(legacy)).toEqual(legacy);
    expect(() => decodeHistory({ events: [planPreview('# 方')], nextPage: null })).toThrow();
    expect(() => decodeEvent({ ...planPreview('# 方'), phase: 'started' })).toThrow();
    expect(() => decodeEvent({ ...planPreview('# 方'), toolCallId: 'exit-a' })).toThrow();
    expect(() => decodeEvent(planPreview(''))).toThrow();
    expect(() => decodeEvent({ ...planPreview('# 方'), createdAt: '2026-09-08T00:00:00.000Z' })).toThrow();
    expect(executionState([userFixture(), planPreview('# 方'), idleFixture('confirming')]).pendingPlan).toBeUndefined();
  });
  it('does not let a newer draft reuse an older completed plan confirmation window', () => {
    const oldPlan = { ...planCompleted, eventId: 'plan-old' };
    const events = [userFixture(), oldPlan, idleFixture('confirming'),
      eventFixture({ eventId: 'approve-old', type: 'user.plan_confirmation', targetEventId: 'plan-old', result: 'approve' }),
      planPreview('# 新草稿'), { ...idleFixture('confirming'), eventId: 'confirm-new' }];
    expect(executionState(events).pendingPlan).toBeUndefined();
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
  it('binds a pending question and its answer by exact event id', () => {
    const first = questionFixture('question-old');
    const oldAnswer = eventFixture({ eventId: 'answer-old', type: 'user.question_answer', targetEventId: 'question-old',
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '旧回答' }] });
    const current = questionFixture('question-current');
    const waiting = [userFixture(), first, oldAnswer, current, idleFixture('confirming')];
    expect(executionState(waiting).pendingQuestion).toEqual(current);
    expect(executionState([...waiting, { ...oldAnswer, eventId: 'answer-old-duplicate' }]).pendingQuestion).toEqual(current);
    const currentAnswer = { ...oldAnswer, eventId: 'answer-current', targetEventId: 'question-current' };
    expect(executionState([...waiting, currentAnswer]).pendingQuestion).toBeUndefined();
  });
  it('keeps the business confirmation question pending while the Session remains running', () => {
    const question = businessConfirmationQuestionFixture();
    const waiting = [userFixture(), question, idleFixture('confirming')];
    expect(executionState(waiting)).toMatchObject({ confirming: true, pendingQuestion: question });
    const answer = eventFixture({ eventId: 'business-confirmation-answer', type: 'user.question_answer', targetEventId: question.eventId,
      answers: [{ id: 'confirmation', selected: [], custom: '继续执行' }] });
    expect(executionState([...waiting, answer]).pendingQuestion).toBeUndefined();
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
  it('allows question answers only after complete history and outside the shared control lock', () => {
    const waiting = { state: 'running', hasPendingQuestion: true, historyReady: true, controlLocked: false, uncertain: false };
    expect(canAnswerQuestion(waiting)).toBe(true);
    expect(canAnswerQuestion({ ...waiting, historyReady: false })).toBe(false);
    expect(canAnswerQuestion({ ...waiting, controlLocked: true })).toBe(false);
    expect(canAnswerQuestion({ ...waiting, uncertain: true })).toBe(false);
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
    const value = { displayName: '<script>bad</script>', description: ['![x](https://invalid.test)'], useCases: [] };
    expect(decodeCommandResult(freezeInvocation(catalog[1], '/help'), value)).toHaveProperty('value', value);
  });
});
