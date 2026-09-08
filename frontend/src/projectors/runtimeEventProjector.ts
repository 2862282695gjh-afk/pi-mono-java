import type { ActivityTurn, ConversationTurn } from '../types/product';
import type { RuntimeEvent } from '../types/runtime';

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
          title: '思考摘要', content: event.content });
        break;
      case 'agent.tool_call': {
        const result = events.find((item) => item.type === 'agent.tool_result' && item.toolCallId === event.toolCallId && item.sourceEventId === event.sourceEventId);
        const confirmed = events.some((item) => item.type === 'user.tool_confirmation' && item.toolCallId === event.toolCallId);
        const terminal = events.some((item) => item.type === 'session.status_idle' && item.sourceEventId === event.sourceEventId && item.reason !== 'confirming');
        let status: ActivityTurn['status'] = terminal || !observing ? 'unconfirmed' : 'running';
        if (event.requiresConfirmation && !confirmed && !terminal) status = 'confirming';
        if (result?.type === 'agent.tool_result') status = result.isError ? 'error' : 'completed';
        turns.push({ key, kind: 'activity', toolCallId: event.toolCallId, toolName: event.toolName, status,
          arguments: Object.entries(event.arguments).map(([key, value]) => ({ key, value: typeof value === 'string' ? value : JSON.stringify(value, null, 2) })),
          result: result?.type === 'agent.tool_result' ? result.content.map((block) => block.text).join('\n') : '',
          errorCode: result?.type === 'agent.tool_result' ? result.errorCode : undefined });
        break;
      }
      case 'agent.tool_result':
        if (!events.some((item) => item.type === 'agent.tool_call' && item.toolCallId === event.toolCallId && item.sourceEventId === event.sourceEventId)) {
          turns.push({ key, kind: 'activity', toolCallId: event.toolCallId, toolName: '工具调用（调用记录尚未读到）',
            status: event.isError ? 'error' : 'completed', arguments: [], result: event.content.map((block) => block.text).join('\n'), errorCode: event.errorCode });
        }
        break;
      case 'user.interrupt': turns.push({ key, kind: 'notice', text: '停止请求已接受，等待原任务实际结束。' }); break;
      case 'user.tool_confirmation': turns.push({ key, kind: 'notice', text: event.result === 'allow' ? '已允许本次工具调用。' : `已拒绝本次工具调用。${event.denyMessage ?? ''}` }); break;
      case 'session.status_idle':
        turns.push({ key, kind: 'notice', text: ({ done: '本轮已完成', terminated: '本轮已停止', confirming: '等待工具确认，任务尚未结束', failed: `本轮失败：${event.message ?? ''}` })[event.reason] });
        break;
      case 'session.model_changed': turns.push({ key, kind: 'notice', text: `模型已变更：${event.previousModelId} → ${event.modelId}` }); break;
      case 'session.thinking_changed': turns.push({ key, kind: 'notice', text: `后续思考摘要已${event.thinking ? '开启' : '关闭'}；已有摘要保留。` }); break;
      case 'session.compacted': turns.push({ key, kind: 'notice', text: `上下文已压缩：${event.tokensBefore} → 约 ${event.estimatedTokensAfter} tokens` }); break;
    }
  }
  return turns;
}
