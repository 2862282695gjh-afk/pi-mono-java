import type { ActivityTurn, ConversationTurn } from '../types/product';
import type { RuntimeEvent } from '../types/runtime';
import { hasToolConfirmation, questionAnswer } from '../runtime/eventStore';

/** 输入是已按 eventId 合并的 Store，配置记录不冒充模型回答。 */
export function projectRuntimeEvents(events: RuntimeEvent[], observing = false): ConversationTurn[] {
  const turns: ConversationTurn[] = [];
  for (const event of events) {
    const key = event.eventId;
    switch (event.type) {
      case 'user.message':
        turns.push({ key, kind: 'user', text: event.content.filter((block) => block.type === 'text').map((block) => block.text).join(''),
          fileIds: event.content.filter((block) => block.type === 'file').map((block) => block.fileId) });
        break;
      case 'agent.message':
        if (event.content) turns.push({ key, kind: 'assistant', rawMarkdown: event.content,
          streaming: observing && event.phase === 'delta', unconfirmed: event.phase === 'delta' });
        break;
      case 'agent.thinking':
        turns.push({ key, kind: 'thinking', status: event.phase === 'completed' ? 'completed' : observing ? 'running' : 'unconfirmed',
          title: '思考过程', content: event.content });
        break;
      case 'agent.tool_call': {
        const result = matchingToolResult(events, event);
        const question = matchingQuestion(events, event);
        if (event.toolName === 'ExitPlanMode' && matchingPlan(events, event)) break;
        if (event.toolName === 'AskUserQuestion') {
          if (question) break;
          turns.push(questionToolStatus(event, result, observing));
          break;
        }
        const confirmed = hasToolConfirmation(events, event);
        const terminal = events.some((item) => item.type === 'session.status_idle' && item.sourceEventId === event.sourceEventId && item.reason !== 'confirming');
        let status: ActivityTurn['status'] = terminal || !observing ? 'unconfirmed' : 'running';
        if (event.requiresConfirmation && !confirmed && !terminal) status = 'confirming';
        if (result?.type === 'agent.tool_result') status = result.isError ? 'error' : 'completed';
        turns.push({ key, kind: 'activity', toolCallId: event.toolCallId, toolName: event.toolName,
          ...(event.displayName ? { displayName: event.displayName } : {}), status,
          arguments: argumentRows(event.arguments), argumentValue: event.arguments,
          result: result?.type === 'agent.tool_result' ? result.content.map((block) => block.text).join('\n') : '',
          resultBlocks: result?.type === 'agent.tool_result' ? result.content.map((block) => block.text) : undefined,
          hasResult: result?.type === 'agent.tool_result',
          errorCode: result?.type === 'agent.tool_result' ? result.errorCode : undefined });
        break;
      }
      case 'agent.tool_result':
        if (events.some((item) => item.type === 'agent.plan' && item.toolCallId === event.toolCallId
          && item.sourceEventId === event.sourceEventId)) break;
        if (events.some((item) => item.type === 'agent.question' && item.toolCallId === event.toolCallId
          && item.sourceEventId === event.sourceEventId)) break;
        if (!events.some((item) => item.type === 'agent.tool_call' && item.toolCallId === event.toolCallId && item.sourceEventId === event.sourceEventId)) {
          turns.push({ key, kind: 'activity', toolCallId: event.toolCallId, toolName: '工具调用（调用记录尚未读到）',
            status: event.isError ? 'error' : 'completed', arguments: [], result: event.content.map((block) => block.text).join('\n'),
            resultBlocks: event.content.map((block) => block.text), hasResult: true, errorCode: event.errorCode });
        }
        break;
      case 'agent.plan': {
        const decision = events.find((item) => item.type === 'user.plan_confirmation' && item.targetEventId === event.eventId);
        const terminal = events.find((item) => item.type === 'session.status_idle'
          && item.sourceEventId === event.sourceEventId && item.reason !== 'confirming');
        const interrupted = events.some((item) => item.type === 'user.interrupt' && item.targetEventId === event.sourceEventId);
        const lifecycle = terminal?.type === 'session.status_idle'
          ? terminal.reason === 'terminated' ? 'stopped' : terminal.reason === 'failed' ? 'failed' : 'ended'
          : interrupted ? 'stopping' : undefined;
        turns.push({ key, kind: 'plan', eventId: event.eventId, sourceEventId: event.sourceEventId,
          rawMarkdown: event.content, decision: decision?.type === 'user.plan_confirmation' ? decision.result : undefined,
          lifecycle });
        break;
      }
      case 'agent.todo_updated': break;
      case 'agent.question': {
        const answer = questionAnswer(events, event.eventId);
        const index = events.indexOf(event);
        const terminal = events.slice(index + 1).find((item) => item.type === 'session.status_idle'
          && item.sourceEventId === event.sourceEventId && item.reason !== 'confirming');
        const interrupted = events.slice(index + 1).some((item) => item.type === 'user.interrupt'
          && item.targetEventId === event.sourceEventId);
        turns.push({ key, kind: 'question', eventId: event.eventId, sourceEventId: event.sourceEventId,
          questions: event.questions, answer: answer?.type === 'user.question_answer' ? answer.answers : undefined,
          lifecycle: terminal ? 'closed' : interrupted ? 'stopping' : undefined });
        break;
      }
      case 'user.interrupt': {
        const ended = events.slice(events.indexOf(event) + 1).some((item) => item.type === 'session.status_idle'
          && item.sourceEventId === event.targetEventId && item.reason !== 'confirming');
        if (!ended) turns.push({ key, kind: 'notice', text: '停止中' });
        break;
      }
      case 'user.tool_confirmation': turns.push({ key, kind: 'notice', text: event.result === 'allow' ? '已允许本次工具调用。' : `已拒绝本次工具调用。${event.denyMessage ?? ''}` }); break;
      case 'user.plan_confirmation': break;
      case 'user.question_answer': break;
      case 'session.status_idle': {
        if (event.reason === 'confirming') break;
        if (event.reason === 'done' && hasCompletedAnswer(events, event)) break;
        const text = event.reason === 'done' ? '已完成'
          : event.reason === 'terminated' ? '已停止'
            : `执行失败${event.message ? `：${event.message}` : ''}`;
        turns.push({ key, kind: 'notice', text });
        break;
      }
      case 'session.model_changed': turns.push({ key, kind: 'notice', text: `模型已变更：${event.previousModelId} → ${event.modelId}` }); break;
      case 'session.thinking_changed': turns.push({ key, kind: 'notice', text: `后续思考展示已${event.thinking ? '开启' : '关闭'}；已有思考记录保留。` }); break;
      case 'session.mode_changed': turns.push({ key, kind: 'notice', text: event.mode === 'plan' ? '已切换到 Plan。' : '已切换到 Build。' }); break;
      case 'session.compacted': turns.push({ key, kind: 'notice', text: `上下文已压缩：${event.tokensBefore} → 约 ${event.estimatedTokensAfter} tokens` }); break;
    }
  }
  return turns;
}

function matchingToolResult(events: RuntimeEvent[], call: Extract<RuntimeEvent, { type: 'agent.tool_call' }>) {
  return events.find((item) => item.type === 'agent.tool_result' && item.toolCallId === call.toolCallId
    && item.sourceEventId === call.sourceEventId);
}

function matchingQuestion(events: RuntimeEvent[], call: Extract<RuntimeEvent, { type: 'agent.tool_call' }>) {
  return events.find((item) => item.type === 'agent.question' && item.toolCallId === call.toolCallId
    && item.sourceEventId === call.sourceEventId);
}

function matchingPlan(events: RuntimeEvent[], call: Extract<RuntimeEvent, { type: 'agent.tool_call' }>): boolean {
  return events.some((item) => item.type === 'agent.plan' && item.toolCallId === call.toolCallId
    && item.sourceEventId === call.sourceEventId);
}

function argumentRows(argumentsValue: Record<string, unknown>) {
  return Object.entries(argumentsValue).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : safeStringify(value),
  }));
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function questionToolStatus(call: Extract<RuntimeEvent, { type: 'agent.tool_call' }>, result: RuntimeEvent | undefined,
  observing: boolean): ActivityTurn {
  const failed = result?.type === 'agent.tool_result' && result.isError;
  const completedWithoutQuestion = result?.type === 'agent.tool_result' && !result.isError;
  return {
    key: call.eventId,
    kind: 'activity',
    toolCallId: call.toolCallId,
    toolName: call.toolName,
    ...(call.displayName ? { displayName: call.displayName } : {}),
    status: failed ? 'error' : completedWithoutQuestion || !observing ? 'unconfirmed' : 'running',
    arguments: [],
    result: '',
    hasResult: result?.type === 'agent.tool_result',
    statusMessage: failed ? briefQuestionError(result) : completedWithoutQuestion || !observing
      ? '问题记录尚未完整，正在同步'
      : '正在准备问题',
    errorCode: failed && result?.type === 'agent.tool_result' ? result.errorCode : undefined,
  };
}

function briefQuestionError(result: Extract<RuntimeEvent, { type: 'agent.tool_result' }>): string {
  const detail = result.content.map((block) => block.text).join(' ').replace(/\s+/gu, ' ').trim();
  if (!detail) return '提问失败';
  return `提问失败：${detail.length > 160 ? `${detail.slice(0, 159)}…` : detail}`;
}

function hasCompletedAnswer(events: RuntimeEvent[], idle: Extract<RuntimeEvent, { type: 'session.status_idle' }>): boolean {
  const idleIndex = events.indexOf(idle);
  return events.slice(0, idleIndex).some((item) => item.type === 'agent.message' && item.sourceEventId === idle.sourceEventId
    && item.phase === 'completed' && Boolean(item.content));
}
