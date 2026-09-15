import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import type { AgentOption } from '../types/product';
import type { RuntimeSession } from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';
import { SessionIndexRepository } from '../runtime/sessionIndex';
import { useRuntimeApi } from './useRuntimeApi';

export function useWorkbenchSessions(agent: AgentOption) {
  const storageNotice = ref('');
  const repository = new SessionIndexRepository({
    apiBase: (import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? '').replace(/\/+$/u, ''), agentId: agent.id,
    callerId: import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web',
  }, () => window.localStorage, (notice) => { storageNotice.value = notice; });
  const initial = repository.read();
  const threads = ref(initial.entries);
  const activeSessionId = ref(initial.activeSessionId);
  const unavailable = ref<string[]>([]);
  const restoring = ref(false);
  const restoreError = ref('');
  const recoveryExhausted = ref(false);
  const runtime = useRuntimeApi(repository, (created) => { recordCreatedSession(created); select(created.sessionId); }, patchSession);
  const canSend = computed(() => runtime.canSend.value && !restoring.value && !restoreError.value);
  const currentThread = computed(() => threads.value.find((thread) => thread.sessionId === activeSessionId.value));
  const message = ref('');
  const drafts = new Map<string, string>();
  let navigation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let polling = 0;
  const syncThreads = () => { threads.value = repository.read().entries; };

  function select(id: string | null): void {
    if (activeSessionId.value) drafts.set(activeSessionId.value, message.value);
    activeSessionId.value = id;
    message.value = id ? drafts.get(id) ?? '' : '';
    repository.select(id);
  }
  function trackSession(): void {
    const session = runtime.session.value;
    if (!session) return;
    patchSession(session);
  }
  function sessionEntry(session: RuntimeSession) {
    const previous = threads.value.find((thread) => thread.sessionId === session.sessionId);
    return { sessionId: session.sessionId, agentId: session.agentId,
      title: session.displayName || previous?.title || '新会话', agentName: previous?.agentName || agent.name,
      lastActivityAt: previous?.lastActivityAt || session.updatedAt || session.createdAt, pinned: previous?.pinned ?? false };
  }
  function patchSession(session: RuntimeSession): void {
    repository.patchMetadata(sessionEntry(session));
    syncThreads();
  }
  function recordCreatedSession(session: RuntimeSession): void {
    repository.recordActivity({ ...sessionEntry(session), lastActivityAt: new Date().toISOString() });
    syncThreads();
  }
  async function resumeSession(id: string): Promise<void> {
    const turn = ++navigation;
    stopPolling();
    runtime.clearSessionView();
    select(id); restoring.value = true; restoreError.value = '';
    try {
      await runtime.getSession(id);
      if (turn !== navigation) return;
      trackSession();
      unavailable.value = unavailable.value.filter((item) => item !== id);
      const results = await Promise.allSettled([runtime.listModels(), runtime.recover()]);
      for (const result of results) if (result.status === 'rejected') throw result.reason;
    } catch (error) {
      if (turn !== navigation) return;
      if (error instanceof RuntimeApiError && [403, 404].includes(error.status)) {
        unavailable.value = [...new Set([...unavailable.value, id])];
      }
      restoreError.value = '会话未能完整恢复，请重试；不会自动创建或重发消息。';
    } finally {
      if (turn === navigation) { restoring.value = false; restartPolling(); }
    }
  }
  function newConversation(): void {
    navigation++; stopPolling(); runtime.clearSessionView(); select(null);
    restoring.value = false; restoreError.value = '';
  }
  async function createSession(agentId = agent.id): Promise<void> {
    if (!agentId) return;
    newConversation();
    const turn = ++navigation;
    restoring.value = true;
    try {
      await runtime.createSession(agentId);
      if (turn !== navigation || !runtime.session.value) return;
      trackSession(); select(runtime.session.value.sessionId);
    } catch (error) {
      if (turn === navigation) restoreError.value = '会话创建或读取未完成，请检查错误；已有恢复标识会保留，不会自动重建。';
      throw error;
    } finally { if (turn === navigation) restoring.value = false; }
  }
  function forgetSession(id: string): void {
    if (activeSessionId.value === id) newConversation();
    repository.forget(id); syncThreads();
    unavailable.value = unavailable.value.filter((item) => item !== id);
  }
  function touchCurrentThread(text: string): void {
    const entry = currentThread.value;
    if (!entry) return;
    repository.recordActivity({ sessionId: entry.sessionId, agentId: entry.agentId, agentName: entry.agentName,
      title: ['新会话', '已恢复的会话'].includes(entry.title)
        ? Array.from(text).slice(0, 24).join('') + (Array.from(text).length > 24 ? '…' : '') : entry.title,
      lastActivityAt: new Date().toISOString() });
    syncThreads();
  }
  async function renameThread(sessionId: string, displayName: string): Promise<void> {
    const renamed = await runtime.renameSession(sessionId, displayName);
    patchSession(renamed);
  }
  function togglePinned(sessionId: string): void {
    const entry = threads.value.find((thread) => thread.sessionId === sessionId);
    if (!entry) return;
    repository.setPinned(sessionId, !entry.pinned); syncThreads();
  }
  function stopPolling(): void { polling++; clearTimeout(timer); recoveryExhausted.value = false; }
  function restartPolling(): void {
    stopPolling();
    if (restoring.value || !runtime.hasSession.value || !runtime.running.value || runtime.streaming.value || document.visibilityState === 'hidden') return;
    const turn = polling;
    let attempts = 0;
    const schedule = () => {
      if (turn !== polling || !runtime.running.value || runtime.streaming.value) return;
      if (attempts === 6) { recoveryExhausted.value = true; return; }
      timer = setTimeout(async () => {
        attempts++;
        try { await runtime.recover(); }
        catch { if (turn === polling) restoreError.value = '状态读取失败，请检查连接后刷新状态。'; }
        schedule();
      }, [1000, 2000, 5000][Math.min(attempts, 2)]);
    };
    schedule();
  }
  async function refreshState(): Promise<void> {
    restoreError.value = '';
    if (!runtime.hasSession.value) { if (activeSessionId.value) await resumeSession(activeSessionId.value); return; }
    try { await runtime.recover(); }
    finally { restartPolling(); }
  }
  function visibility(): void { if (document.visibilityState === 'visible') restartPolling(); else stopPolling(); }
  watch([() => runtime.session.value?.sessionId, runtime.running, runtime.streaming], restartPolling);
  watch([() => runtime.session.value?.sessionId, () => runtime.session.value?.displayName], ([id]) => {
    if (id) { trackSession(); if (!activeSessionId.value) select(id); }
  }, { flush: 'sync' });
  onMounted(() => {
    document.addEventListener('visibilitychange', visibility);
    if (initial.activeSessionId) void resumeSession(initial.activeSessionId);
  });
  onUnmounted(() => { navigation++; stopPolling(); runtime.clearSessionView(); document.removeEventListener('visibilitychange', visibility); });
  return { runtime, canSend, threads, currentThread, activeSessionId, message, restoring, restoreError, storageNotice, unavailable,
    recoveryExhausted, resumeSession, createSession, newConversation, forgetSession, touchCurrentThread, renameThread,
    togglePinned, refreshState };
}
