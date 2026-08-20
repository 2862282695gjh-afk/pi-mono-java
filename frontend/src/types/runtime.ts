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
  session_id: string;
  agent_id: string;
  model_id: string;
  state: 'idle' | 'running';
  thinking: boolean;
  created_at: string;
  updated_at?: string;
}

export interface AvailableModels {
  current_model_id: string;
  models: string[];
}

export interface RuntimeHistoryPage {
  events: RuntimeEventData[];
  next_page?: string | null;
}

export interface RuntimeEventEnvelope {
  id?: string;
  event: string;
  data: RuntimeEventData;
}

export type RuntimeEventData = Record<string, unknown>;

export interface ControlAccepted {
  session_id: string;
  accepted_at: string;
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
