import type {
  ActivityTurn,
  AssistantTurn,
  ConversationTurn,
  ThinkingTurn,
  UserTurn,
} from '../types/product';

export interface AgentMessageBlock {
  kind: 'assistant';
  key: string;
  turn: AssistantTurn;
}

export interface AgentActivityBlock {
  kind: 'activityGroup';
  key: string;
  turns: Array<ThinkingTurn | ActivityTurn>;
}

export interface AgentRound {
  kind: 'agent';
  key: string;
  blocks: Array<AgentMessageBlock | AgentActivityBlock>;
  copySource: string;
  active: boolean;
}

export interface UserTimelineItem {
  kind: 'user';
  key: string;
  turn: UserTurn;
}

export type ConversationTimelineItem = UserTimelineItem | AgentRound;

type AgentTurn = AssistantTurn | ThinkingTurn | ActivityTurn;

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
      .filter((turn): turn is AssistantTurn => turn.kind === 'assistant' && Boolean(turn.rawMarkdown))
      .map((turn) => turn.rawMarkdown)
      .join('\n\n'),
    active: turns.some(isActiveTurn),
  });
}

function groupAgentBlocks(turns: AgentTurn[]): Array<AgentMessageBlock | AgentActivityBlock> {
  const blocks: Array<AgentMessageBlock | AgentActivityBlock> = [];
  let activityTurns: Array<ThinkingTurn | ActivityTurn> = [];

  for (const turn of turns) {
    if (turn.kind === 'assistant') {
      appendActivityBlock(blocks, activityTurns);
      activityTurns = [];
      blocks.push({ kind: 'assistant', key: turn.key, turn });
    } else {
      activityTurns.push(turn);
    }
  }
  appendActivityBlock(blocks, activityTurns);
  return blocks;
}

function appendActivityBlock(
  blocks: Array<AgentMessageBlock | AgentActivityBlock>,
  turns: Array<ThinkingTurn | ActivityTurn>,
): void {
  if (!turns.length) return;
  blocks.push({ kind: 'activityGroup', key: `activity-${turns[0].key}`, turns });
}

function isActiveTurn(turn: AgentTurn): boolean {
  if (turn.kind === 'assistant') return turn.streaming;
  return turn.status === 'running';
}
