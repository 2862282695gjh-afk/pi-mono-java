import { computed, ref } from 'vue';
import type { Ref } from 'vue';
import type { CommandInvocation } from '../runtime/commands';
import type { DeveloperSettingsController, PrepareWriteFailure } from '../types/developerSettings';
import type { QuestionAnswer, SubmissionOutcome } from '../types/runtime';
import type { useWorkbenchSessions } from './useWorkbenchSessions';

type Workbench = ReturnType<typeof useWorkbenchSessions>;
type Runtime = Workbench['runtime'];
type WriteKind = 'message' | 'command' | 'stop' | 'tool' | 'plan' | 'question';

interface WorkbenchWriteActionsOptions {
  development: boolean;
  settings?: DeveloperSettingsController;
  runtime: Runtime;
  canSend: Readonly<Ref<boolean>>;
  message: Ref<string>;
  touchCurrentThread(text: string): void;
  controlLocked(): boolean;
  runControl(action: () => Promise<unknown>): Promise<boolean>;
}

interface WriteTicket {
  id: number;
  epoch: number;
  kind: WriteKind;
}

interface PreparedDispatch {
  headers?: Headers;
  isCurrent(): boolean;
}

/**
 * 统一协调工作台写动作的认证准备、目标复核和业务派发。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
export function useWorkbenchWriteActions(options: WorkbenchWriteActionsOptions) {
  const pending = ref(false);
  const preparing = ref(false);
  const messageSubmitting = ref(false);
  const activeKind = ref<WriteKind | ''>('');
  const questionSubmittingId = ref('');
  const progress = ref('');
  const notice = ref('');
  let epoch = 0;
  let nextTicket = 0;
  let activeTicket = 0;

  const messagePending = computed(() => pending.value
    && (activeKind.value === 'message' || activeKind.value === 'command'));
  const messageProgress = computed(() => messagePending.value ? progress.value : '');
  const controlPending = computed(() => pending.value && !messagePending.value);
  const controlProgress = computed(() => controlPending.value ? progress.value : '');
  const stopPending = computed(() => pending.value && activeKind.value === 'stop');

  async function submit(): Promise<void> {
    const draft = options.message.value;
    const sessionId = options.runtime.session.value?.sessionId;
    if (!draft.trim() || pending.value || !options.canSend.value || !sessionId) return;
    try {
      await runPrepared('message', () => sessionId === options.runtime.session.value?.sessionId
        && options.canSend.value, async (headers, current) => {
        messageSubmitting.value = true;
        const submission = await options.runtime.sendMessage(draft, [], headers);
        const outcome = await submission.confirmation;
        if (current() && outcome === 'confirmed') {
          options.touchCurrentThread(draft);
          if (options.message.value === draft) options.message.value = '';
        }
      }, undefined, () => sessionId === options.runtime.session.value?.sessionId);
    } catch {
      // 没有本次回执时保留草稿，不以同文历史确认，也不自动重发。
    }
  }

  async function executeCommand(invocation: CommandInvocation): Promise<SubmissionOutcome> {
    const sessionId = options.runtime.session.value?.sessionId;
    const frozenInvocation = freezeInvocation(invocation);
    if (!sessionId || pending.value || !commandTargetCurrent(sessionId, frozenInvocation)) return 'not-submitted';
    return runPrepared('command', () => commandTargetCurrent(sessionId, frozenInvocation), async (headers, current) => {
      const outcome = await options.runtime.executeCommand(frozenInvocation, headers);
      if (outcome === 'confirmed' && frozenInvocation.executionMode === 'skillEvents'
        && current()) {
        const suffix = frozenInvocation.request.arguments ? ` ${frozenInvocation.request.arguments}` : '';
        options.touchCurrentThread(`/${frozenInvocation.request.name}${suffix}`);
      }
      return outcome;
    }, 'not-submitted', () => sessionId === options.runtime.session.value?.sessionId);
  }

  async function stop(): Promise<void> {
    const sessionId = options.runtime.session.value?.sessionId;
    const rootId = options.runtime.execution.value.rootId;
    if (!sessionId || !rootId || pending.value || !stopTargetCurrent(sessionId, rootId)) return;
    await runPrepared('stop', () => sessionId === options.runtime.session.value?.sessionId
      && stopTargetCurrent(sessionId, rootId),
    async (headers) => { await options.runControl(() => options.runtime.interrupt(headers)); });
  }

  async function decideTool(decision: 'allow' | 'deny', reason: string): Promise<void> {
    const sessionId = options.runtime.session.value?.sessionId;
    const toolCallId = options.runtime.execution.value.pendingTool?.toolCallId;
    if (!sessionId || !toolCallId || pending.value || !toolTargetCurrent(sessionId, toolCallId)) return;
    await runPrepared('tool', () => toolTargetCurrent(sessionId, toolCallId),
    async (headers) => {
      await options.runControl(() => options.runtime.confirmTool(toolCallId, decision, reason, headers));
    });
  }

  async function decidePlan(eventId: string, decision: 'approve' | 'revise', feedback: string): Promise<void> {
    const sessionId = options.runtime.session.value?.sessionId;
    if (!sessionId || pending.value || !planTargetCurrent(sessionId, eventId)) return;
    await runPrepared('plan', () => planTargetCurrent(sessionId, eventId),
    async (headers) => {
      await options.runControl(() => options.runtime.confirmPlan(eventId, decision, feedback, headers));
    });
  }

  async function answerQuestion(eventId: string, answers: QuestionAnswer[]): Promise<void> {
    const sessionId = options.runtime.session.value?.sessionId;
    const frozenAnswers = answers.map((answer) => ({ ...answer, selected: [...answer.selected] }));
    if (!sessionId || pending.value || !questionTargetCurrent(sessionId, eventId)) return;
    questionSubmittingId.value = eventId;
    try {
      await runPrepared('question', () => questionTargetCurrent(sessionId, eventId),
      async (headers) => {
        await options.runControl(() => options.runtime.answerQuestion(eventId, frozenAnswers, headers));
      });
    } finally {
      if (questionSubmittingId.value === eventId) questionSubmittingId.value = '';
    }
  }

  async function runPrepared<T>(
    kind: WriteKind,
    targetCurrent: () => boolean,
    action: (headers: Headers | undefined, current: () => boolean) => Promise<T>,
    notSubmitted?: T,
    identityCurrent: () => boolean = targetCurrent,
  ): Promise<T> {
    const ticket = begin(kind);
    if (!ticket) return notSubmitted as T;
    try {
      const prepared = await prepare(ticket, targetCurrent);
      if (prepared === null || !current(ticket) || !targetCurrent() || !prepared.isCurrent()) {
        return notSubmitted as T;
      }
      preparing.value = false;
      progress.value = progressText(kind);
      return await action(prepared.headers, () => current(ticket) && identityCurrent());
    } finally {
      finish(ticket);
    }
  }

  async function prepare(ticket: WriteTicket, targetCurrent: () => boolean): Promise<PreparedDispatch | null> {
    if (!current(ticket) || !targetCurrent()) return null;
    if (!options.development) return { isCurrent: () => true };
    if (!options.settings) {
      notice.value = failureNotice(ticket.kind, 'configuration');
      return null;
    }
    const result = await options.settings.prepareWrite();
    if (!current(ticket) || !targetCurrent()) return null;
    if (!result.ok) {
      notice.value = failureNotice(ticket.kind, result.reason);
      return null;
    }
    return { headers: result.headers, isCurrent: result.isCurrent };
  }

  function begin(kind: WriteKind): WriteTicket | null {
    if (pending.value) return null;
    const ticket = { id: ++nextTicket, epoch, kind };
    activeTicket = ticket.id;
    activeKind.value = kind;
    pending.value = true;
    preparing.value = options.development;
    notice.value = '';
    progress.value = progressText(kind);
    return ticket;
  }

  function finish(ticket: WriteTicket): void {
    if (activeTicket !== ticket.id) return;
    activeTicket = 0;
    activeKind.value = '';
    pending.value = false;
    preparing.value = false;
    messageSubmitting.value = false;
    progress.value = '';
  }

  function cancel(): void {
    epoch += 1;
    options.settings?.cancelToken();
    notice.value = '';
  }

  function clearNotice(): void {
    notice.value = '';
  }

  function current(ticket: WriteTicket): boolean {
    return activeTicket === ticket.id && epoch === ticket.epoch;
  }

  function commandTargetCurrent(sessionId: string, invocation: CommandInvocation): boolean {
    if (sessionId !== options.runtime.session.value?.sessionId || options.runtime.catalogStatus.value !== 'ready') return false;
    return options.runtime.commands.value.some((command) => command.name === invocation.request.name
      && (command.kind === 'skill') === (invocation.executionMode === 'skillEvents'));
  }

  function stopTargetCurrent(sessionId: string, rootId: string): boolean {
    return sessionId === options.runtime.session.value?.sessionId
      && rootId === options.runtime.execution.value.rootId
      && options.runtime.canStop.value
      && !options.controlLocked();
  }

  function toolTargetCurrent(sessionId: string, toolCallId: string): boolean {
    return controlTargetCurrent(sessionId)
      && toolCallId === options.runtime.execution.value.pendingTool?.toolCallId;
  }

  function planTargetCurrent(sessionId: string, eventId: string): boolean {
    return controlTargetCurrent(sessionId)
      && options.runtime.session.value?.mode === 'plan'
      && eventId === options.runtime.execution.value.pendingPlan?.eventId;
  }

  function questionTargetCurrent(sessionId: string, eventId: string): boolean {
    return controlTargetCurrent(sessionId)
      && eventId === options.runtime.execution.value.pendingQuestion?.eventId;
  }

  function controlTargetCurrent(sessionId: string): boolean {
    return sessionId === options.runtime.session.value?.sessionId
      && options.runtime.session.value?.state === 'running'
      && options.runtime.execution.value.confirming
      && options.runtime.historyReady.value
      && !options.runtime.stopping.value
      && !options.runtime.controlPending.value
      && !options.runtime.recovering.value
      && !options.runtime.receiptUnknown.value
      && !options.runtime.uncertainty.value
      && !options.controlLocked();
  }

  return {
    pending,
    preparing,
    messagePending,
    messageSubmitting,
    messageProgress,
    controlPending,
    controlProgress,
    stopPending,
    questionSubmittingId,
    notice,
    submit,
    executeCommand,
    stop,
    decideTool,
    decidePlan,
    answerQuestion,
    cancel,
    clearNotice,
  };
}

function freezeInvocation(invocation: CommandInvocation): CommandInvocation {
  const request = Object.freeze({ ...invocation.request });
  return Object.freeze({ ...invocation, request });
}

function progressText(kind: WriteKind): string {
  const controls: Record<Exclude<WriteKind, 'message' | 'command'>, string> = {
    stop: '正在提交停止请求…',
    tool: '正在提交工具确认…',
    plan: '正在提交审核结果…',
    question: '正在提交回答…',
  };
  return kind === 'message' || kind === 'command' ? '正在发送…' : controls[kind];
}

function failureNotice(kind: WriteKind, reason: PrepareWriteFailure): string {
  if (reason === 'busy') return '正在处理另一项操作，请稍候后重试。';
  const target = kind === 'message' ? '消息未发送'
    : kind === 'command' ? '命令未执行' : '操作尚未提交';
  return reason === 'authentication'
    ? `认证暂不可用，${target}，请重试。`
    : `请检查开发者设置，${target}。`;
}
