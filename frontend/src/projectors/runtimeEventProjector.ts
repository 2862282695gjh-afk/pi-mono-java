import type {
  ActivityTurn,
  AssistantTurn,
  ConversationTurn,
  UserTurn,
} from '../types/product';
import type { RuntimeEventData, RuntimeEventEnvelope } from '../types/runtime';

export function projectRuntimeEvents(events: RuntimeEventEnvelope[]): ConversationTurn[] {
  const turns: ConversationTurn[] = [];
  const assistantTurns = new Map<string, AssistantTurn>();
  const activityTurns = new Map<string, ActivityTurn>();

  for (const envelope of events) {
    if (envelope.event === 'user.message') {
      appendUserTurn(turns, envelope.data);
    } else if (envelope.event.startsWith('assistant.message.')) {
      projectAssistantEvent(turns, assistantTurns, envelope);
    } else if (envelope.event.startsWith('assistant.thinking.')) {
      projectThinkingEvent(turns, assistantTurns, envelope);
    } else if (envelope.event.startsWith('tool.')) {
      projectToolEvent(turns, activityTurns, envelope);
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
  assistantTurns: Map<string, AssistantTurn>,
  envelope: RuntimeEventEnvelope,
): void {
  const entryId = readString(envelope.data.entryId);
  if (!entryId) return;
  const turn = ensureAssistantTurn(turns, assistantTurns, entryId);

  if (envelope.event === 'assistant.message.delta') {
    turn.rawMarkdown += readTextContent(envelope.data.delta);
  }
  if (envelope.event === 'assistant.message.completed') {
    turn.rawMarkdown = readMessageText(envelope.data.message);
    turn.streaming = false;
  }
}

function projectThinkingEvent(
  turns: ConversationTurn[],
  assistantTurns: Map<string, AssistantTurn>,
  envelope: RuntimeEventEnvelope,
): void {
  const assistantEntryId = readString(envelope.data.assistantEntryId);
  if (!assistantEntryId) return;
  const turn = ensureAssistantTurn(turns, assistantTurns, assistantEntryId);

  if (envelope.event === 'assistant.thinking.delta') {
    turn.thinking += readTextContent(envelope.data.delta);
  }
  if (envelope.event === 'assistant.thinking.completed') {
    turn.thinking = readTextContent(envelope.data.content) || turn.thinking;
  }
}

function projectToolEvent(
  turns: ConversationTurn[],
  activityTurns: Map<string, ActivityTurn>,
  envelope: RuntimeEventEnvelope,
): void {
  const toolCallId = readString(envelope.data.toolCallId);
  if (!toolCallId) return;
  const turn = ensureActivityTurn(turns, activityTurns, toolCallId, envelope.data);

  if (envelope.event === 'tool.execution.completed') {
    turn.status = envelope.data.isError === true ? 'error' : 'completed';
  }
  if (envelope.event === 'tool.result') {
    turn.status = envelope.data.isError === true ? 'error' : 'completed';
    turn.result = readContentArrayText(envelope.data.content);
  }
}

function ensureAssistantTurn(
  turns: ConversationTurn[],
  assistantTurns: Map<string, AssistantTurn>,
  entryId: string,
): AssistantTurn {
  const existing = assistantTurns.get(entryId);
  if (existing) return existing;
  const turn: AssistantTurn = {
    key: entryId,
    kind: 'assistant',
    rawMarkdown: '',
    thinking: '',
    streaming: true,
  };
  assistantTurns.set(entryId, turn);
  turns.push(turn);
  return turn;
}

function ensureActivityTurn(
  turns: ConversationTurn[],
  activityTurns: Map<string, ActivityTurn>,
  toolCallId: string,
  data: RuntimeEventData,
): ActivityTurn {
  const existing = activityTurns.get(toolCallId);
  if (existing) return existing;
  const turn: ActivityTurn = {
    key: `tool-${toolCallId}`,
    kind: 'activity',
    toolCallId,
    toolName: readString(data.toolName) || 'Agent 工具',
    status: 'running',
    result: '',
  };
  activityTurns.set(toolCallId, turn);
  turns.push(turn);
  return turn;
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

function readRecord(value: unknown): RuntimeEventData | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as RuntimeEventData;
}
