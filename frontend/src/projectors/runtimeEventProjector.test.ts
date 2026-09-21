import { describe, expect, it } from 'vitest';
import { projectRuntimeEvents } from './runtimeEventProjector';
import { eventFixture, idleFixture, questionFixture, showcaseQuestionFixture, userFixture } from '../runtime/fixtures';

describe('Runtime Events presentation', () => {
  it('replaces a stopping notice with its terminal result without affecting another root', () => {
    const interrupt = eventFixture({ eventId: 'stop', type: 'user.interrupt', targetEventId: 'root-a' });
    expect(projectRuntimeEvents([interrupt])).toEqual([expect.objectContaining({ text: '停止中' })]);
    expect(projectRuntimeEvents([interrupt, idleFixture('terminated')])).toEqual([expect.objectContaining({ text: '已停止' })]);
    expect(projectRuntimeEvents([interrupt, eventFixture({ eventId: 'other-end', type: 'session.status_idle', reason: 'terminated', sourceEventId: 'other-root' })]))
      .toEqual([expect.objectContaining({ text: '停止中' }), expect.objectContaining({ text: '已停止' })]);
  });
  it('does not invent an answer for an empty authoritative message', () => {
    expect(projectRuntimeEvents([eventFixture({ eventId: 'a', type: 'agent.message', phase: 'completed', sourceEventId: 'root-a', content: '' })])).toEqual([]);
  });
  it('shows public thinking history independently of current settings', () => {
    const turns = projectRuntimeEvents([
      eventFixture({ eventId: 't', type: 'agent.thinking', phase: 'completed', sourceEventId: 'root-a', content: '原始推理' }),
      eventFixture({ eventId: 'setting', type: 'session.thinking_changed', thinking: false, previousThinking: true, reason: 'user' }),
    ]);
    expect(turns[0]).toMatchObject({ kind: 'thinking', title: '思考过程', content: '原始推理', status: 'completed' });
    expect(turns[1].kind).toBe('notice');
  });
  it('keeps full text blocks, explicit outcome and Runtime Mate argument wrapper', () => {
    const long = '文字'.repeat(5000);
    const turns = projectRuntimeEvents([
      userFixture(), eventFixture({ eventId: 'c', type: 'agent.tool_call', toolCallId: 'tool', sourceEventId: 'root-a', toolName: 'CallMateTool', requiresConfirmation: false, arguments: { tool: 'query', args: { area: '校园' } } }),
      eventFixture({ eventId: 'r', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'tool', content: [{ type: 'text', text: long }, { type: 'text', text: '' }], isError: false }), idleFixture(),
    ]);
    expect(turns[1]).toMatchObject({ toolName: 'CallMateTool', status: 'completed', result: `${long}\n`, arguments: [{ key: 'tool', value: 'query' }, { key: 'args', value: '{\n  "area": "校园"\n}' }] });
    expect(turns[2]).toMatchObject({ kind: 'notice', text: '已完成' });
  });
  it('keeps the display snapshot separate from the execution identity in every tool state', () => {
    const call = eventFixture({ eventId: 'display-call', type: 'agent.tool_call', toolCallId: 'display-tool',
      sourceEventId: 'root-a', toolName: 'Read', displayName: '读取安全配置', requiresConfirmation: true, arguments: { path: 'a.txt' } });
    const confirming = projectRuntimeEvents([userFixture(), call, idleFixture('confirming')], true);
    expect(confirming.find((turn) => turn.kind === 'activity')).toMatchObject({
      toolName: 'Read', displayName: '读取安全配置', status: 'confirming',
    });
    const success = eventFixture({ eventId: 'display-result', type: 'agent.tool_result', toolCallId: 'display-tool',
      sourceEventId: 'root-a', content: [{ type: 'text', text: 'ok' }], isError: false });
    expect(projectRuntimeEvents([userFixture(), call, success]).find((turn) => turn.kind === 'activity')).toMatchObject({
      toolName: 'Read', displayName: '读取安全配置', status: 'completed',
    });
    const failed = { ...success, eventId: 'display-failed', isError: true, errorCode: 'TOOL_FAILED' };
    expect(projectRuntimeEvents([userFixture(), call, failed]).find((turn) => turn.kind === 'activity')).toMatchObject({
      toolName: 'Read', displayName: '读取安全配置', status: 'error',
    });
  });
  it('replaces a result-only placeholder when the matching call record becomes available', () => {
    const result = eventFixture({ eventId: 'early-result', type: 'agent.tool_result', toolCallId: 'late-tool',
      sourceEventId: 'root-a', content: [{ type: 'text', text: 'ok' }], isError: false });
    expect(projectRuntimeEvents([result])[0]).toMatchObject({ toolName: '工具调用（调用记录尚未读到）' });
    const call = eventFixture({ eventId: 'late-call', type: 'agent.tool_call', toolCallId: 'late-tool',
      sourceEventId: 'root-a', toolName: 'CallMateTool', displayName: '查询工单',
      arguments: { tool: 'query_ticket', args: {} }, requiresConfirmation: false });
    const activities = projectRuntimeEvents([result, call]).filter((turn) => turn.kind === 'activity');
    expect(activities).toEqual([expect.objectContaining({ key: 'late-call', displayName: '查询工单', status: 'completed' })]);
  });
  it('marks a disconnected preview as unconfirmed rather than completed', () => {
    expect(projectRuntimeEvents([{ eventId: 'p', type: 'agent.message', phase: 'delta', sourceEventId: 'root-a', content: '半条' }])[0]).toMatchObject({ streaming: false, unconfirmed: true });
  });
  it('renders plan previews as read-only streaming drafts until completion replaces them', () => {
    const preview = { eventId: 'plan', type: 'agent.plan', phase: 'delta', sourceEventId: 'root-a',
      content: '# 方案' } as const;
    expect(projectRuntimeEvents([preview], true)[0]).toMatchObject({
      kind: 'plan', streaming: true, unconfirmed: true, rawMarkdown: '# 方案',
    });
    expect(projectRuntimeEvents([preview], false)[0]).toMatchObject({ streaming: false, unconfirmed: true });
    const completed = eventFixture({ ...preview, phase: 'completed', toolCallId: 'exit' });
    expect(projectRuntimeEvents([completed], true)[0]).toMatchObject({ streaming: false, unconfirmed: false });
  });
  it('does not close a later draft with an earlier confirming event from the same root', () => {
    const confirming = idleFixture('confirming');
    const preview = { eventId: 'plan', type: 'agent.plan', phase: 'delta', sourceEventId: 'root-a',
      content: '# 新草稿' } as const;
    expect(projectRuntimeEvents([confirming, preview], true)[0]).toMatchObject({
      kind: 'plan', streaming: true, unconfirmed: true, lifecycle: undefined,
    });
  });
  it('does not apply an earlier confirmation to a later message with the same tool ID', () => {
    const call = eventFixture({ eventId: 'old-call', type: 'agent.tool_call', toolCallId: 'same-id', sourceEventId: 'old-root', toolName: 'CallMateTool', arguments: {}, requiresConfirmation: true });
    const receipt = eventFixture({ eventId: 'old-confirm', type: 'user.tool_confirmation', toolCallId: 'same-id', result: 'allow' });
    const currentCall = { ...call, eventId: 'new-call', sourceEventId: 'root-a' };
    const turns = projectRuntimeEvents([userFixture('old-root'), call, receipt, userFixture(), currentCall, idleFixture('confirming')], true);
    expect(turns.find((turn) => turn.key === 'new-call')).toMatchObject({ kind: 'activity', status: 'confirming' });
  });
  it('renders the authoritative plan once and never parses TodoUpdate text for progress', () => {
    const call = eventFixture({ eventId: 'exit-call', type: 'agent.tool_call', toolCallId: 'exit', sourceEventId: 'root-a',
      toolName: 'ExitPlanMode', arguments: {}, requiresConfirmation: false });
    const plan = eventFixture({ eventId: 'plan', type: 'agent.plan', sourceEventId: 'root-a', toolCallId: 'exit', content: '# 方案' });
    const result = eventFixture({ eventId: 'exit-result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'exit',
      content: [{ type: 'text', text: '{"result":"approved"}' }], isError: false });
    const turns = projectRuntimeEvents([userFixture(), call, plan, result]);
    expect(turns.filter((turn) => turn.kind === 'plan')).toEqual([
      expect.objectContaining({ eventId: 'plan', rawMarkdown: '# 方案' }),
    ]);
    expect(turns.some((turn) => turn.kind === 'activity' && turn.toolName === 'ExitPlanMode')).toBe(false);
  });
  it('keeps a failed ExitPlanMode call visible when no agent.plan was committed', () => {
    const call = eventFixture({ eventId: 'exit-call', type: 'agent.tool_call', toolCallId: 'exit', sourceEventId: 'root-a',
      toolName: 'ExitPlanMode', arguments: { plan: 'invalid' }, requiresConfirmation: false });
    const result = eventFixture({ eventId: 'exit-result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'exit',
      content: [{ type: 'text', text: '工具参数不符合约束，本次调用未执行。' }], isError: true, errorCode: 'TOOL_ARGUMENTS_INVALID' });
    expect(projectRuntimeEvents([userFixture(), call, result])).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'activity', toolName: 'ExitPlanMode', status: 'error' }),
    ]));
  });
  it('marks an undecided plan as stopped after its root terminates', () => {
    const plan = eventFixture({ eventId: 'plan', type: 'agent.plan', sourceEventId: 'root-a',
      toolCallId: 'exit', content: '# 方案' });
    const terminal = idleFixture('terminated');

    expect(projectRuntimeEvents([userFixture(), plan, terminal])).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'plan', eventId: 'plan', lifecycle: 'stopped' }),
    ]));
  });
  it('projects questions from their public event and attaches only the exact target answer', () => {
    const oldAnswer = eventFixture({ eventId: 'old-answer', type: 'user.question_answer', targetEventId: 'old-question',
      answers: [{ id: 'scope', selected: ['全部'] }] });
    const answer = eventFixture({ eventId: 'answer', type: 'user.question_answer', targetEventId: 'question-a',
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '正文' }] }) as Extract<
        import('../types/runtime').RuntimeEvent, { type: 'user.question_answer' }>;
    const turns = projectRuntimeEvents([userFixture(), oldAnswer, questionFixture(), answer]);
    expect(turns.find((turn) => turn.kind === 'question')).toEqual(expect.objectContaining({
      kind: 'question', eventId: 'question-a', answer: answer.answers,
    }));
    expect(turns.some((turn) => turn.kind === 'notice' && turn.key === 'answer')).toBe(false);
    const waiting = projectRuntimeEvents([userFixture(), eventFixture({ eventId: 'ask', type: 'agent.tool_call',
      sourceEventId: 'root-a', toolCallId: 'ask-a', toolName: 'AskUserQuestion', arguments: {}, requiresConfirmation: false }),
    questionFixture(), idleFixture('confirming')]);
    expect(waiting.filter((turn) => turn.kind === 'question')).toHaveLength(1);
    expect(waiting.some((turn) => turn.kind === 'activity' && turn.toolName === 'AskUserQuestion')).toBe(false);
  });
  it('absorbs only an exactly associated AskUserQuestion call and result', () => {
    const call = eventFixture({ eventId: 'ask', type: 'agent.tool_call', sourceEventId: 'root-a', toolCallId: 'ask-a',
      toolName: 'AskUserQuestion', arguments: { questions: [{ id: 'scope', question: '范围？' }] }, requiresConfirmation: false });
    const result = eventFixture({ eventId: 'answer-result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'ask-a',
      content: [{ type: 'text', text: '{"answers":[{"id":"scope","selected":["东区"]}]}' }], isError: false });
    const exactQuestion = questionFixture();
    const exact = projectRuntimeEvents([userFixture(), call, exactQuestion, result]);
    expect(exact.filter((turn) => turn.kind === 'question')).toHaveLength(1);
    expect(exact.some((turn) => turn.kind === 'activity' && turn.toolName === 'AskUserQuestion')).toBe(false);

    const otherRootQuestion = { ...exactQuestion, eventId: 'other-question', sourceEventId: 'root-b' };
    const unmatched = projectRuntimeEvents([userFixture(), call, otherRootQuestion, result]);
    expect(unmatched.find((turn) => turn.kind === 'activity')).toMatchObject({
      toolName: 'AskUserQuestion', status: 'unconfirmed', statusMessage: '问题记录尚未完整，正在同步', result: '',
    });
  });
  it('absorbs only the exactly associated showcase CallMateTool shell and keeps ordinary Mate calls visible', () => {
    const showcaseCall = eventFixture({ eventId: 'showcase-call-event', type: 'agent.tool_call', sourceEventId: 'root-a',
      toolCallId: 'showcase-call', toolName: 'CallMateTool', arguments: { tool: 'confirm_with_user', args: {
        label: 'driver_design_confirm', context: { '问题': '不得从这里提取' },
      } }, requiresConfirmation: false });
    const question = showcaseQuestionFixture();
    const result = eventFixture({ eventId: 'showcase-result', type: 'agent.tool_result', sourceEventId: 'root-a',
      toolCallId: 'showcase-call', content: [{ type: 'text', text: '{"content":"继续"}' }], isError: false });
    const turns = projectRuntimeEvents([userFixture(), showcaseCall, question, result]);
    expect(turns.filter((turn) => turn.kind === 'question')).toEqual([expect.objectContaining({
      eventId: question.eventId, label: 'driver_design_confirm', context: question.type === 'agent.question' ? question.context : undefined,
    })]);
    expect(turns.some((turn) => turn.kind === 'activity')).toBe(false);

    const ordinaryCall = { ...showcaseCall, eventId: 'ordinary-call', toolCallId: 'ordinary',
      arguments: { tool: 'query_inspections', args: { campus: '东区' } } };
    expect(projectRuntimeEvents([userFixture(), ordinaryCall], true)).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'activity', toolName: 'CallMateTool', toolCallId: 'ordinary', status: 'running' }),
    ]));
  });
  it('uses the confirm tool name only for a preparation/failure placeholder, never to invent a question card', () => {
    const call = eventFixture({ eventId: 'showcase-call-event', type: 'agent.tool_call', sourceEventId: 'root-a',
      toolCallId: 'showcase-call', toolName: 'CallMateTool', arguments: { tool: 'confirm_with_user', args: {} },
      requiresConfirmation: false });
    const result = eventFixture({ eventId: 'showcase-result', type: 'agent.tool_result', sourceEventId: 'root-a',
      toolCallId: 'showcase-call', content: [{ type: 'text', text: '{"content":"不得泄露"}' }], isError: false });
    const turns = projectRuntimeEvents([userFixture(), call, result]);
    expect(turns.some((turn) => turn.kind === 'question')).toBe(false);
    expect(turns.find((turn) => turn.kind === 'activity')).toMatchObject({
      toolCallId: 'showcase-call', arguments: [], result: '', statusMessage: '问题记录尚未完整，正在同步',
    });
    const differentRoot = { ...showcaseQuestionFixture(), eventId: 'other-question', sourceEventId: 'root-b' };
    expect(projectRuntimeEvents([userFixture(), call, differentRoot], true).find((turn) => turn.key === call.eventId))
      .toMatchObject({ kind: 'activity', statusMessage: '正在准备问题' });
  });
  it('keeps a brief AskUserQuestion failure visible without exposing its arguments in the conversation', () => {
    const argumentsValue = { questions: [{ id: 'secret', question: '不应重复的原始参数' }] };
    const call = eventFixture({ eventId: 'ask', type: 'agent.tool_call', sourceEventId: 'root-a', toolCallId: 'ask-a',
      toolName: 'AskUserQuestion', arguments: argumentsValue, requiresConfirmation: false }) as Extract<
        import('../types/runtime').RuntimeEvent, { type: 'agent.tool_call' }>;
    const result = eventFixture({ eventId: 'failed', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'ask-a',
      content: [{ type: 'text', text: ` ${'失败详情 '.repeat(40)} ` }], isError: true,
      errorCode: 'USER_QUESTION_UNAVAILABLE' }) as Extract<import('../types/runtime').RuntimeEvent, { type: 'agent.tool_result' }>;
    const activity = projectRuntimeEvents([userFixture(), call, result]).find((turn) => turn.kind === 'activity');

    expect(activity).toMatchObject({ toolName: 'AskUserQuestion', status: 'error', arguments: [], result: '',
      errorCode: 'USER_QUESTION_UNAVAILABLE' });
    expect(activity?.kind === 'activity' ? activity.statusMessage : '').toMatch(/^提问失败：.{1,160}…$/u);
    expect(call.arguments).toBe(argumentsValue);
    expect(result.content[0].text).toContain('失败详情');
  });
  it('does not expose an associated question result when its call record is still missing', () => {
    const result = eventFixture({ eventId: 'answer-result', type: 'agent.tool_result', sourceEventId: 'root-a', toolCallId: 'ask-a',
      content: [{ type: 'text', text: '{"answers":[{"id":"scope","selected":["东区"]}]}' }], isError: false });
    const turns = projectRuntimeEvents([userFixture(), questionFixture(), result]);

    expect(turns.filter((turn) => turn.kind === 'question')).toHaveLength(1);
    expect(turns.some((turn) => turn.kind === 'activity')).toBe(false);
  });
  it('closes an unanswered question only on its root lifecycle', () => {
    const turns = projectRuntimeEvents([userFixture(), questionFixture(), idleFixture('terminated')]);
    expect(turns.find((turn) => turn.kind === 'question')).toMatchObject({ lifecycle: 'closed' });
  });
  it('keeps answered questions and superseded plans as immutable history beside the current cards', () => {
    const oldQuestion = questionFixture();
    const oldAnswer = eventFixture({ eventId: 'answer-a', type: 'user.question_answer', targetEventId: oldQuestion.eventId,
      answers: [{ id: 'scope', selected: ['东区'] }, { id: 'note', selected: [], custom: '先试点' }] });
    const currentQuestion = { ...questionFixture(), eventId: 'question-b', toolCallId: 'ask-b' };
    const oldPlan = eventFixture({ eventId: 'plan-a', type: 'agent.plan', sourceEventId: 'root-a',
      toolCallId: 'exit-a', content: '# 旧计划' });
    const revision = eventFixture({ eventId: 'revision-a', type: 'user.plan_confirmation', targetEventId: 'plan-a',
      result: 'revise', feedback: '补充验证' });
    const currentPlan = eventFixture({ eventId: 'plan-b', type: 'agent.plan', sourceEventId: 'root-a',
      toolCallId: 'exit-b', content: '# 当前计划' });
    const questionTurns = projectRuntimeEvents([userFixture(), oldQuestion, oldAnswer, currentQuestion, idleFixture('confirming')]);
    const planTurns = projectRuntimeEvents([userFixture(), oldPlan, revision, currentPlan, idleFixture('confirming')]);

    expect(questionTurns.filter((turn) => turn.kind === 'question')).toEqual([
      expect.objectContaining({ eventId: 'question-a', answer: oldAnswer.type === 'user.question_answer' ? oldAnswer.answers : undefined }),
      expect.objectContaining({ eventId: 'question-b', answer: undefined }),
    ]);
    expect(planTurns.filter((turn) => turn.kind === 'plan')).toEqual([
      expect.objectContaining({ eventId: 'plan-a', decision: 'revise', rawMarkdown: '# 旧计划' }),
      expect.objectContaining({ eventId: 'plan-b', decision: undefined, rawMarkdown: '# 当前计划' }),
    ]);
  });
});
