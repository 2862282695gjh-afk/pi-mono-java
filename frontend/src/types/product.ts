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
  text: string;
  thinking: string;
  streaming: boolean;
}

export interface ActivityTurn {
  key: string;
  kind: 'activity';
  toolCallId: string;
  toolName: string;
  status: 'running' | 'completed' | 'error';
  result: string;
}

export type ConversationTurn = UserTurn | AssistantTurn | ActivityTurn;
