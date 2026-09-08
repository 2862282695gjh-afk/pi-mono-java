import { describe, expect, it } from 'vitest';
import { decodeEvent, decodeHistory, matchesReceipt, messageEvent, readEventStream } from './protocol';
import { executionState, mergeEvent, reconcileEvents } from './eventStore';
import { decodeCatalog, decodeCommandResult, filterCommands, freezeInvocation, parseCommand } from './commands';
import { eventFixture, idleFixture, streamResponse, userFixture } from './fixtures';
import type { RuntimeEvent } from '../types/runtime';

const call = eventFixture({ eventId: 'call', type: 'agent.tool_call', sourceEventId: 'root-a', toolCallId: 'tool-a', toolName: 'CallMateTool', arguments: { tool: 'query', args: {} }, requiresConfirmation: true });
const result = eventFixture({ eventId: 'result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'tool-a', content: [{ type: 'text', text: '' }], isError: false });
const preview = (content: string): RuntimeEvent => ({ eventId: 'answer', type: 'agent.message', phase: 'delta', content, sourceEventId: 'root-a' });
const completed = eventFixture({ ...preview('完整正文'), phase: 'completed' });

describe('Events v2 protocol and authoritative store', () => {
  it('accepts completed and preview shapes, text-only tool blocks including empty text', () => {
    [userFixture(), idleFixture(), call, result, preview('增量'), completed].forEach((event) => expect(decodeEvent(event)).toEqual(event));
    expect(decodeEvent({ ...result, content: [{ type: 'text', text: 'one' }, { type: 'text', text: '' }] })).toBeTruthy();
  });
  it.each([
    { ...result, isError: 'false' }, { ...result, isError: true }, { ...result, errorCode: 'TOOL_FAILED' },
    { ...result, content: [] }, { ...result, content: 'text' }, { ...result, content: [{ type: 'image', url: 'https://invalid.test' }] },
    { ...call, requiresConfirmation: undefined }, { ...call, arguments: [] }, { ...call, arguments: { tool: 'query' } },
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
