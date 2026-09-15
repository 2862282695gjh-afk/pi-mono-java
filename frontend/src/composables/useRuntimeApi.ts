import { computed, ref, shallowRef } from 'vue';
import type { MessageSubmission, QuestionAnswer, RuntimeEvent, RuntimeSession, SubmissionOutcome, UserEvent } from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';
import { decodeCatalog, decodeCommandResult } from '../runtime/commands';
import type { CommandDescriptor, CommandInvocation, CommandResult } from '../runtime/commands';
import { codePointLength, decodeHistory, decodeModels, decodeSession, invalidResponse, matchesReceipt, messageEvent, readEventStream, record } from '../runtime/protocol';
import type { PendingOperation, PendingStore } from '../runtime/sessionIndex';
import { executionState, executionTodos, mergeEvent, reconcileEvents } from '../runtime/eventStore';
import { normalizeQuestionAnswers, questionAnswerEvent } from '../runtime/questionInteraction';
import { defaultRuntimeConnection } from '../runtime/runtimeConfiguration';
import type { RuntimeConnectionSnapshot, RuntimeEnvironmentSource } from '../runtime/runtimeConfiguration';

const API_PATH = '/campusclaw-service/v1';
type Context = {
  generation: number;
  sessionId: string;
  signal: AbortSignal;
  connection: RuntimeConnectionSnapshot;
};

/** 仅内部 Runtime 工作台装配；不兼容或回退到 Mate 公共接口。 */
export function useRuntimeApi(pendingStore?: PendingStore, onCreated?: (session: RuntimeSession) => void,
  onObservedSession?: (session: RuntimeSession) => void, environment?: RuntimeEnvironmentSource) {
  const session = ref<RuntimeSession | null>(null);
  const etag = ref('');
  const models = ref<string[]>([]);
  const events = shallowRef<RuntimeEvent[]>([]);
  const streamCount = ref(0);
  const messagePending = ref(false);
  const controlPending = ref(false);
  const stopping = ref(false);
  const recovering = ref(false);
  const historyReady = ref(false);
  const uncertainty = ref('');
  const receiptUnknown = ref(false);
  const lastError = ref('');
  const lastErrorCode = ref('');
  const commands = ref<CommandDescriptor[]>([]);
  const catalogStatus = ref<'stale' | 'loading' | 'ready' | 'error'>('stale');
  const catalogError = ref('');
  const commandResult = shallowRef<CommandResult | null>(null);
  const commandPending = ref(false);
  let generation = 0;
  let revision = 0;
  let catalogRevision = 0;
  let messageTicket = 0;
  let controlTicket = 0;
  let sessionReadTicket = 0;
  let viewController = new AbortController();
  let catalogFlight: Promise<CommandDescriptor[]> | undefined;
  let recoveryFlight: Promise<void> | undefined;
  const pendingOperations = ref<PendingOperation[]>([]);
  const execution = computed(() => executionState(events.value));
  const todoSnapshot = computed(() => executionTodos(events.value));
  const hasSession = computed(() => session.value !== null);
  const streaming = computed(() => streamCount.value > 0);
  const running = computed(() => hasSession.value && (session.value?.state === 'running' || messagePending.value
    || (!!execution.value.rootId && !execution.value.terminal)));
  const canSend = computed(() => hasSession.value && historyReady.value && !running.value && !receiptUnknown.value && !uncertainty.value && !pendingOperations.value.length
    && !recovering.value && !streaming.value && !controlPending.value && !stopping.value && !commandPending.value);
  const canStop = computed(() => session.value?.state === 'running' && !receiptUnknown.value && !recovering.value && !!execution.value.rootId && !execution.value.terminal
    && !stopping.value && !controlPending.value && !messagePending.value);
  const connectionSnapshot = () => environment?.snapshot() ?? defaultRuntimeConnection();

  function context(): Context {
    if (!session.value) throw new RuntimeApiError({ code: 'SESSION_REQUIRED', message: '请先新建或恢复会话。' });
    return detachedContext(session.value.sessionId);
  }
  function detachedContext(sessionId: string): Context {
    return { generation, sessionId, signal: viewController.signal, connection: connectionSnapshot() };
  }
  function current(ctx: Context): boolean { return generation === ctx.generation && !ctx.signal.aborted; }
  function check(ctx: Context): void { if (!current(ctx)) throw new DOMException('View changed', 'AbortError'); }
  function path(ctx: Context, suffix = ''): string { return `/sessions/${encodeURIComponent(ctx.sessionId)}${suffix}`; }
  function clearError(): void { lastError.value = ''; lastErrorCode.value = ''; }
  function publish(error: unknown, ctx: Context): RuntimeApiError {
    const normalized = error instanceof RuntimeApiError ? error
      : new RuntimeApiError({ message: '暂时无法读取服务，请检查连接后重新核对。' });
    if (current(ctx)) { lastError.value = normalized.message; lastErrorCode.value = normalized.code; }
    return normalized;
  }
  function invalidateCatalog(): void { catalogRevision++; catalogStatus.value = 'stale'; }
  function clearSessionView(): void {
    viewController.abort();
    viewController = new AbortController();
    generation++; revision++; catalogRevision++; messageTicket++; controlTicket++;
    session.value = null; etag.value = ''; models.value = []; events.value = [];
    streamCount.value = 0; messagePending.value = false; controlPending.value = false;
    stopping.value = false; recovering.value = false; historyReady.value = false; receiptUnknown.value = false; uncertainty.value = '';
    commands.value = []; catalogStatus.value = 'stale'; catalogError.value = '';
    commandResult.value = null; commandPending.value = false;
    catalogFlight = undefined; recoveryFlight = undefined; pendingOperations.value = []; clearError();
  }
  async function request(ctx: Context, suffix: string, init: RequestInit = {}): Promise<Response> {
    check(ctx);
    const headers = new Headers({ Accept: 'application/json', 'Accept-Language': navigator.language.startsWith('zh') ? 'zh-CN' : 'en-US', 'X-HW-ID': ctx.connection.callerId });
    new Headers(init.headers).forEach((value, key) => headers.set(key, value));
    if (import.meta.env.DEV && environment && !ctx.connection.apiBase) {
      if (ctx.connection.proxyVersion === undefined || !ctx.connection.proxyKey) {
        throw new RuntimeApiError({ code: 'DEV_PROXY_CONFIG_REQUIRED', message: '请先读取并应用本地代理配置。' });
      }
      headers.set('X-CampusClaw-Proxy-Version', String(ctx.connection.proxyVersion));
      headers.set('X-CampusClaw-Proxy-Key', ctx.connection.proxyKey);
    }
    let response: Response;
    try {
      response = await fetch(`${ctx.connection.apiBase}${API_PATH}${suffix}`, { ...init, headers, signal: ctx.signal, cache: 'no-store' });
    } catch (error) {
      check(ctx);
      const writes = !!init.method && init.method !== 'GET';
      throw new RuntimeApiError({ code: writes ? 'OUTCOME_UNCERTAIN' : 'NETWORK_ERROR', outcomeUncertain: writes,
        message: writes ? '提交结果不确定；请先核对历史，不要重复提交。' : '读取失败，请检查连接后重试。' });
    }
    check(ctx);
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new RuntimeApiError({ status: response.status, code: typeof body.resCode === 'string' ? body.resCode : `HTTP_${response.status}`,
        message: typeof body.resMsg === 'string' ? body.resMsg : '服务未接受本次操作，请重新核对。' });
    }
    return response;
  }
  async function result(response: Response): Promise<unknown> {
    if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/json') invalidResponse();
    const body = record(await response.json().catch(invalidResponse));
    if (body.resCode !== '0' || typeof body.resMsg !== 'string' || !('result' in body)) invalidResponse();
    return body.result;
  }
  function applySession(value: RuntimeSession, response: Response, ctx: Context, expectedRevision: number): void {
    check(ctx);
    const version = response.headers.get('ETag');
    if (value.sessionId !== ctx.sessionId || !version || !/^"[^"\r\n]+"$/u.test(version)) invalidResponse();
    if (revision !== expectedRevision) return;
    const old = session.value;
    session.value = value; etag.value = version;
    if (old?.state !== value.state || old?.modelId !== value.modelId || old?.thinking !== value.thinking) invalidateCatalog();
  }
  async function refreshSession(ctx: Context): Promise<RuntimeSession> {
    const version = revision;
    const ticket = ++sessionReadTicket;
    const response = await request(ctx, path(ctx));
    const value = decodeSession(await result(response));
    check(ctx);
    if (ticket === sessionReadTicket) applySession(value, response, ctx, version);
    return value;
  }
  async function getSession(sessionId = session.value?.sessionId): Promise<RuntimeSession> {
    if (!sessionId) throw new RuntimeApiError({ message: '请输入 Session ID。' });
    if (sessionId !== session.value?.sessionId) {
      clearSessionView();
      pendingOperations.value = pendingStore?.list(sessionId) ?? [];
      receiptUnknown.value = pendingOperations.value.some((item) => !item.receiptId);
      if (pendingOperations.value.length) uncertainty.value = '正在核对上次提交的结果，不会重复发送。';
    }
    const ctx = detachedContext(sessionId);
    try { return await refreshSession(ctx); } catch (error) { throw publish(error, ctx); }
  }
  async function createSession(agentId: string): Promise<RuntimeSession> {
    clearSessionView();
    const ctx = detachedContext('');
    try {
      const response = await request(ctx, `/agents/${encodeURIComponent(agentId)}/sessions`, { method: 'POST' });
      const created = decodeSession(await result(response));
      check(ctx);
      onCreated?.(created);
      await getSession(created.sessionId);
      historyReady.value = true;
      await listModels();
      return created;
    } catch (error) { throw publish(error, ctx); }
  }
  async function listModels() {
    const ctx = context();
    try {
      const value = decodeModels(await result(await request(ctx, path(ctx, '/models'))));
      check(ctx); models.value = value.models;
      // Models 无 Session 版本，不能拆开覆盖 Session.modelId 或 ETag。
      return value;
    } catch (error) { throw publish(error, ctx); }
  }
  async function loadHistory(): Promise<RuntimeEvent[]> {
    const ctx = context();
    let loaded: RuntimeEvent[] = [];
    let page: number | null = 1;
    try {
      while (page !== null) {
        const response = await request(ctx, path(ctx, `/events?limit=200&page=${page}`));
        const value = decodeHistory(await result(response));
        check(ctx);
        if (value.nextPage !== null && value.nextPage !== page + 1) invalidResponse();
        loaded = reconcileEvents([], [...loaded, ...value.events]);
        page = value.nextPage;
      }
      check(ctx);
      events.value = reconcileEvents(events.value, loaded);
      return loaded;
    } catch (error) { throw publish(error, ctx); }
  }
  function recover(): Promise<void> {
    if (recoveryFlight) return recoveryFlight;
    const ctx = context();
    recovering.value = true; historyReady.value = false;
    const flight = (async () => {
      try {
        await loadHistory();
        await refreshSession(ctx);
        check(ctx);
        for (const operation of [...pendingOperations.value]) {
          const acceptedPlan = operation.kind === 'plan-confirmation' && operation.targetEventId
            && events.value.some((event) => event.type === 'user.plan_confirmation' && event.targetEventId === operation.targetEventId);
          const acceptedAnswer = operation.kind === 'question-answer' && operation.targetEventId
            && events.value.some((event) => event.type === 'user.question_answer' && event.targetEventId === operation.targetEventId);
          const receiptIndex = events.value.findIndex((event) => event.eventId === operation.receiptId);
          const ended = receiptIndex >= 0 && events.value.slice(receiptIndex + 1).some((event) =>
            event.type === 'session.status_idle' && event.sourceEventId === operation.rootId);
          if (acceptedPlan || acceptedAnswer || ended) finishOperation(ctx, operation);
        }
        receiptUnknown.value = pendingOperations.value.some((item) => !item.receiptId);
        if (!pendingOperations.value.length) uncertainty.value = execution.value.rootId && !execution.value.terminal && session.value?.state === 'idle'
          ? '尚未读到本轮结束记录，请刷新状态；不能仅按空闲状态再次发送。' : '';
        stopping.value = execution.value.interruptPending;
        historyReady.value = true;
      } catch (error) { throw publish(error, ctx); }
      finally { if (current(ctx)) { recovering.value = false; recoveryFlight = undefined; } }
    })();
    recoveryFlight = flight;
    return flight;
  }
  async function changeSetting(body: unknown): Promise<RuntimeSession> {
    const ctx = context();
    try {
      if (!etag.value) await refreshSession(ctx);
      check(ctx);
      const settingRevision = ++revision;
      const response = await request(ctx, path(ctx), { method: 'POST', headers: { 'Content-Type': 'application/json', 'If-Match': etag.value }, body: JSON.stringify(body) });
      const value = decodeSession(await result(response));
      applySession(value, response, ctx, settingRevision);
      await loadHistory();
      return value;
    } catch (error) {
      if (current(ctx) && error instanceof RuntimeApiError && (error.status === 412 || error.outcomeUncertain)) {
        await recover().catch(() => undefined);
        if (error.status === 412) error = new RuntimeApiError({ status: 412, code: 'SESSION_VERSION_MISMATCH', message: '设置已被更新，已重新读取。请核对当前值，再明确选择；没有自动重试修改。' });
      }
      throw publish(error, ctx);
    }
  }
  async function readDetachedSession(ctx: Context): Promise<{ value: RuntimeSession; etag: string }> {
    const response = await request(ctx, path(ctx));
    const value = decodeSession(await result(response));
    const version = response.headers.get('ETag');
    if (value.sessionId !== ctx.sessionId || !version || !/^"[^"\r\n]+"$/u.test(version)) invalidResponse();
    return { value, etag: version };
  }
  async function readNamedSession(sessionId: string): Promise<RuntimeSession> {
    if (!sessionId) throw new RuntimeApiError({ message: '请输入 Session ID。' });
    return (await readDetachedSession(detachedContext(sessionId))).value;
  }
  async function renameSession(sessionId: string, displayName: string): Promise<RuntimeSession> {
    if (sessionId === session.value?.sessionId) return changeSetting({ displayName });
    const ctx = detachedContext(sessionId);
    try {
      const resource = await readDetachedSession(ctx);
      const response = await request(ctx, path(ctx), { method: 'POST', headers: {
        'Content-Type': 'application/json', 'If-Match': resource.etag,
      }, body: JSON.stringify({ displayName }) });
      const value = decodeSession(await result(response));
      const version = response.headers.get('ETag');
      if (value.sessionId !== sessionId || !version || !/^"[^"\r\n]+"$/u.test(version)) invalidResponse();
      onObservedSession?.(value);
      return value;
    } catch (error) {
      if (error instanceof RuntimeApiError && (error.status === 412 || error.outcomeUncertain)) {
        try { onObservedSession?.((await readDetachedSession(ctx)).value); } catch { /* 保留原写入结果。 */ }
        if (error.status === 412) throw new RuntimeApiError({ status: 412, code: 'SESSION_VERSION_MISMATCH',
          message: '名称保存时会话已更新。已刷新会话信息，请核对名称后再次保存；没有自动重试。' });
      }
      throw error;
    }
  }
  async function deleteSession(): Promise<void> {
    const ctx = context();
    try { await request(ctx, path(ctx), { method: 'DELETE' }); check(ctx); clearSessionView(); }
    catch (error) { throw publish(error, ctx); }
  }
  async function deleteNamedSession(sessionId: string): Promise<void> {
    if (!sessionId) throw new RuntimeApiError({ message: '请输入 Session ID。' });
    const ctx = detachedContext(sessionId);
    await request(ctx, path(ctx), { method: 'DELETE' });
    check(ctx);
  }
  function writeHeaders(custom?: HeadersInit, stream = true): Headers {
    const headers = new Headers(custom);
    // 临时凭据逐动作复制；不得覆盖协议头或引入不存在的幂等/版本机制。
    headers.set('Accept', stream ? 'text/event-stream' : 'application/json');
    headers.set('Content-Type', 'application/json');
    headers.delete('If-Match'); headers.delete('Idempotency-Key'); headers.delete('Last-Event-ID');
    return headers;
  }
  function beginOperation(ctx: Context, kind: PendingOperation['kind'], rootId?: string, targetEventId?: string): PendingOperation {
    const operation: PendingOperation = { id: crypto.randomUUID(), kind, ...(rootId ? { rootId } : {}),
      ...(targetEventId ? { targetEventId } : {}) };
    pendingOperations.value = [...pendingOperations.value, operation];
    pendingStore?.put(ctx.sessionId, operation);
    return operation;
  }
  function finishOperation(ctx: Context, operation: PendingOperation): void {
    pendingStore?.remove(ctx.sessionId, operation.id);
    if (current(ctx)) pendingOperations.value = pendingOperations.value.filter((item) => item.id !== operation.id);
  }
  async function submitStream(input: UserEvent | CommandInvocation, custom?: HeadersInit): Promise<MessageSubmission> {
    const ctx = context();
    const skill = 'executionMode' in input;
    const user = skill ? undefined : input as UserEvent;
    const isMessage = skill || input.type === 'user.message';
    if (isMessage ? !canSend.value : controlPending.value) throw new RuntimeApiError({ code: 'SESSION_BUSY', message: '请等待当前操作，或先重新核对会话。' });
    const init = { method: 'POST', headers: writeHeaders(custom), body: JSON.stringify(skill ? input.request : { event: input }) };
    const operation = beginOperation(ctx, skill ? 'skill' : user?.type === 'user.message' ? 'message'
      : user?.type === 'user.interrupt' ? 'interrupt' : user?.type === 'user.plan_confirmation' ? 'plan-confirmation'
      : user?.type === 'user.question_answer' ? 'question-answer' : 'confirmation',
    isMessage ? undefined : execution.value.rootId,
    user?.type === 'user.plan_confirmation' || user?.type === 'user.question_answer' ? user.targetEventId : undefined);
    const ticket = isMessage ? ++messageTicket : ++controlTicket;
    if (isMessage) messagePending.value = true;
    else controlPending.value = true;
    revision++; clearError();
    let response: Response;
    try {
      response = await request(ctx, path(ctx, skill ? '/command' : '/events'), init);
    } catch (error) {
      if (current(ctx)) {
        if (isMessage) messagePending.value = false;
        else controlPending.value = false;
        if (error instanceof RuntimeApiError && error.status > 0) finishOperation(ctx, operation);
        if (error instanceof RuntimeApiError && error.outcomeUncertain) { receiptUnknown.value = true; uncertainty.value = error.message; }
        invalidateCatalog();
      }
      if ((user?.type === 'user.plan_confirmation' || user?.type === 'user.question_answer')
        && error instanceof RuntimeApiError && error.status === 409) {
        await recover().catch(() => undefined);
      }
      throw publish(error, ctx);
    }
    let settle!: (outcome: SubmissionOutcome) => void;
    const confirmation = new Promise<SubmissionOutcome>((resolve) => { settle = resolve; });
    streamCount.value++;
    void consume(response, input, ctx, settle, ticket, operation);
    return { confirmation };
  }
  async function consume(response: Response, input: UserEvent | CommandInvocation, ctx: Context, settle: (outcome: SubmissionOutcome) => void, ticket: number, operation: PendingOperation): Promise<void> {
    const isMessage = 'executionMode' in input || input.type === 'user.message';
    const release = () => {
      if (isMessage && messageTicket === ticket) messagePending.value = false;
      if (!isMessage && controlTicket === ticket) controlPending.value = false;
    };
    let receipt: RuntimeEvent | undefined;
    let rootId = execution.value.rootId;
    let ended = false;
    try {
      await readEventStream(response, (event) => {
        check(ctx);
        if (!receipt) {
          const skill = 'executionMode' in input;
          if (event.type !== (skill ? 'user.message' : input.type)) invalidResponse();
          if (!skill && !matchesReceipt(input, event)) invalidResponse();
          receipt = event;
          if (event.type === 'user.message') rootId = event.eventId;
          if (event.type === 'user.interrupt') { rootId = event.targetEventId; stopping.value = true; }
          operation.receiptId = receipt.eventId; operation.rootId = rootId;
          pendingStore?.put(ctx.sessionId, operation);
          release();
          if (session.value) session.value = { ...session.value, state: 'running' };
          invalidateCatalog(); settle('confirmed');
        } else {
          if (event.type.startsWith('user.')) invalidResponse();
          if ('sourceEventId' in event && event.sourceEventId !== rootId) invalidResponse();
        }
        revision++;
        events.value = mergeEvent(events.value, event);
        if (event.type === 'session.status_idle' && event.sourceEventId === rootId) {
          ended = true;
          finishOperation(ctx, operation);
          if (event.reason === 'failed') { lastError.value = event.message!; lastErrorCode.value = event.errorCode!; }
          if (event.reason !== 'confirming') stopping.value = false;
          invalidateCatalog();
          return false;
        }
      }, ctx.signal);
    } catch (error) { if (current(ctx) && error instanceof RuntimeApiError) publish(error, ctx); }
    finally {
      settle(receipt ? 'confirmed' : 'uncertain');
      if (current(ctx)) {
        streamCount.value--; release();
        if (!receipt) {
          receiptUnknown.value = true;
          uncertainty.value = '未收到本次完整回执，提交结果不确定。草稿已保留；相同文本不能证明成功，请勿直接重发。';
        } else if (!ended) {
          uncertainty.value = '回执已收到，但观察流中断。后台可能仍在执行，请重新核对；不要重复提交。';
        }
        await recover().catch(() => undefined);
      }
    }
  }
  function sendMessage(message: string, fileIds: string[] = [], headers?: HeadersInit) {
    return submitStream(messageEvent(message, fileIds), headers);
  }
  function interrupt(headers?: HeadersInit) {
    if (!canStop.value) throw new RuntimeApiError({ code: 'STOP_UNAVAILABLE', message: '尚未确认当前执行目标，请先重新核对。' });
    return submitStream({ type: 'user.interrupt', targetEventId: execution.value.rootId }, headers);
  }
  function confirmTool(toolCallId: string, decision: 'allow' | 'deny', denyMessage: string, headers?: HeadersInit) {
    if (session.value?.state !== 'running' || stopping.value || execution.value.pendingTool?.toolCallId !== toolCallId || !execution.value.confirming
      || codePointLength(denyMessage) > 2048) throw new RuntimeApiError({ message: '待确认工具已变化或拒绝说明超长，请重新核对。' });
    return submitStream({ type: 'user.tool_confirmation', toolCallId, result: decision,
      ...(decision === 'deny' && denyMessage.trim() ? { denyMessage } : {}) }, headers);
  }
  function confirmPlan(targetEventId: string, decision: 'approve' | 'revise', feedback: string, headers?: HeadersInit) {
    if (session.value?.state !== 'running' || stopping.value || execution.value.pendingPlan?.eventId !== targetEventId
      || !execution.value.confirming || !historyReady.value || feedback.length > 4096 || (feedback && !feedback.trim())) {
      throw new RuntimeApiError({ code: 'PLAN_CONFIRMATION_NOT_PENDING', message: '待审核方案已变化或反馈不符合要求，请重新核对。' });
    }
    return submitStream({ type: 'user.plan_confirmation', targetEventId, result: decision,
      ...(decision === 'revise' && feedback ? { feedback } : {}) }, headers);
  }
  function answerQuestion(targetEventId: string, answers: QuestionAnswer[], headers?: HeadersInit) {
    const pending = execution.value.pendingQuestion;
    if (session.value?.state !== 'running' || stopping.value || pending?.eventId !== targetEventId
      || !execution.value.confirming || !historyReady.value || recovering.value || receiptUnknown.value || uncertainty.value) {
      throw new RuntimeApiError({ code: 'QUESTION_ANSWER_NOT_PENDING', message: '待回答问题已变化，请重新核对。' });
    }
    return submitStream(questionAnswerEvent(targetEventId, normalizeQuestionAnswers(pending.questions, answers)), headers);
  }
  function listCommands(): Promise<CommandDescriptor[]> {
    if (catalogStatus.value === 'ready') return Promise.resolve(commands.value);
    if (catalogFlight) return catalogFlight;
    const ctx = context();
    const version = catalogRevision;
    catalogStatus.value = 'loading'; catalogError.value = '';
    const flight = (async () => {
      try {
        const value = decodeCatalog(await result(await request(ctx, path(ctx, '/commands'))));
        check(ctx);
        if (version === catalogRevision) { commands.value = value; catalogStatus.value = 'ready'; }
        else catalogStatus.value = 'stale';
        return value;
      } catch (error) {
        if (current(ctx)) { catalogStatus.value = 'error'; catalogError.value = '命令清单读取失败，请重试清单。'; }
        throw error;
      } finally { if (current(ctx)) catalogFlight = undefined; }
    })();
    catalogFlight = flight;
    return flight.then((value) => current(ctx) && catalogStatus.value === 'stale' ? listCommands() : value);
  }
  async function executeCommand(invocation: CommandInvocation, headers?: HeadersInit): Promise<SubmissionOutcome> {
    const ctx = context();
    if (commandPending.value || catalogStatus.value !== 'ready') throw new RuntimeApiError({ message: '请先读取最新命令清单。' });
    if (invocation.executionMode === 'skillEvents') {
      const submission = await submitStream(invocation, headers);
      return submission.confirmation;
    }
    const init = { method: 'POST', headers: writeHeaders(headers, false), body: JSON.stringify(invocation.request) };
    const operation = beginOperation(ctx, 'command');
    commandPending.value = true;
    const commandRevision = ++revision;
    clearError();
    try {
      const response = await request(ctx, path(ctx, '/command'), init);
      const decoded = decodeCommandResult(invocation, await result(response));
      check(ctx);
      if (decoded.kind === 'session') applySession(decoded.value, response, ctx, commandRevision);
      if (decoded.kind === 'models') models.value = decoded.value.models;
      commandResult.value = decoded;
      finishOperation(ctx, operation);
      return 'confirmed';
    } catch (error) {
      if (current(ctx) && error instanceof RuntimeApiError && error.status > 0) finishOperation(ctx, operation);
      if (current(ctx) && (!(error instanceof RuntimeApiError) || error.status === 0)) {
        uncertainty.value = '命令结果未知，已保留命令草稿；请核对当前资源，不会自动再次运行。';
        await recover().catch(() => undefined);
      }
      throw publish(error, ctx);
    } finally {
      if (current(ctx)) {
        commandPending.value = false; invalidateCatalog();
        await listCommands().catch(() => undefined);
      }
    }
  }
  function acknowledgeUnknown(): void {
    if (recovering.value || running.value || session.value?.state !== 'idle'
      || pendingOperations.value.some((item) => item.receiptId)) return;
    const ctx = context();
    for (const operation of [...pendingOperations.value]) finishOperation(ctx, operation);
    receiptUnknown.value = false; uncertainty.value = '';
  }
  return { session, etag, models, events, hasSession, streaming, running, canSend, canStop, execution, todoSnapshot, stopping,
    messagePending, controlPending, recovering, historyReady, uncertainty, receiptUnknown, lastError, lastErrorCode,
    commands, catalogStatus, catalogError, commandResult, commandPending, pendingOperations, clearSessionView, clearError,
    createSession, getSession, readNamedSession, deleteSession, deleteNamedSession, listModels, loadHistory, recover, sendMessage, interrupt, confirmTool, confirmPlan, answerQuestion,
    changeModel: (modelId: string) => changeSetting({ modelId }),
    changeThinking: (thinking: boolean) => changeSetting({ thinking }),
    changeMode: (mode: 'build' | 'plan') => changeSetting({ mode }),
    renameSession,
    listCommands, executeCommand, invalidateCatalog, acknowledgeUnknown };
}
