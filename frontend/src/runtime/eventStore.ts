import type { RuntimeEvent } from '../types/runtime';
import { invalidResponse } from './protocol';

export function isPreview(event: RuntimeEvent): boolean {
  return 'phase' in event && event.phase === 'delta';
}
export function mergeEvent(events: RuntimeEvent[], incoming: RuntimeEvent): RuntimeEvent[] {
  const index = events.findIndex((event) => event.eventId === incoming.eventId);
  if (index < 0) return [...events, incoming];
  const previous = events[index];
  if (previous.type !== incoming.type
    || ('sourceEventId' in previous && 'sourceEventId' in incoming && previous.sourceEventId !== incoming.sourceEventId)) invalidResponse();
  if (!isPreview(previous)) return events;
  const merged = [...events];
  merged[index] = isPreview(incoming) && 'content' in previous && 'content' in incoming
    ? { ...incoming, content: String(previous.content) + incoming.content } as RuntimeEvent
    : incoming;
  return merged;
}
/** 历史顺序为权威；保留尚未出现在分页中的实时尾部，不用 createdAt 排序。 */
export function reconcileEvents(current: RuntimeEvent[], history: RuntimeEvent[]): RuntimeEvent[] {
  let ordered: RuntimeEvent[] = [];
  for (const event of history) ordered = mergeEvent(ordered, event);
  const ids = new Set(ordered.map((event) => event.eventId));
  return [...ordered, ...current.filter((event) => !ids.has(event.eventId))];
}
export function executionState(events: RuntimeEvent[]) {
  const latest = [...events].reverse();
  const root = latest.find((event) => event.type === 'user.message');
  const rootId = root?.eventId ?? '';
  const idle = latest.find((event) => event.type === 'session.status_idle' && event.sourceEventId === rootId);
  const terminal = idle?.type === 'session.status_idle' && idle.reason !== 'confirming' ? idle : undefined;
  const pendingTool = latest.find((event) => event.type === 'agent.tool_call'
    && event.sourceEventId === rootId && event.requiresConfirmation
    && !events.some((item) => (item.type === 'agent.tool_result' || item.type === 'user.tool_confirmation') && item.toolCallId === event.toolCallId));
  const interruptPending = !terminal && events.some((event) => event.type === 'user.interrupt' && event.targetEventId === rootId);
  const continued = idle && events.slice(events.indexOf(idle) + 1).some((event) => event.type === 'user.tool_confirmation'
    || event.type === 'user.interrupt' || ('sourceEventId' in event && event.sourceEventId === rootId));
  return { rootId, terminal, interruptPending, pendingTool: !terminal && pendingTool?.type === 'agent.tool_call' ? pendingTool : undefined,
    confirming: !terminal && !continued && idle?.type === 'session.status_idle' && idle.reason === 'confirming' };
}
