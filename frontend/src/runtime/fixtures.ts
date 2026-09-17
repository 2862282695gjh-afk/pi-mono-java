import type { RuntimeEvent, RuntimeSession } from '../types/runtime';

// 仅测试使用的已确认内部协议样本，不含真实凭据或私有推理。
export const sessionFixture = (overrides: Partial<RuntimeSession> = {}): RuntimeSession => ({
  sessionId: 'session-a', agentId: 'agent-a', displayName: null, modelId: 'model-primary',
  state: 'idle', thinking: true, mode: 'build', createdAt: '2026-09-08T00:00:00.000Z', ...overrides,
});
export function eventFixture(data: Record<string, unknown>): RuntimeEvent {
  return { createdAt: '2026-09-08T00:00:00.000Z', ...data } as RuntimeEvent;
}
export const userFixture = (eventId = 'root-a', message = '检查订单') => eventFixture({ eventId, type: 'user.message', content: [{ type: 'text', text: message }] });
export const idleFixture = (reason = 'done', sourceEventId = 'root-a') => eventFixture({ eventId: `idle-${sourceEventId}-${reason}`, type: 'session.status_idle', sourceEventId, reason });
export const questionFixture = (eventId = 'question-a') => eventFixture({ eventId, type: 'agent.question', sourceEventId: 'root-a',
  toolCallId: 'ask-a', questions: [{ id: 'scope', header: '范围', question: '选择范围', options: [
    { label: '东区', description: '只核对东区' }, { label: '全部' },
  ], multiSelect: false }, { id: 'note', question: '补充说明', multiSelect: false }] });
export const showcaseQuestionFixture = (eventId = 'showcase-question') => eventFixture({ eventId, type: 'agent.question',
  sourceEventId: 'root-a', toolCallId: 'showcase-call', label: 'driver_design_confirm', context: {
    '方案': '驱动配置方案', '超时秒数': 30, '补充说明': null, '范围': ['东区', { '夜间': true }],
  } });
export function jsonResponse(result: unknown, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify({ resCode: '0', resMsg: 'success', result }), { headers: { 'Content-Type': 'application/json', ...headers } });
}
export function streamResponse(events: unknown[]): Response {
  return new Response(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''), { headers: { 'Content-Type': 'text/event-stream' } });
}
