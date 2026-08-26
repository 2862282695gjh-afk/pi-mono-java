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
  updatedAt: string;
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
}

export interface ThinkingTurn {
  key: string;
  kind: 'thinking';
  status: 'running' | 'completed';
  title: string;
  summary: string;
}

export interface ToolArgumentRow {
  key: string;
  value: string;
  redacted: boolean;
}

export interface ActivityTurn {
  key: string;
  kind: 'activity';
  toolCallId: string;
  toolName: string;
  status: 'running' | 'completed' | 'error';
  arguments: ToolArgumentRow[];
  result: string;
}

export type ConversationTurn = UserTurn | ThinkingTurn | AssistantTurn | ActivityTurn;
