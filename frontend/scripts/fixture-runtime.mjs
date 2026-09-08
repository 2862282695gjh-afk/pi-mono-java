// 浏览器验收专用、仅监听回环地址；不是 Runtime 实现或生产 fallback。
import { createServer } from 'node:http';
const port = Number(process.env.FIXTURE_PORT || 4319);
let sequence = 0;
let version = 1;
let history = [];
let rootId = '';
const session = { sessionId: 'session-fixture', agentId: 'agent-fixture', displayName: null,
  modelId: 'model-primary', state: 'idle', thinking: true, createdAt: new Date().toISOString() };
const make = (type, data = {}) => ({ eventId: `fixture-${++sequence}`, type, createdAt: new Date().toISOString(), ...data });
const json = (response, result, status = 200, headers = {}) => {
  response.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  response.end(JSON.stringify({ resCode: '0', resMsg: 'fixture', result }));
};
function stream(response, events) {
  response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
  for (const event of events) {
    history.push(event);
    response.write(`data: ${JSON.stringify(event)}\n\n`);
  }
  response.end();
}
function startMessage(response, input) {
  const receipt = make('user.message', { content: input.content });
  rootId = receipt.eventId; session.state = 'running'; version++;
  stream(response, [receipt,
    make('agent.thinking', { phase: 'completed', sourceEventId: rootId, content: '先核对巡检范围，再查询公开的园区记录。' }),
    make('agent.tool_call', { sourceEventId: rootId, toolCallId: `tool-${rootId}`, toolName: 'CallMateTool',
      arguments: { tool: 'query_inspections', args: { campus: '东区' } }, requiresConfirmation: true }),
    make('session.status_idle', { sourceEventId: rootId, reason: 'confirming' }),
  ]);
}
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    let raw = '';
    for await (const chunk of request) { raw += chunk; if (raw.length > 128 * 1024) throw new Error('too large'); }
    const body = raw ? JSON.parse(raw) : {};
    if (request.method === 'POST' && /\/agents\/[^/]+\/sessions$/u.test(url.pathname)) {
      history = []; session.state = 'idle'; return json(response, session, 201);
    }
    if (request.method === 'GET' && url.pathname.endsWith('/events')) {
      const offset = (Number(url.searchParams.get('page') || 1) - 1) * 200;
      return json(response, { events: history.slice(offset, offset + 200), nextPage: offset + 200 < history.length ? offset / 200 + 2 : null });
    }
    if (url.pathname.endsWith('/models')) return json(response, { currentModelId: session.modelId, models: ['model-primary', 'model-secondary'] });
    if (url.pathname.endsWith('/commands')) {
      const hints = { name: '[显示名称]', model: '[modelId]', thinking: '[on|off]' };
      const names = session.state === 'running' ? ['help', 'status', 'model', 'skills'] : ['help', 'status', 'name', 'model', 'thinking', 'compact', 'skills', 'skill:inspect'];
      return json(response, { commands: names.map((name) => ({ name, kind: name.startsWith('skill:') ? 'skill' : 'builtin',
        description: { help: '查看 Agent 用途和使用场景', status: '查看当前会话状态', model: '查看或切换模型', compact: '压缩当前上下文', 'skill:inspect': '梳理园区巡检重点' }[name] || '查看或修改当前会话配置',
        ...(session.state === 'idle' && hints[name] ? { input: { hint: hints[name] } } : {}),
      })) });
    }
    if (request.method === 'POST' && url.pathname.endsWith('/command')) {
      if (body.name.startsWith('skill:')) return startMessage(response, { content: [{ type: 'text', text: `/${body.name}${body.arguments ? ` ${body.arguments}` : ''}` }] });
      if (body.name === 'help') return json(response, { displayName: '园区运维助手', description: ['协助梳理巡检、查询记录和整理处理建议。'], userCases: ['梳理明天的巡检重点', '检查东区的设备异常'] });
      if (body.name === 'skills') return json(response, { skills: [{ name: 'inspect', description: '园区巡检' }] });
      if (body.name === 'compact') return json(response, { compacted: false });
      if (body.name === 'model' && !body.arguments?.trim()) return json(response, { currentModelId: session.modelId, models: ['model-primary', 'model-secondary'] });
      if (body.name === 'model' && body.arguments) session.modelId = body.arguments;
      if (body.name === 'name' && body.arguments) session.displayName = body.arguments;
      if (body.name === 'thinking' && body.arguments) session.thinking = body.arguments === 'on';
      return json(response, session, 200, { ETag: `"v${++version}"` });
    }
    if (request.method === 'POST' && url.pathname.endsWith('/events')) {
      const input = body.event;
      if (input.type === 'user.message') return startMessage(response, input);
      const receipt = make(input.type, Object.fromEntries(Object.entries(input).filter(([key]) => key !== 'type')));
      session.state = 'idle'; version++;
      if (input.type === 'user.interrupt') return stream(response, [receipt, make('session.status_idle', { sourceEventId: rootId, reason: 'terminated' })]);
      const denied = input.result === 'deny';
      return stream(response, [receipt,
        make('agent.tool_result', { sourceEventId: rootId, toolCallId: input.toolCallId, content: [{ type: 'text', text: denied ? '本次调用被用户拒绝。' : '发现 3 条待处理巡检记录。' }], isError: denied, ...(denied ? { errorCode: 'TOOL_CONFIRMATION_DENIED' } : {}) }),
        make('agent.message', { sourceEventId: rootId, phase: 'completed', content: denied ? '已跳过查询。你可以补充其他处理要求。' : '明天建议优先检查：\n\n1. 东区设备告警。\n2. 未关闭的巡检记录。\n3. 值班人员交接情况。' }),
        make('session.status_idle', { sourceEventId: rootId, reason: 'done' }),
      ]);
    }
    if (request.method === 'PUT') { Object.assign(session, body); version++; }
    if (request.method === 'DELETE') { history = []; response.writeHead(204); return response.end(); }
    return json(response, session, 200, { ETag: `"v${version}"` });
  } catch {
    response.writeHead(400, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ resCode: 'FIXTURE_INVALID_REQUEST', resMsg: '仅用于已声明的契约样本' }));
  }
});
server.listen(port, '127.0.0.1', () => process.stdout.write(`Contract fixture listening on 127.0.0.1:${port}\n`));
