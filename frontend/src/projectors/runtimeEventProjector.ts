import type {
  ActivityTurn,
  AssistantTurn,
  ConversationTurn,
  ThinkingTurn,
  ToolArgumentRow,
  UserTurn,
} from '../types/product';
import type { RuntimeEventData, RuntimeEventEnvelope } from '../types/runtime';

const MAX_TOOL_ARGUMENT_ROWS = 12;
const MAX_TOOL_ARGUMENT_DEPTH = 3;
const MAX_TOOL_VALUE_LENGTH = 240;
const MAX_TOOL_RESULT_LENGTH = 4_000;
const REDACTED_TOOL_VALUE = '已隐藏';
const SENSITIVE_ARGUMENT_KEY = /authorization|cookie|credential|password|secret|token|api.?key|app.?key|access.?key|private.?key|jwt|session.?id|agent.?id|tool.?call.?id|file.?id/iu;
const SENSITIVE_ARGUMENT_VALUE = /^\s*(?:bearer\s+|basic\s+|eyJ[a-zA-Z0-9_-]+\.)/iu;
const ABSOLUTE_PATH = /^(?:\/|[a-zA-Z]:[\\/])/u;

interface AssistantProjection {
  turn: AssistantTurn;
  appended: boolean;
}

interface ToolSpec {
  toolCallId: string;
  toolName: string;
  arguments: ToolArgumentRow[];
}

export function projectRuntimeEvents(events: RuntimeEventEnvelope[]): ConversationTurn[] {
  const turns: ConversationTurn[] = [];
  const assistantTurns = new Map<string, AssistantProjection>();
  const thinkingTurns = new Map<string, ThinkingTurn>();
  const activityTurns = new Map<string, ActivityTurn>();
  const toolSpecs = new Map<string, ToolSpec>();

  for (const envelope of events) {
    if (envelope.event === 'user.message') {
      appendUserTurn(turns, envelope.data);
    } else if (envelope.event.startsWith('assistant.message.')) {
      projectAssistantEvent(turns, assistantTurns, activityTurns, toolSpecs, envelope);
    } else if (envelope.event.startsWith('assistant.thinking.')) {
      projectThinkingEvent(turns, thinkingTurns, envelope);
    } else if (envelope.event.startsWith('tool.')) {
      projectToolEvent(turns, activityTurns, toolSpecs, envelope);
    }
  }

  return turns;
}

function appendUserTurn(turns: ConversationTurn[], data: RuntimeEventData): void {
  const entryId = readString(data.entryId) || `user-${turns.length}`;
  const turn: UserTurn = {
    key: entryId,
    kind: 'user',
    text: readString(data.message),
    fileIds: readStringArray(data.fileIds),
  };
  turns.push(turn);
}

function projectAssistantEvent(
  turns: ConversationTurn[],
  assistantTurns: Map<string, AssistantProjection>,
  activityTurns: Map<string, ActivityTurn>,
  toolSpecs: Map<string, ToolSpec>,
  envelope: RuntimeEventEnvelope,
): void {
  const entryId = readString(envelope.data.entryId);
  if (!entryId) return;
  const projection = ensureAssistantTurn(assistantTurns, entryId);

  if (envelope.event === 'assistant.message.delta') {
    const delta = readTextContent(envelope.data.delta);
    if (delta) {
      appendAssistantTurn(turns, projection);
      projection.turn.rawMarkdown += delta;
    }
  }
  if (envelope.event === 'assistant.message.completed') {
    projection.turn.rawMarkdown = readMessageText(envelope.data.message);
    projection.turn.streaming = false;
    registerToolSpecs(envelope.data.message, toolSpecs, activityTurns);
    if (projection.turn.rawMarkdown) appendAssistantTurn(turns, projection);
  }
}

function projectThinkingEvent(
  turns: ConversationTurn[],
  thinkingTurns: Map<string, ThinkingTurn>,
  envelope: RuntimeEventEnvelope,
): void {
  const assistantEntryId = readString(envelope.data.assistantEntryId);
  if (!assistantEntryId) return;
  const contentIndex = readNumber(envelope.data.contentIndex);
  const key = `thinking-${assistantEntryId}-${contentIndex}`;
  let turn = thinkingTurns.get(key);
  if (!turn) {
    turn = {
      key,
      kind: 'thinking',
      status: 'running',
      title: '正在分析',
      summary: '',
    };
    thinkingTurns.set(key, turn);
    turns.push(turn);
  }

  const completed = envelope.event === 'assistant.thinking.completed';
  turn.status = completed ? 'completed' : 'running';
  turn.title = readThinkingDisplay(envelope.data, 'Title') || (completed ? '分析过程' : '正在分析');
  turn.summary = readThinkingDisplay(envelope.data, 'Summary') || turn.summary;
}

function projectToolEvent(
  turns: ConversationTurn[],
  activityTurns: Map<string, ActivityTurn>,
  toolSpecs: Map<string, ToolSpec>,
  envelope: RuntimeEventEnvelope,
): void {
  const toolCallId = readString(envelope.data.toolCallId);
  if (!toolCallId) return;
  const turn = ensureActivityTurn(turns, activityTurns, toolSpecs, toolCallId, envelope.data);

  if (envelope.event === 'tool.execution.started' || envelope.event === 'tool.execution.delta') {
    turn.status = 'running';
  }
  if (envelope.event === 'tool.execution.completed') {
    turn.status = envelope.data.isError === true ? 'error' : 'completed';
  }
  if (envelope.event === 'tool.result') {
    turn.status = envelope.data.isError === true ? 'error' : 'completed';
    turn.result = truncateToolResult(readContentArrayText(envelope.data.content));
  }
}

function ensureAssistantTurn(
  assistantTurns: Map<string, AssistantProjection>,
  entryId: string,
): AssistantProjection {
  const existing = assistantTurns.get(entryId);
  if (existing) return existing;
  const projection: AssistantProjection = {
    turn: {
      key: entryId,
      kind: 'assistant',
      rawMarkdown: '',
      streaming: true,
    },
    appended: false,
  };
  assistantTurns.set(entryId, projection);
  return projection;
}

function appendAssistantTurn(turns: ConversationTurn[], projection: AssistantProjection): void {
  if (projection.appended) return;
  projection.appended = true;
  turns.push(projection.turn);
}

function ensureActivityTurn(
  turns: ConversationTurn[],
  activityTurns: Map<string, ActivityTurn>,
  toolSpecs: Map<string, ToolSpec>,
  toolCallId: string,
  data: RuntimeEventData,
): ActivityTurn {
  const existing = activityTurns.get(toolCallId);
  if (existing) return existing;
  const spec = toolSpecs.get(toolCallId);
  const turn: ActivityTurn = {
    key: `tool-${toolCallId}`,
    kind: 'activity',
    toolCallId,
    toolName: readString(data.toolName) || spec?.toolName || 'Agent 工具',
    status: 'running',
    arguments: spec?.arguments ?? [],
    result: '',
  };
  activityTurns.set(toolCallId, turn);
  turns.push(turn);
  return turn;
}

function registerToolSpecs(
  value: unknown,
  toolSpecs: Map<string, ToolSpec>,
  activityTurns: Map<string, ActivityTurn>,
): void {
  const message = readRecord(value);
  if (!Array.isArray(message?.content)) return;
  for (const item of message.content) {
    const block = readRecord(item);
    if (readString(block?.type) !== 'tool_call') continue;
    const toolCallId = readString(block?.toolCallId);
    if (!toolCallId) continue;
    const spec: ToolSpec = {
      toolCallId,
      toolName: readString(block?.name) || 'Agent 工具',
      arguments: projectToolArguments(block?.arguments),
    };
    toolSpecs.set(toolCallId, spec);
    const activity = activityTurns.get(toolCallId);
    if (activity) {
      activity.toolName = spec.toolName;
      activity.arguments = spec.arguments;
    }
  }
}

function projectToolArguments(value: unknown): ToolArgumentRow[] {
  const rows: ToolArgumentRow[] = [];
  const record = readRecord(value);
  if (!record) return rows;
  for (const [key, item] of Object.entries(record)) {
    appendToolArgument(rows, key, item, 0);
    if (rows.length >= MAX_TOOL_ARGUMENT_ROWS) break;
  }
  return rows;
}

function appendToolArgument(
  rows: ToolArgumentRow[],
  key: string,
  value: unknown,
  depth: number,
): void {
  if (rows.length >= MAX_TOOL_ARGUMENT_ROWS) return;
  if (SENSITIVE_ARGUMENT_KEY.test(key)) {
    rows.push({ key, value: REDACTED_TOOL_VALUE, redacted: true });
    return;
  }
  if (Array.isArray(value) && value.length > 0 && depth < MAX_TOOL_ARGUMENT_DEPTH) {
    value.forEach((item, index) => appendToolArgument(rows, `${key}[${index}]`, item, depth + 1));
    return;
  }
  const record = readRecord(value);
  if (record && Object.keys(record).length > 0 && depth < MAX_TOOL_ARGUMENT_DEPTH) {
    for (const [childKey, childValue] of Object.entries(record)) {
      appendToolArgument(rows, `${key}.${childKey}`, childValue, depth + 1);
      if (rows.length >= MAX_TOOL_ARGUMENT_ROWS) return;
    }
    return;
  }
  const formatted = formatToolValue(value);
  rows.push({ key, value: formatted, redacted: formatted === REDACTED_TOOL_VALUE });
}

function formatToolValue(value: unknown): string {
  if (typeof value === 'string') {
    if (SENSITIVE_ARGUMENT_VALUE.test(value)) return REDACTED_TOOL_VALUE;
    const visible = ABSOLUTE_PATH.test(value) ? `…/${lastPathSegment(value)}` : value;
    return truncate(visible, MAX_TOOL_VALUE_LENGTH);
  }
  if (value === null) return 'null';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.length === 0 ? '[]' : '[…]';
  if (readRecord(value)) return Object.keys(value as RuntimeEventData).length === 0 ? '{}' : '{…}';
  return String(value ?? '');
}

function lastPathSegment(value: string): string {
  return value.replace(/\\/gu, '/').split('/').filter(Boolean).at(-1) || '本地路径';
}

function truncateToolResult(value: string): string {
  return truncate(value, MAX_TOOL_RESULT_LENGTH, '\n…输出已截断');
}

function truncate(value: string, limit: number, suffix = '…'): string {
  return value.length > limit ? `${value.slice(0, limit)}${suffix}` : value;
}

function readThinkingDisplay(data: RuntimeEventData, suffix: 'Title' | 'Summary'): string {
  return readString(data[`thinkingDisplay${suffix}`]) || readString(data[`display${suffix}`]);
}

function readMessageText(value: unknown): string {
  const message = readRecord(value);
  return readContentArrayText(message?.content);
}

function readContentArrayText(value: unknown): string {
  if (!Array.isArray(value)) return '';
  return value.map(readTextContent).filter(Boolean).join('\n');
}

function readTextContent(value: unknown): string {
  const content = readRecord(value);
  return readString(content?.text);
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === 'string');
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function readNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function readRecord(value: unknown): RuntimeEventData | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as RuntimeEventData;
}
