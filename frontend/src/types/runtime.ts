export interface ResultBean<T> {
  resCode: string;
  resMsg: string;
  result: T;
}

export interface ErrorBean {
  resCode: string;
  resMsg: string;
}

export interface RuntimeSession {
  sessionId: string;
  agentId: string;
  modelId: string;
  state: 'idle' | 'running';
  thinking: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface AvailableModels {
  currentModelId: string;
  models: string[];
}

export interface RuntimeHistoryPage {
  events: RuntimeEventData[];
  nextPage?: string | null;
}

export interface RuntimeEventEnvelope {
  id?: string;
  event: string;
  data: RuntimeEventData;
}

export type RuntimeEventData = Record<string, unknown>;

export interface ControlAccepted {
  sessionId: string;
  acceptedAt: string;
}

export type SubmissionOutcome = 'confirmed' | 'uncertain';

export interface MessageSubmission {
  confirmation: Promise<SubmissionOutcome>;
}

export type FollowUpMode = 'steer' | 'queue';

export interface AcceptedControl {
  key: string;
  message: string;
  mode: FollowUpMode;
  acceptedAt: string;
}

export class RuntimeApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfter?: string;
  readonly outcomeUncertain: boolean;

  constructor(options: {
    message: string;
    status?: number;
    code?: string;
    retryAfter?: string;
    outcomeUncertain?: boolean;
  }) {
    super(options.message);
    this.name = 'RuntimeApiError';
    this.status = options.status ?? 0;
    this.code = options.code ?? 'NETWORK_ERROR';
    this.retryAfter = options.retryAfter;
    this.outcomeUncertain = options.outcomeUncertain ?? false;
  }
}
