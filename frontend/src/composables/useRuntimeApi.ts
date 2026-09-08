import { computed, ref, shallowRef } from 'vue';
import type { MessageSubmission, RuntimeEvent, RuntimeSession, SubmissionOutcome, UserEvent } from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';
import { decodeCatalog, decodeCommandResult } from '../runtime/commands';
import type { CommandDescriptor, CommandInvocation, CommandResult } from '../runtime/commands';
import { codePointLength, decodeHistory, decodeModels, decodeSession, invalidResponse, matchesReceipt, messageEvent, readEventStream, record } from '../runtime/protocol';
import { executionState, mergeEvent, reconcileEvents } from '../runtime/eventStore';

const API_PATH = '/campusclaw-service/v1';
const apiBase = (import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? '').replace(/\/+$/u, '');
const callerId = import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web';
type Context = { generation: number; sessionId: string; signal: AbortSignal };

/** 仅内部 Runtime 工作台装配；不兼容或回退到 Mate / Events v1。 */
export function useRuntimeApi() {
  const session = ref<RuntimeSession | null>(null);
  const etag = ref('');
  const models = ref<string[]>([]);
  const events = shallowRef<RuntimeEvent[]>([]);
  const streamCount = ref(0);
  const messagePending = ref(false);
  const controlPending = ref(false);
  const stopping = ref(false);
  const recovering = ref(false);
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
  let interruptedObservation: { rootId: string; receiptId: string } | undefined;
  const execution = computed(() => executionState(events.value));
  const hasSession = computed(() => session.value !== null);
  const streaming = computed(() => streamCount.value > 0);
  const running = computed(() => session.value?.state === 'running' || messagePending.value);
  const canSend = computed(() => hasSession.value && !running.value && !receiptUnknown.value && !recovering.value && !commandPending.value);
  const canStop = computed(() => running.value && !!execution.value.rootId && !execution.value.terminal
    && !stopping.value && !controlPending.value && !messagePending.value);

  function context(): Context {
    if (!session.value) throw new RuntimeApiError({ code: 'SESSION_REQUIRED', message: '请先新建或恢复会话。' });
    return { generation, sessionId: session.value.sessionId, signal: viewController.signal };
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
    stopping.value = false; recovering.value = false; receiptUnknown.value = false; uncertainty.value = '';
    commands.value = []; catalogStatus.value = 'stale'; catalogError.value = '';
    commandResult.value = null; commandPending.value = false;
    catalogFlight = undefined; recoveryFlight = undefined; interruptedObservation = undefined; clearError();
  }
  async function request(ctx: Context, suffix: string, init: RequestInit = {}): Promise<Response> {
    check(ctx);
    const headers = new Headers({ Accept: 'application/json', 'Accept-Language': navigator.language.startsWith('zh') ? 'zh-CN' : 'en-US', 'X-HW-ID': callerId });
    new Headers(init.headers).forEach((value, key) => headers.set(key, value));
    let response: Response;
    try {
      response = await fetch(`${apiBase}${API_PATH}${suffix}`, { ...init, headers, signal: ctx.signal, cache: 'no-store' });
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
    if (ticket === sessionReadTicket) applySession(value, response, ctx, version);
    return value;
  }
  async function getSession(sessionId = session.value?.sessionId): Promise<RuntimeSession> {
    if (!sessionId) throw new RuntimeApiError({ message: '请输入 Session ID。' });
    if (sessionId !== session.value?.sessionId) clearSessionView();
    const ctx = { generation, sessionId, signal: viewController.signal };
    try { return await refreshSession(ctx); } catch (error) { throw publish(error, ctx); }
  }
  async function createSession(agentId: string): Promise<RuntimeSession> {
    clearSessionView();
    const ctx = { generation, sessionId: '', signal: viewController.signal };
    try {
      const response = await request(ctx, `/agents/${encodeURIComponent(agentId)}/sessions`, { method: 'POST' });
      const created = decodeSession(await result(response));
      check(ctx);
      await getSession(created.sessionId);
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
    recovering.value = true;
    const flight = (async () => {
      try {
        await loadHistory();
        await refreshSession(ctx);
        check(ctx);
        if (!receiptUnknown.value && interruptedObservation) {
          const receiptIndex = events.value.findIndex((event) => event.eventId === interruptedObservation?.receiptId);
          const ended = receiptIndex >= 0 && events.value.slice(receiptIndex + 1).some((event) => event.type === 'session.status_idle' && event.sourceEventId === interruptedObservation?.rootId);
          if (ended) { uncertainty.value = ''; interruptedObservation = undefined; }
        }
        stopping.value = execution.value.interruptPending;
      } catch (error) { throw publish(error, ctx); }
      finally { if (current(ctx)) { recovering.value = false; recoveryFlight = undefined; } }
    })();
    recoveryFlight = flight;
    return flight;
  }
  async function changeSetting(suffix: string, body: unknown): Promise<RuntimeSession> {
    const ctx = context();
    try {
      if (!etag.value) await refreshSession(ctx);
      check(ctx);
      const settingRevision = ++revision;
      const response = await request(ctx, path(ctx, suffix), { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': etag.value }, body: JSON.stringify(body) });
      const value = decodeSession(await result(response));
      applySession(value, response, ctx, settingRevision);
      await loadHistory();
      return value;
    } catch (error) {
      if (current(ctx) && error instanceof RuntimeApiError && (error.status === 412 || error.outcomeUncertain)) {
        await recover().catch(() => undefined);
        if (error.status === 412) error = new RuntimeApiError({ code: 'SESSION_VERSION_MISMATCH', message: '设置已被更新，已重新读取。请核对当前值，再明确选择；没有自动重试修改。' });
      }
      throw publish(error, ctx);
    }
  }
  async function deleteSession(): Promise<void> {
    const ctx = context();
    try { await request(ctx, path(ctx), { method: 'DELETE' }); check(ctx); clearSessionView(); }
    catch (error) { throw publish(error, ctx); }
  }
  function writeHeaders(custom?: HeadersInit, stream = true): Headers {
    const headers = new Headers(custom);
    // 临时凭据逐动作复制；不得覆盖协议头或引入不存在的幂等/版本机制。
    headers.set('Accept', stream ? 'text/event-stream' : 'application/json');
    headers.set('Content-Type', 'application/json');
    headers.delete('If-Match'); headers.delete('Idempotency-Key'); headers.delete('Last-Event-ID');
    return headers;
  }
  async function submitStream(input: UserEvent | CommandInvocation, custom?: HeadersInit): Promise<MessageSubmission> {
    const ctx = context();
    const skill = 'executionMode' in input;
    const isMessage = skill || input.type === 'user.message';
    if (isMessage ? !canSend.value : controlPending.value) throw new RuntimeApiError({ code: 'SESSION_BUSY', message: '请等待当前操作，或先重新核对会话。' });
    const ticket = isMessage ? ++messageTicket : ++controlTicket;
    if (isMessage) messagePending.value = true;
    else controlPending.value = true;
    revision++; clearError();
    let response: Response;
    try {
      response = await request(ctx, path(ctx, skill ? '/command' : '/events'), {
        method: 'POST', headers: writeHeaders(custom), body: JSON.stringify(skill ? input.request : { event: input }),
      });
    } catch (error) {
      if (current(ctx)) {
        messagePending.value = false; controlPending.value = false;
        if (error instanceof RuntimeApiError && error.outcomeUncertain) { receiptUnknown.value = true; uncertainty.value = error.message; }
        invalidateCatalog();
      }
      throw publish(error, ctx);
    }
    let settle!: (outcome: SubmissionOutcome) => void;
    const confirmation = new Promise<SubmissionOutcome>((resolve) => { settle = resolve; });
    streamCount.value++;
    void consume(response, input, ctx, settle, ticket);
    return { confirmation };
  }
  async function consume(response: Response, input: UserEvent | CommandInvocation, ctx: Context, settle: (outcome: SubmissionOutcome) => void, ticket: number): Promise<void> {
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
          interruptedObservation = { rootId, receiptId: receipt.eventId };
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
    commandPending.value = true;
    const commandRevision = ++revision;
    clearError();
    try {
      const response = await request(ctx, path(ctx, '/command'), { method: 'POST', headers: writeHeaders(headers, false), body: JSON.stringify(invocation.request) });
      const decoded = decodeCommandResult(invocation, await result(response));
      check(ctx);
      if (decoded.kind === 'session') applySession(decoded.value, response, ctx, commandRevision);
      if (decoded.kind === 'models') models.value = decoded.value.models;
      commandResult.value = decoded;
      return 'confirmed';
    } catch (error) {
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
    if (recovering.value || session.value?.state !== 'idle') return;
    receiptUnknown.value = false; uncertainty.value = '';
  }
  return { session, etag, models, events, hasSession, streaming, running, canSend, canStop, execution, stopping,
    messagePending, controlPending, recovering, uncertainty, receiptUnknown, lastError, lastErrorCode,
    commands, catalogStatus, catalogError, commandResult, commandPending, clearSessionView, clearError,
    createSession, getSession, deleteSession, listModels, loadHistory, recover, sendMessage, interrupt, confirmTool,
    changeModel: (modelId: string) => changeSetting('/model', { modelId }),
    changeThinking: (thinking: boolean) => changeSetting('/thinking', { thinking }),
    listCommands, executeCommand, invalidateCatalog, acknowledgeUnknown };
}
