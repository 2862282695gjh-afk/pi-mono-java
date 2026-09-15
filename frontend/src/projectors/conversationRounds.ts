import type {
  ActivityTurn,
  AssistantTurn,
  ConversationTurn,
  ThinkingTurn,
  NoticeTurn,
  PlanTurn,
  QuestionTurn,
  UserTurn,
} from '../types/product';

export interface AgentMessageBlock {
  kind: 'assistant';
  key: string;
  turn: AssistantTurn;
}

export interface AgentActivityBlock {
  kind: 'activity';
  key: string;
  turn: ThinkingTurn | ActivityTurn | NoticeTurn;
}

export interface AgentPlanBlock {
  kind: 'plan';
  key: string;
  turn: PlanTurn;
}
export interface AgentQuestionBlock {
  kind: 'question';
  key: string;
  turn: QuestionTurn;
}

export interface AgentRound {
  kind: 'agent';
  key: string;
  blocks: Array<AgentMessageBlock | AgentActivityBlock | AgentPlanBlock | AgentQuestionBlock>;
  copySource: string;
  active: boolean;
}

export interface UserTimelineItem {
  kind: 'user';
  key: string;
  turn: UserTurn;
}

export type ConversationTimelineItem = UserTimelineItem | AgentRound;

type AgentTurn = AssistantTurn | ThinkingTurn | ActivityTurn | NoticeTurn | PlanTurn | QuestionTurn;

export function groupConversationTurns(turns: ConversationTurn[]): ConversationTimelineItem[] {
  const items: ConversationTimelineItem[] = [];
  let pendingAgentTurns: AgentTurn[] = [];

  for (const turn of turns) {
    if (turn.kind === 'user') {
      appendAgentRound(items, pendingAgentTurns);
      pendingAgentTurns = [];
      items.push({ kind: 'user', key: turn.key, turn });
    } else {
      pendingAgentTurns.push(turn);
    }
  }
  appendAgentRound(items, pendingAgentTurns);
  return items;
}

function appendAgentRound(items: ConversationTimelineItem[], turns: AgentTurn[]): void {
  if (!turns.length) return;
  items.push({
    kind: 'agent',
    key: `round-${turns[0].key}`,
    blocks: groupAgentBlocks(turns),
    copySource: turns
      .filter((turn): turn is AssistantTurn => turn.kind === 'assistant' && !turn.unconfirmed && Boolean(turn.rawMarkdown))
      .map((turn) => turn.rawMarkdown)
      .join('\n\n'),
    active: turns.some(isActiveTurn),
  });
}

function groupAgentBlocks(turns: AgentTurn[]): Array<AgentMessageBlock | AgentActivityBlock | AgentPlanBlock | AgentQuestionBlock> {
  const blocks: Array<AgentMessageBlock | AgentActivityBlock | AgentPlanBlock | AgentQuestionBlock> = [];

  for (const turn of turns) {
    if (turn.kind === 'assistant') {
      blocks.push({ kind: 'assistant', key: turn.key, turn });
    } else if (turn.kind === 'plan') {
      blocks.push({ kind: 'plan', key: turn.key, turn });
    } else if (turn.kind === 'question') {
      blocks.push({ kind: 'question', key: turn.key, turn });
    } else {
      blocks.push({ kind: 'activity', key: `activity-${turn.key}`, turn });
    }
  }
  return blocks;
}

function isActiveTurn(turn: AgentTurn): boolean {
  if (turn.kind === 'notice' || turn.kind === 'plan' || turn.kind === 'question') return false;
  if (turn.kind === 'assistant') return turn.streaming;
  return turn.status === 'running';
}
