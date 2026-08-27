import http from 'node:http';

const port = Number.parseInt(process.env.CAMPUSCLAW_FIXTURE_PORT ?? '8080', 10);
const sessionId = 'session-design-review';
const agentId = 'agent-design-review';
let state = 'idle';
const activeStreams = new Set();

const session = () => ({
  sessionId,
  agentId,
  modelId: 'claude-sonnet-4-5',
  state,
  thinking: true,
  createdAt: '2026-08-26T08:00:00Z',
  updatedAt: '2026-08-26T08:08:00Z',
});

const history = [
  {
    type: 'user.message',
    entryId: 'user-history-1',
    entrySeq: 1,
    message: '请分析订单明细，列出异常项并给出处理建议。',
    fileIds: ['file-orders'],
  },
  {
    type: 'assistant.thinking.completed',
    entryId: 'thinking-history-1',
    entrySeq: 2,
    assistantEntryId: 'assistant-history-tool',
    contentIndex: 0,
    content: {
      type: 'thinking',
      text: '**检查计划**\n\n1. 确认订单表结构和字段含义\n2. 检查价格、数量与重复订单\n3. 避免把缺失值直接判定为异常',
    },
  },
  {
    type: 'assistant.message.completed',
    entryId: 'assistant-history-tool',
    entrySeq: 3,
    message: {
      role: 'assistant',
      content: [{
        type: 'tool_call',
        toolCallId: 'call-history-read',
        name: 'Read',
        arguments: {
          file: '/workspace/订单明细.xlsx',
          sheet: '订单明细',
          sessionId: 'session-design-review',
          headers: { authorization: 'Bearer fixture-review-token' },
        },
      }],
    },
  },
  {
    type: 'tool.result',
    entryId: 'tool-history-1',
    entrySeq: 4,
    toolCallId: 'call-history-read',
    toolName: 'Read',
    content: [{ type: 'text', text: '**读取完成**\n\n- 已读取 3,482 行订单\n- 工作表：`订单明细`' }],
    isError: false,
  },
  {
    type: 'assistant.message.completed',
    entryId: 'assistant-history-final',
    entrySeq: 5,
    message: {
      role: 'assistant',
      content: [{
        type: 'text',
        text: '## 异常汇总\n\n已读取文件，发现以下 3 类异常：\n\n| 异常类型 | 数量 | 处理建议 |\n| --- | ---: | --- |\n| 价格异常 | 24 | 复核定价 |\n| 数量异常 | 17 | 校验原始数据 |\n| 重复下单 | 9 | 合并并联系用户 |\n\n建议优先检查 `order_id` 重复记录。',
      }],
    },
  },
];

function sendJson(response, result, status = 200, headers = {}) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    ...headers,
  });
  response.end(JSON.stringify({ resCode: '0', resMsg: 'success', result }));
}

function sendEmpty(response, status = 204) {
  response.writeHead(status);
  response.end();
}

function writeEvent(response, event, data, id) {
  if (id) response.write(`id: ${id}\n`);
  response.write(`event: ${event}\n`);
  response.write(`data: ${JSON.stringify(data)}\n\n`);
}

function startExecution(response) {
  state = 'running';
  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  writeEvent(response, 'user.message', {
    entryId: 'user-live-1',
    entrySeq: 6,
    message: '继续检查价格和数量异常，并标记需要人工复核的订单。',
    fileIds: [],
  }, '6');
  writeEvent(response, 'assistant.thinking.delta', {
    assistantEntryId: 'assistant-live-tool',
    contentIndex: 0,
    delta: {
      type: 'thinking',
      text: '**检查顺序**\n\n1. 核对价格阈值和数量范围\n2. 检查重复订单\n3. 使用工具定位相关字段',
    },
  });
  writeEvent(response, 'assistant.message.completed', {
    entryId: 'assistant-live-tool',
    message: {
      role: 'assistant',
      content: [{
        type: 'tool_call',
        toolCallId: 'call-live-grep',
        name: 'Grep',
        arguments: { pattern: 'price|quantity', path: '/workspace/订单明细.xlsx' },
      }],
    },
  });
  writeEvent(response, 'tool.execution.started', {
    toolCallId: 'call-live-grep',
    toolName: 'Grep',
  });
  writeEvent(response, 'tool.result', {
    entryId: 'tool-live-grep',
    toolCallId: 'call-live-grep',
    toolName: 'Grep',
    content: [{ type: 'text', text: 'MATE_RESPONSE_INVALID' }],
    isError: true,
    errorMessage: '**调用失败**\n\nCampusMate 响应格式不正确。',
  }, '7');
  writeEvent(response, 'assistant.thinking.delta', {
    assistantEntryId: 'assistant-live-recovery',
    contentIndex: 0,
    delta: {
      type: 'thinking',
      text: '**失败判断**\n\n- 查询工具返回了无效响应\n- 保留当前任务上下文\n- 调整检查方式，避免重复提交同一个调用',
    },
  });
  const keepAlive = setInterval(() => response.write(': keep-alive\n\n'), 15_000);
  activeStreams.add({ response, keepAlive });
  response.on('close', () => {
    clearInterval(keepAlive);
    for (const stream of activeStreams) {
      if (stream.response === response) activeStreams.delete(stream);
    }
  });
}

function abortExecution(response) {
  state = 'idle';
  for (const stream of activeStreams) {
    clearInterval(stream.keepAlive);
    writeEvent(stream.response, 'session.status.idle', { status: 'idle' });
    writeEvent(stream.response, 'stream.end', { reason: 'aborted' });
    stream.response.end();
  }
  activeStreams.clear();
  sendEmpty(response);
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url || '/', `http://${request.headers.host}`);
  const path = url.pathname;

  if (request.method === 'POST' && path.endsWith(`/agents/${agentId}/sessions`)) {
    sendJson(response, session());
    return;
  }
  if (request.method === 'GET' && path.endsWith(`/sessions/${sessionId}`)) {
    sendJson(response, session(), 200, { ETag: '"design-v1"' });
    return;
  }
  if (request.method === 'GET' && path.endsWith(`/sessions/${sessionId}/models`)) {
    sendJson(response, {
      currentModelId: 'claude-sonnet-4-5',
      models: ['claude-sonnet-4-5', 'gpt-5.4'],
    });
    return;
  }
  if (request.method === 'GET' && path.endsWith(`/sessions/${sessionId}/events`)) {
    sendJson(response, { events: history, nextPage: null });
    return;
  }
  if (request.method === 'POST' && path.endsWith(`/sessions/${sessionId}/events`)) {
    startExecution(response);
    return;
  }
  if (request.method === 'POST' && path.endsWith(`/sessions/${sessionId}/abort`)) {
    abortExecution(response);
    return;
  }
  response.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify({ resCode: 'NOT_FOUND', resMsg: 'Not found' }));
});

server.listen(port, '127.0.0.1');
