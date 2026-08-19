export type CredentialMode = 'jwt' | 'appkey';

export interface RuntimeAuth {
  credentialId: string;
  credentialMode: CredentialMode;
  credentialSecret: string;
}

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
  events: Record<string, unknown>[];
  next_page?: string;
}

export interface RuntimeSseEvent {
  id?: string;
  event: string;
  data: Record<string, unknown>;
}

export interface ControlAccepted {
  session_id: string;
  accepted_at: string;
}
