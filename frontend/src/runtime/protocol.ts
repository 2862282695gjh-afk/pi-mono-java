import { RuntimeApiError } from '../types/runtime';
import type { AvailableModels, QuestionAnswer, RuntimeEvent, RuntimeHistoryPage, RuntimeQuestion, RuntimeSession, UserEvent } from '../types/runtime';

export const MAX_INPUT_CHARACTERS = 2048;
export const MAX_DENY_MESSAGE_UTF16_UNITS = 4096;
export const codePointLength = (value: string): number => Array.from(value).length;
export function invalidResponse(): never {
  throw new RuntimeApiError({ code: 'INVALID_RESPONSE', message: '事件响应格式不正确，请核对服务端接口。' });
}
export function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalidResponse();
  return value as Record<string, unknown>;
}
export function text(value: unknown): string {
  if (typeof value !== 'string') return invalidResponse();
  return value;
}
export function nonempty(value: unknown): string {
  const result = text(value);
  if (!result.trim()) return invalidResponse();
  return result;
}
export function strings(value: unknown): string[] {
  if (!Array.isArray(value)) return invalidResponse();
  return value.map(text);
}
function bool(value: unknown): boolean {
  if (typeof value !== 'boolean') return invalidResponse();
  return value;
}
function count(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return invalidResponse();
  return value;
}
function oneOf(value: unknown, values: string[]): string {
  if (!values.includes(text(value))) return invalidResponse();
  return text(value);
}
function only(data: Record<string, unknown>, allowed: string[]): void {
  if (Object.keys(data).some((key) => !allowed.includes(key))) invalidResponse();
}
export function javaStrip(value: string): string {
  return value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, '');
}
function normalized(value: unknown, max: number): string {
  const result = text(value);
  if (!result || javaStrip(result) !== result || result.length > max || codePointLength(result) > max) invalidResponse();
  return result;
}
function questionId(value: unknown): string {
  const result = text(value);
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/u.test(result)) invalidResponse();
  return result;
}
function decodeQuestions(value: unknown): RuntimeQuestion[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 3) return invalidResponse();
  const ids = new Set<string>();
  return value.map((item) => {
    const data = record(item);
    only(data, ['id', 'question', 'header', 'options', 'multiSelect']);
    const id = questionId(data.id);
    if (ids.has(id)) invalidResponse();
    ids.add(id);
    const question: RuntimeQuestion = { id, question: normalized(data.question, 2048), multiSelect: bool(data.multiSelect) };
    if (data.header !== undefined) question.header = normalized(data.header, 64);
    if (data.options !== undefined) {
      if (!Array.isArray(data.options) || data.options.length < 2 || data.options.length > 6) return invalidResponse();
      const labels = new Set<string>();
      question.options = data.options.map((item) => {
        const option = record(item);
        only(option, ['label', 'description']);
        const label = normalized(option.label, 160);
        if (labels.has(label)) invalidResponse();
        labels.add(label);
        return { label, ...(option.description === undefined ? {} : { description: normalized(option.description, 1024) }) };
      });
    } else if (question.multiSelect) invalidResponse();
    return question;
  });
}
function decodeAnswers(value: unknown): QuestionAnswer[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 3) return invalidResponse();
  const ids = new Set<string>();
  return value.map((item) => {
    const data = record(item);
    only(data, ['id', 'selected', 'custom']);
    const id = questionId(data.id);
    if (ids.has(id) || !Array.isArray(data.selected) || data.selected.length > 6) invalidResponse();
    ids.add(id);
    const selected = data.selected.map((label) => normalized(label, 160));
    if (new Set(selected).size !== selected.length) invalidResponse();
    const custom = data.custom === undefined ? undefined : normalized(data.custom, 4096);
    if (!selected.length && custom === undefined) invalidResponse();
    return { id, selected, ...(custom === undefined ? {} : { custom }) };
  });
}
export function decodeSession(value: unknown): RuntimeSession {
  const data = record(value);
  nonempty(data.sessionId); nonempty(data.agentId); nonempty(data.modelId);
  if (data.displayName !== null) text(data.displayName);
  oneOf(data.state, ['idle', 'running']); bool(data.thinking); nonempty(data.createdAt);
  oneOf(data.mode, ['build', 'plan']);
  if (data.updatedAt !== undefined) nonempty(data.updatedAt);
  if (data.lifetimeUsage !== undefined) record(data.lifetimeUsage);
  return data as unknown as RuntimeSession;
}
export function decodeModels(value: unknown): AvailableModels {
  const data = record(value);
  return { currentModelId: nonempty(data.currentModelId), models: strings(data.models) };
}
export function decodeEvent(value: unknown, history = false): RuntimeEvent {
  const data = record(value);
  nonempty(data.eventId);
  const type = text(data.type);
  const fields: Record<string, string[]> = {
    'user.message': ['content'],
    'user.interrupt': ['targetEventId'],
    'user.tool_confirmation': ['toolCallId', 'result', 'denyMessage'],
    'user.plan_confirmation': ['targetEventId', 'result', 'feedback'],
    'user.question_answer': ['targetEventId', 'answers'],
    'agent.message': ['phase', 'content', 'sourceEventId', 'usage'],
    'agent.thinking': ['phase', 'content', 'sourceEventId'],
    'agent.tool_call': ['toolCallId', 'toolName', 'displayName', 'arguments', 'requiresConfirmation', 'sourceEventId'],
    'agent.tool_result': ['toolCallId', 'content', 'isError', 'errorCode', 'sourceEventId'],
    'agent.plan': ['phase', 'toolCallId', 'content', 'sourceEventId'],
    'agent.todo_updated': ['toolCallId', 'todos', 'sourceEventId'],
    'agent.question': ['toolCallId', 'questions', 'label', 'context', 'sourceEventId'],
    'session.status_idle': ['reason', 'sourceEventId', 'errorCode', 'message'],
    'session.model_changed': ['previousModelId', 'modelId', 'reason'],
    'session.thinking_changed': ['previousThinking', 'thinking', 'reason'],
    'session.mode_changed': ['previousMode', 'mode', 'reason', 'sourceEventId', 'confirmationEventId'],
    'session.compacted': ['reason', 'tokensBefore', 'estimatedTokensAfter', 'sourceEventId'],
  };
  if (!Object.hasOwn(fields, type)) return invalidResponse();
  only(data, ['eventId', 'type', 'createdAt', ...fields[type]]);
  if (data.phase === 'delta') {
    if (history || data.createdAt !== undefined || data.usage !== undefined) invalidResponse();
  } else if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/u.test(text(data.createdAt)) || !Number.isFinite(Date.parse(text(data.createdAt)))) {
    invalidResponse();
  }
  if (type.startsWith('agent.') || type === 'session.status_idle') nonempty(data.sourceEventId);
  switch (type) {
    case 'user.message': {
      if (!Array.isArray(data.content) || !data.content.length || data.content.length > 5) return invalidResponse();
      const files = new Set<string>();
      data.content.forEach((value, index) => {
        const block = record(value);
        if (block.type === 'text') {
          only(block, ['type', 'text']); nonempty(block.text);
          if (index !== 0) invalidResponse();
        } else if (block.type === 'file') {
          only(block, ['type', 'fileId']);
          const id = nonempty(block.fileId);
          if (files.has(id)) invalidResponse();
          files.add(id);
        } else invalidResponse();
      });
      if (files.size > 4) invalidResponse();
      break;
    }
    case 'user.interrupt': nonempty(data.targetEventId); break;
    case 'user.tool_confirmation':
      nonempty(data.toolCallId); oneOf(data.result, ['allow', 'deny']);
      if (data.denyMessage !== undefined && (data.result !== 'deny' || nonempty(data.denyMessage).length > MAX_DENY_MESSAGE_UTF16_UNITS)) invalidResponse();
      break;
    case 'user.plan_confirmation':
      if (nonempty(data.targetEventId).length > 128) invalidResponse();
      oneOf(data.result, ['approve', 'revise']);
      if (data.feedback !== undefined && (data.result !== 'revise' || !text(data.feedback).trim()
        || text(data.feedback).length > MAX_DENY_MESSAGE_UTF16_UNITS)) invalidResponse();
      break;
    case 'user.question_answer':
      if (nonempty(data.targetEventId).length > 128) invalidResponse();
      data.answers = decodeAnswers(data.answers);
      break;
    case 'agent.message': case 'agent.thinking':
      oneOf(data.phase, ['delta', 'completed']); text(data.content);
      if (data.usage !== undefined) record(data.usage);
      break;
    case 'agent.tool_call':
      nonempty(data.toolCallId); nonempty(data.toolName); record(data.arguments); bool(data.requiresConfirmation);
      if (typeof data.displayName === 'string' && data.displayName.trim()) data.displayName = data.displayName.trim();
      else delete data.displayName;
      // 模型参数的业务错误由工具执行校验处理，失败调用仍是可展示的事件。
      break;
    case 'agent.tool_result':
      nonempty(data.toolCallId); bool(data.isError);
      if (!Array.isArray(data.content) || !data.content.length) return invalidResponse();
      data.content.forEach((value) => {
        const block = record(value); only(block, ['type', 'text']);
        oneOf(block.type, ['text']); text(block.text);
      });
      if (data.isError) nonempty(data.errorCode);
      else if (data.errorCode !== undefined) invalidResponse();
      break;
    case 'agent.plan': {
      const plan = text(data.content);
      if (data.phase === 'delta') {
        if (!plan || data.toolCallId !== undefined) invalidResponse();
        break;
      }
      if (data.phase !== undefined) oneOf(data.phase, ['completed']);
      nonempty(data.toolCallId);
      const first = nonempty(plan).split(/\r\n|[\r\n]/u).find((line) => javaStrip(line));
      if (plan.length > 262_144 || !first?.startsWith('# ')) invalidResponse();
      break;
    }
    case 'agent.todo_updated': {
      nonempty(data.toolCallId);
      if (!Array.isArray(data.todos) || data.todos.length > 100) return invalidResponse();
      const contents = new Set<string>();
      let active = 0;
      data.todos.forEach((value) => {
        const todo = record(value); only(todo, ['content', 'status']);
        const rawContent = text(todo.content);
        const content = javaStrip(rawContent);
        if (!content || content !== rawContent || content.length > 1024 || contents.has(content)) invalidResponse();
        contents.add(content);
        if (oneOf(todo.status, ['pending', 'in_progress', 'completed']) === 'in_progress') active++;
      });
      if (active > 1) invalidResponse();
      break;
    }
    case 'agent.question': {
      nonempty(data.toolCallId);
      const hasQuestions = Object.hasOwn(data, 'questions');
      const hasLabel = Object.hasOwn(data, 'label');
      if (hasQuestions === hasLabel || (Object.hasOwn(data, 'context') && !hasLabel)) invalidResponse();
      if (hasQuestions) data.questions = decodeQuestions(data.questions);
      else {
        text(data.label);
        if (Object.hasOwn(data, 'context')) record(data.context);
      }
      break;
    }
    case 'session.status_idle':
      oneOf(data.reason, ['done', 'failed', 'terminated', 'confirming']);
      if (data.reason === 'failed') { nonempty(data.errorCode); nonempty(data.message); }
      else if (data.errorCode !== undefined || data.message !== undefined) invalidResponse();
      break;
    case 'session.model_changed': nonempty(data.previousModelId); nonempty(data.modelId); nonempty(data.reason); break;
    case 'session.thinking_changed': bool(data.previousThinking); bool(data.thinking); nonempty(data.reason); break;
    case 'session.mode_changed': {
      const previous = oneOf(data.previousMode, ['build', 'plan']);
      const mode = oneOf(data.mode, ['build', 'plan']);
      if (previous === mode) invalidResponse();
      const reason = oneOf(data.reason, ['requested', 'planApproved']);
      if (reason === 'planApproved') {
        if (previous !== 'plan' || mode !== 'build') invalidResponse();
        nonempty(data.sourceEventId); nonempty(data.confirmationEventId);
      } else if (data.sourceEventId !== undefined || data.confirmationEventId !== undefined) invalidResponse();
      break;
    }
    case 'session.compacted':
      count(data.tokensBefore); count(data.estimatedTokensAfter); nonempty(data.reason);
      if (data.sourceEventId !== undefined) nonempty(data.sourceEventId);
      break;
  }
  return data as unknown as RuntimeEvent;
}
export function decodeHistory(value: unknown): RuntimeHistoryPage {
  const data = record(value);
  only(data, ['events', 'nextPage']);
  if (!Array.isArray(data.events)) return invalidResponse();
  if (data.nextPage !== null && count(data.nextPage) < 1) invalidResponse();
  return { events: data.events.map((event) => decodeEvent(event, true)), nextPage: data.nextPage as number | null };
}
export function messageEvent(message: string, fileIds: string[] = []): UserEvent {
  if (codePointLength(message) > MAX_INPUT_CHARACTERS || fileIds.length > 4 || new Set(fileIds).size !== fileIds.length
    || fileIds.some((id) => !/^[0-9a-fA-F]{32}$/u.test(id)) || (!message.trim() && !fileIds.length)) {
    throw new RuntimeApiError({ code: 'INVALID_INPUT', message: '消息最多 2048 个字符、4 个有效附件，且不能为空。' });
  }
  return { type: 'user.message', content: [
    ...(message.trim() ? [{ type: 'text' as const, text: message }] : []),
    ...fileIds.map((fileId) => ({ type: 'file' as const, fileId })),
  ] };
}

export function matchesReceipt(input: UserEvent, event: RuntimeEvent): boolean {
  if (input.type === 'user.message' && event.type === 'user.message') {
    return input.content.length === event.content.length && input.content.every((block, index) => {
      const actual = event.content[index];
      return block.type === 'text' && actual.type === 'text' ? block.text === actual.text
        : block.type === 'file' && actual.type === 'file' && block.fileId === actual.fileId;
    });
  }
  if (input.type === 'user.interrupt' && event.type === 'user.interrupt') return input.targetEventId === event.targetEventId;
  if (input.type === 'user.tool_confirmation' && event.type === 'user.tool_confirmation') {
    return input.toolCallId === event.toolCallId && input.result === event.result && input.denyMessage === event.denyMessage;
  }
  if (input.type === 'user.plan_confirmation' && event.type === 'user.plan_confirmation') {
    return input.targetEventId === event.targetEventId && input.result === event.result && input.feedback === event.feedback;
  }
  return input.type === 'user.question_answer' && event.type === 'user.question_answer'
    && input.targetEventId === event.targetEventId && JSON.stringify(input.answers) === JSON.stringify(event.answers);
}

/** POST 专用 data-only SSE 读取器；中止只关闭观察，不代表业务停止。 */
export async function readEventStream(response: Response, receive: (event: RuntimeEvent) => boolean | void, signal: AbortSignal): Promise<void> {
  if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'text/event-stream' || !response.body) invalidResponse();
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const cancel = () => { void reader.cancel().catch(() => undefined); };
  signal.addEventListener('abort', cancel, { once: true });
  let buffer = '';
  try {
    while (!signal.aborted) {
      const chunk = await reader.read();
      buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      if (buffer.length > 2 * 1024 * 1024) invalidResponse();
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/u.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const lines = frame.split(/\r?\n/u).filter((line) => line && !line.startsWith(':'));
        if (!lines.length) continue;
        if (lines.length !== 1 || !lines[0].startsWith('data:')) invalidResponse();
        let payload: unknown;
        try { payload = JSON.parse(lines[0].slice(5).replace(/^ /u, '')); } catch { invalidResponse(); }
        const event = decodeEvent(payload);
        if (!signal.aborted && receive(event) === false) return;
      }
      if (chunk.done) {
        if (buffer.trim() && !buffer.startsWith(':')) invalidResponse();
        return;
      }
    }
  } finally {
    signal.removeEventListener('abort', cancel);
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
