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
        key: 'thinking-entry-assistant-0',
        kind: 'thinking',
        status: 'running',
        title: '正在分析',
        summary: '',
      },
      {
        key: 'entry-assistant',
        kind: 'assistant',
        rawMarkdown: '发现 3 条异常。',
        streaming: false,
      },
      {
        key: 'tool-call-1',
        kind: 'activity',
        toolCallId: 'call-1',
        toolName: 'query_orders',
        status: 'completed',
        arguments: [],
        result: '3 rows',
      },
    ]);
  });

  it('replaces a Markdown preview with the authoritative completed source', () => {
    const events: RuntimeEventEnvelope[] = [
      {
        event: 'assistant.message.started',
        data: { entryId: 'entry-assistant', role: 'assistant' },
      },
      {
        event: 'assistant.message.delta',
        data: {
          entryId: 'entry-assistant',
          delta: { type: 'text', text: '| incomplete' },
        },
      },
      {
        event: 'assistant.message.completed',
        data: {
          entryId: 'entry-assistant',
          message: {
            role: 'assistant',
            content: [{ type: 'text', text: '| Name |\n| --- |\n| complete |' }],
          },
        },
      },
    ];

    expect(projectRuntimeEvents(events)).toEqual([{
      key: 'entry-assistant',
      kind: 'assistant',
      rawMarkdown: '| Name |\n| --- |\n| complete |',
      streaming: false,
    }]);
  });

  it('never projects raw thinking content and only exposes safe display fields', () => {
    const events: RuntimeEventEnvelope[] = [
      {
        event: 'assistant.thinking.delta',
        data: {
          assistantEntryId: 'entry-assistant',
          contentIndex: 0,
          delta: { type: 'thinking', text: '不应展示的原始思维文本' },
        },
      },
      {
        event: 'assistant.thinking.completed',
        data: {
          assistantEntryId: 'entry-assistant',
          contentIndex: 0,
          content: { type: 'thinking', text: '同样不应展示' },
          thinkingDisplayTitle: '已检查数据范围',
          thinkingDisplaySummary: '已核对近 30 天的订单状态与异常分布。',
        },
      },
    ];

    const turns = projectRuntimeEvents(events);
    expect(turns).toEqual([{
      key: 'thinking-entry-assistant-0',
      kind: 'thinking',
      status: 'completed',
      title: '已检查数据范围',
      summary: '已核对近 30 天的订单状态与异常分布。',
    }]);
    expect(JSON.stringify(turns)).not.toContain('原始思维');
  });

  it('waits for tool execution and presents redacted, bounded arguments', () => {
    const events: RuntimeEventEnvelope[] = [
      {
        event: 'assistant.message.completed',
        data: {
          entryId: 'entry-assistant',
          message: {
            role: 'assistant',
            content: [{
              type: 'tool_call',
              toolCallId: 'call-1',
              name: 'Read',
              arguments: {
                path: '/Users/example/private/project/report.md',
                token: 'secret-token',
                headers: { authorization: 'Bearer visible-secret' },
                note: '  Bearer another-secret',
                query: '检查报告',
              },
            }],
          },
        },
      },
      {
        event: 'tool.execution.started',
        data: { toolCallId: 'call-1', toolName: 'Read' },
      },
      {
        event: 'tool.result',
        data: {
          toolCallId: 'call-1',
          toolName: 'Read',
          content: [{ type: 'text', text: '读取完成' }],
          isError: false,
        },
      },
    ];

    expect(projectRuntimeEvents(events)).toEqual([{
      key: 'tool-call-1',
      kind: 'activity',
      toolCallId: 'call-1',
      toolName: 'Read',
      status: 'completed',
      arguments: [
        { key: 'path', value: '…/report.md', redacted: false },
        { key: 'token', value: '已隐藏', redacted: true },
        { key: 'headers.authorization', value: '已隐藏', redacted: true },
        { key: 'note', value: '已隐藏', redacted: true },
        { key: 'query', value: '检查报告', redacted: false },
      ],
      result: '读取完成',
    }]);
  });

  it('presents the localized public message for a failed tool result', () => {
    const events: RuntimeEventEnvelope[] = [{
      event: 'tool.result',
      data: {
        toolCallId: 'call-1',
        toolName: 'CallMateTool',
        content: [{ type: 'text', text: 'MATE_RESPONSE_INVALID' }],
        isError: true,
        errorCode: 'MATE_RESPONSE_INVALID',
        errorMessage: 'CampusMate 响应格式不正确。',
      },
    }];

    expect(projectRuntimeEvents(events)).toEqual([{
      key: 'tool-call-1',
      kind: 'activity',
      toolCallId: 'call-1',
      toolName: 'CallMateTool',
      status: 'error',
      arguments: [],
      result: 'CampusMate 响应格式不正确。',
    }]);
  });

  it('does not create a visible turn for a tool proposal before execution starts', () => {
    const events: RuntimeEventEnvelope[] = [{
      event: 'assistant.message.completed',
      data: {
        entryId: 'entry-assistant',
        message: {
          role: 'assistant',
          content: [{
            type: 'tool_call',
            toolCallId: 'call-1',
            name: 'Find',
            arguments: { pattern: '*.md' },
          }],
        },
      },
    }];

    expect(projectRuntimeEvents(events)).toEqual([]);
  });
});
