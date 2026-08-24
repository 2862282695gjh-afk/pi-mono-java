import { describe, expect, it } from 'vitest';
import { projectRuntimeEvents } from './runtimeEventProjector';
import type { RuntimeEventEnvelope } from '../types/runtime';

describe('projectRuntimeEvents', () => {
  it('projects HTTP 1.38 lowerCamelCase event fields', () => {
    const events: RuntimeEventEnvelope[] = [
      {
        id: '17',
        event: 'user.message',
        data: {
          entryId: 'entry-user',
          entrySeq: 17,
          message: '检查订单',
          fileIds: ['file-1'],
        },
      },
      {
        event: 'assistant.message.started',
        data: { entryId: 'entry-assistant', role: 'assistant' },
      },
      {
        event: 'assistant.thinking.delta',
        data: {
          assistantEntryId: 'entry-assistant',
          contentIndex: 0,
          delta: { type: 'thinking', text: '正在定位异常。' },
        },
      },
      {
        event: 'assistant.message.completed',
        data: {
          entryId: 'entry-assistant',
          entrySeq: 18,
          message: { role: 'assistant', content: [{ type: 'text', text: '发现 3 条异常。' }] },
          finishReason: 'stop',
        },
      },
      {
        event: 'tool.execution.started',
        data: { toolCallId: 'call-1', toolName: 'query_orders' },
      },
      {
        id: '19',
        event: 'tool.result',
        data: {
          entryId: 'entry-tool',
          entrySeq: 19,
          toolCallId: 'call-1',
          toolName: 'query_orders',
          content: [{ type: 'text', text: '3 rows' }],
          isError: false,
        },
      },
    ];

    expect(projectRuntimeEvents(events)).toEqual([
      {
        key: 'entry-user',
        kind: 'user',
        text: '检查订单',
        fileIds: ['file-1'],
      },
      {
        key: 'entry-assistant',
        kind: 'assistant',
        text: '发现 3 条异常。',
        thinking: '正在定位异常。',
        streaming: false,
      },
      {
        key: 'tool-call-1',
        kind: 'activity',
        toolCallId: 'call-1',
        toolName: 'query_orders',
        status: 'completed',
        result: '3 rows',
      },
    ]);
  });
});
