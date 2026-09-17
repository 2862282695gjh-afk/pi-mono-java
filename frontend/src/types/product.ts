export interface AgentOption {
  id: string;
  name: string;
  description: string;
  category: string;
}

export interface ThreadSummary {
  sessionId: string;
  title: string;
  agentName: string;
  lastActivityAt: string;
  pinned: boolean;
}

export interface UserTurn {
  key: string;
  kind: 'user';
  text: string;
  fileIds: string[];
}

export interface AssistantTurn {
  key: string;
  kind: 'assistant';
  rawMarkdown: string;
  streaming: boolean;
  unconfirmed?: boolean;
}

export interface ThinkingTurn {
  key: string;
  kind: 'thinking';
  status: 'running' | 'completed' | 'unconfirmed';
  title: string;
  content: string;
}

export interface ToolArgumentRow {
  key: string;
  value: string;
}

export interface ActivityTurn {
  key: string;
  kind: 'activity';
  toolCallId: string;
  toolName: string;
  status: 'running' | 'completed' | 'error' | 'confirming' | 'waiting_answer' | 'unconfirmed';
  arguments: ToolArgumentRow[];
  result: string;
  argumentValue?: Record<string, unknown>;
  resultBlocks?: string[];
  hasResult?: boolean;
  statusMessage?: string;
  errorCode?: string;
}

export interface NoticeTurn {
  key: string;
  kind: 'notice';
  text: string;
}

export interface PlanTurn {
  key: string;
  kind: 'plan';
  eventId: string;
  sourceEventId: string;
  rawMarkdown: string;
  decision?: 'approve' | 'revise';
  lifecycle?: 'stopping' | 'stopped' | 'failed' | 'ended';
}

interface QuestionTurnBase {
  key: string;
  kind: 'question';
  eventId: string;
  sourceEventId: string;
  answer?: import('./runtime').QuestionAnswer[];
  lifecycle?: 'stopping' | 'closed';
}

export type StructuredQuestionTurn = QuestionTurnBase & {
  questions: import('./runtime').RuntimeQuestion[];
  label?: never;
  context?: never;
};

export type ShowcaseQuestionTurn = QuestionTurnBase & {
  label: string;
  context?: Record<string, unknown>;
  questions?: never;
};

export type QuestionTurn = StructuredQuestionTurn | ShowcaseQuestionTurn;

export type ConversationTurn = UserTurn | ThinkingTurn | AssistantTurn | ActivityTurn | NoticeTurn | PlanTurn
  | QuestionTurn;
