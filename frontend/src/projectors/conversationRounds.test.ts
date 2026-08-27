import { describe, expect, it } from 'vitest';
import { groupConversationTurns } from './conversationRounds';
import type { ConversationTurn } from '../types/product';

describe('groupConversationTurns', () => {
  it('groups one user request and its complete agent activity into one copyable round', () => {
    const turns: ConversationTurn[] = [
      { key: 'user-1', kind: 'user', text: '检查订单', fileIds: [] },
      {
        key: 'thinking-1',
        kind: 'thinking',
        status: 'completed',
        title: '分析过程',
        content: '先检查字段。',
      },
      {
        key: 'tool-1',
        kind: 'activity',
        toolCallId: 'call-1',
        toolName: 'Read',
        status: 'completed',
        arguments: [],
        result: '完成',
      },
      { key: 'assistant-1', kind: 'assistant', rawMarkdown: '第一段回答', streaming: false },
      { key: 'assistant-2', kind: 'assistant', rawMarkdown: '第二段回答', streaming: false },
    ];

    expect(groupConversationTurns(turns)).toEqual([
      { kind: 'user', key: 'user-1', turn: turns[0] },
      {
        kind: 'agent',
        key: 'round-thinking-1',
        blocks: [
          {
            kind: 'activity',
            key: 'activity-thinking-1',
            turn: turns[1],
          },
          {
            kind: 'activity',
            key: 'activity-tool-1',
            turn: turns[2],
          },
          { kind: 'assistant', key: 'assistant-1', turn: turns[3] },
          { kind: 'assistant', key: 'assistant-2', turn: turns[4] },
        ],
        copySource: '第一段回答\n\n第二段回答',
        active: false,
      },
    ]);
  });

  it('keeps later activity in chronological blocks and marks the round active', () => {
    const turns: ConversationTurn[] = [
      { key: 'assistant-1', kind: 'assistant', rawMarkdown: '正在处理', streaming: false },
      {
        key: 'tool-1',
        kind: 'activity',
        toolCallId: 'call-1',
        toolName: 'Grep',
        status: 'running',
        arguments: [],
        result: '',
      },
    ];

    expect(groupConversationTurns(turns)).toEqual([{
      kind: 'agent',
      key: 'round-assistant-1',
      blocks: [
        { kind: 'assistant', key: 'assistant-1', turn: turns[0] },
        { kind: 'activity', key: 'activity-tool-1', turn: turns[1] },
      ],
      copySource: '正在处理',
      active: true,
    }]);
  });
});
