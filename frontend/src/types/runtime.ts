export interface RuntimeSession {
  sessionId: string;
  agentId: string;
  displayName: string | null;
  modelId: string;
  state: 'idle' | 'running';
  thinking: boolean;
  createdAt: string;
  updatedAt?: string;
  lifetimeUsage?: Record<string, unknown>;
}

export interface AvailableModels { currentModelId: string; models: string[] }
export type UserContent = { type: 'text'; text: string } | { type: 'file'; fileId: string };
export type UserEvent =
  | { type: 'user.message'; content: UserContent[] }
  | { type: 'user.interrupt'; targetEventId: string }
  | { type: 'user.tool_confirmation'; toolCallId: string; result: 'allow' | 'deny'; denyMessage?: string };

type EventIdentity = { eventId: string; createdAt?: string };
export type RuntimeEvent = EventIdentity & (UserEvent
  | { type: 'agent.message' | 'agent.thinking'; sourceEventId: string; phase: 'delta' | 'completed'; content: string; usage?: Record<string, unknown> }
  | { type: 'agent.tool_call'; sourceEventId: string; toolCallId: string; toolName: string; arguments: Record<string, unknown>; requiresConfirmation: boolean }
  | { type: 'agent.tool_result'; sourceEventId: string; toolCallId: string; content: { type: 'text'; text: string }[]; isError: boolean; errorCode?: string }
  | { type: 'session.status_idle'; sourceEventId: string; reason: 'done' | 'failed' | 'terminated' | 'confirming'; errorCode?: string; message?: string }
  | { type: 'session.model_changed'; previousModelId: string; modelId: string; reason: string }
  | { type: 'session.thinking_changed'; previousThinking: boolean; thinking: boolean; reason: string }
  | { type: 'session.compacted'; tokensBefore: number; estimatedTokensAfter: number; reason: string; sourceEventId?: string });

export interface RuntimeHistoryPage { events: RuntimeEvent[]; nextPage: number | null }
export type SubmissionOutcome = 'confirmed' | 'uncertain';
export interface MessageSubmission { confirmation: Promise<SubmissionOutcome> }

export class RuntimeApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly outcomeUncertain: boolean;

  constructor(options: { message: string; status?: number; code?: string; outcomeUncertain?: boolean }) {
    super(options.message);
    this.name = 'RuntimeApiError';
    this.status = options.status ?? 0;
    this.code = options.code ?? 'NETWORK_ERROR';
    this.outcomeUncertain = options.outcomeUncertain ?? false;
  }
}
