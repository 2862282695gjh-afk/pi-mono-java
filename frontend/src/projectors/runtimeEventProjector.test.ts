import { describe, expect, it } from 'vitest';
import { projectRuntimeEvents } from './runtimeEventProjector';
import { eventFixture, idleFixture, userFixture } from '../runtime/fixtures';

describe('Runtime Events presentation', () => {
  it('does not invent an answer for an empty authoritative message', () => {
    expect(projectRuntimeEvents([eventFixture({ eventId: 'a', type: 'agent.message', phase: 'completed', sourceEventId: 'root-a', content: '' })])).toEqual([]);
  });
  it('shows public thinking history independently of current settings', () => {
    const turns = projectRuntimeEvents([
      eventFixture({ eventId: 't', type: 'agent.thinking', phase: 'completed', sourceEventId: 'root-a', content: '原始推理' }),
      eventFixture({ eventId: 'setting', type: 'session.thinking_changed', thinking: false, previousThinking: true, reason: 'user' }),
    ]);
    expect(turns[0]).toMatchObject({ kind: 'thinking', title: '思考过程', content: '原始推理', status: 'completed' });
    expect(turns[1].kind).toBe('notice');
  });
  it('keeps full text blocks, explicit outcome and Runtime Mate argument wrapper', () => {
    const long = '文字'.repeat(5000);
    const turns = projectRuntimeEvents([
      userFixture(), eventFixture({ eventId: 'c', type: 'agent.tool_call', toolCallId: 'tool', sourceEventId: 'root-a', toolName: 'CallMateTool', requiresConfirmation: false, arguments: { tool: 'query', args: { area: '校园' } } }),
      eventFixture({ eventId: 'r', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'tool', content: [{ type: 'text', text: long }, { type: 'text', text: '' }], isError: false }), idleFixture(),
    ]);
    expect(turns[1]).toMatchObject({ toolName: 'CallMateTool', status: 'completed', result: `${long}\n`, arguments: [{ key: 'tool', value: 'query' }, { key: 'args', value: '{\n  "area": "校园"\n}' }] });
    expect(turns[2]).toMatchObject({ kind: 'notice', text: '本轮已完成' });
  });
  it('marks a disconnected preview as unconfirmed rather than completed', () => {
    expect(projectRuntimeEvents([{ eventId: 'p', type: 'agent.message', phase: 'delta', sourceEventId: 'root-a', content: '半条' }])[0]).toMatchObject({ streaming: false, unconfirmed: true });
  });
  it('does not apply an earlier confirmation to a later message with the same tool ID', () => {
    const call = eventFixture({ eventId: 'old-call', type: 'agent.tool_call', toolCallId: 'same-id', sourceEventId: 'old-root', toolName: 'CallMateTool', arguments: {}, requiresConfirmation: true });
    const receipt = eventFixture({ eventId: 'old-confirm', type: 'user.tool_confirmation', toolCallId: 'same-id', result: 'allow' });
    const currentCall = { ...call, eventId: 'new-call', sourceEventId: 'root-a' };
    const turns = projectRuntimeEvents([userFixture('old-root'), call, receipt, userFixture(), currentCall, idleFixture('confirming')], true);
    expect(turns.find((turn) => turn.key === 'new-call')).toMatchObject({ kind: 'activity', status: 'confirming' });
  });
});
