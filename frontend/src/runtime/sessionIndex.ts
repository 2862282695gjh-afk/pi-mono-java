import type { ThreadSummary } from '../types/product';

export interface PendingOperation {
  id: string;
  kind: 'message' | 'skill' | 'command' | 'interrupt' | 'confirmation' | 'plan-confirmation' | 'question-answer';
  rootId?: string;
  receiptId?: string;
  targetEventId?: string;
}
export interface PendingStore {
  list(sessionId: string): PendingOperation[];
  put(sessionId: string, operation: PendingOperation): void;
  remove(sessionId: string, operationId: string): void;
}
export interface SessionIndexEntry extends ThreadSummary {
  agentId: string;
  pending: PendingOperation[];
}
interface SessionIndex { schemaVersion: 2; activeSessionId: string | null; entries: SessionIndexEntry[] }
interface LegacySessionIndex { schemaVersion: 1; activeSessionId: string | null; entries: Array<{
  sessionId: string; agentId: string; title: string; agentName: string; updatedAt: string; pending: PendingOperation[];
}> }
const emptyIndex = (): SessionIndex => ({ schemaVersion: 2, activeSessionId: null, entries: [] });
const isText = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 2048;
const kinds = new Set(['message', 'skill', 'command', 'interrupt', 'confirmation', 'plan-confirmation', 'question-answer']);
function decodeOperation(value: unknown): PendingOperation {
  const item = value as PendingOperation;
  if (!item || !isText(item.id) || !kinds.has(item.kind)
    || (item.rootId !== undefined && !isText(item.rootId)) || (item.receiptId !== undefined && !isText(item.receiptId))
    || (item.targetEventId !== undefined && !isText(item.targetEventId))) throw new Error('Invalid operation');
  return { id: item.id, kind: item.kind, ...(item.rootId ? { rootId: item.rootId } : {}),
    ...(item.receiptId ? { receiptId: item.receiptId } : {}), ...(item.targetEventId ? { targetEventId: item.targetEventId } : {}) };
}
function decodeEntry(entry: SessionIndexEntry, ids: Set<string>): SessionIndexEntry {
  if (!entry || !isText(entry.sessionId) || !isText(entry.agentId) || !isText(entry.title)
    || !isText(entry.agentName) || !isText(entry.lastActivityAt) || !Number.isFinite(Date.parse(entry.lastActivityAt))
    || typeof entry.pinned !== 'boolean' || !Array.isArray(entry.pending) || ids.has(entry.sessionId)) throw new Error('Invalid entry');
  ids.add(entry.sessionId);
  return { sessionId: entry.sessionId, agentId: entry.agentId, title: entry.title, agentName: entry.agentName,
    lastActivityAt: entry.lastActivityAt, pinned: entry.pinned, pending: entry.pending.map(decodeOperation) };
}
function decodeIndex(raw: string): SessionIndex {
  const value = JSON.parse(raw) as SessionIndex;
  if (value?.schemaVersion !== 2 || !Array.isArray(value.entries)
    || (value.activeSessionId !== null && !isText(value.activeSessionId))) throw new Error('Invalid index');
  const ids = new Set<string>();
  const entries = value.entries.map((entry) => decodeEntry(entry, ids));
  return { schemaVersion: 2, activeSessionId: ids.has(value.activeSessionId ?? '') ? value.activeSessionId : null, entries };
}
function decodeLegacyIndex(raw: string): SessionIndex {
  const value = JSON.parse(raw) as LegacySessionIndex;
  if (value?.schemaVersion !== 1 || !Array.isArray(value.entries)
    || (value.activeSessionId !== null && !isText(value.activeSessionId))) throw new Error('Invalid legacy index');
  const ids = new Set<string>();
  const entries = value.entries.map((entry) => {
    if (!entry || !isText(entry.sessionId) || !isText(entry.agentId) || !isText(entry.title)
      || !isText(entry.agentName) || !isText(entry.updatedAt) || !Number.isFinite(Date.parse(entry.updatedAt))
      || !Array.isArray(entry.pending) || ids.has(entry.sessionId)) throw new Error('Invalid legacy entry');
    ids.add(entry.sessionId);
    return { sessionId: entry.sessionId, agentId: entry.agentId, title: entry.title, agentName: entry.agentName,
      lastActivityAt: entry.updatedAt, pinned: false, pending: entry.pending.map(decodeOperation) };
  });
  return { schemaVersion: 2, activeSessionId: ids.has(value.activeSessionId ?? '') ? value.activeSessionId : null, entries };
}

function orderEntries(entries: SessionIndexEntry[]): SessionIndexEntry[] {
  return entries.map((entry, index) => ({ entry, index })).sort((a, b) => Number(b.entry.pinned) - Number(a.entry.pinned)
    || Date.parse(b.entry.lastActivityAt) - Date.parse(a.entry.lastActivityAt) || a.index - b.index).map(({ entry }) => entry);
}

/** 只保存恢复索引与请求身份；每次合并最新存储，当前页签的导航由页面自己持有。 */
export class SessionIndexRepository implements PendingStore {
  readonly key: string;
  readonly legacyKey: string;
  private memory = emptyIndex();
  private writeFailed = false;
  constructor(scope: { apiBase: string; agentId: string; callerId: string },
    private storage: () => Storage, private report: (message: string) => void) {
    const suffix = JSON.stringify([scope.apiBase, scope.agentId, scope.callerId]);
    this.key = 'campusclaw.session-index.v2:' + suffix;
    this.legacyKey = 'campusclaw.session-index.v1:' + suffix;
  }
  read(): SessionIndex {
    if (this.writeFailed) return structuredClone(this.memory);
    try {
      const storage = this.storage();
      const raw = storage.getItem(this.key);
      if (raw !== null) this.memory = decodeIndex(raw);
      else {
        const legacy = storage.getItem(this.legacyKey);
        this.memory = legacy === null ? emptyIndex() : decodeLegacyIndex(legacy);
        if (legacy !== null) {
          try { storage.setItem(this.key, JSON.stringify(this.memory)); }
          catch {
            this.writeFailed = true;
            this.report('无法保存会话恢复入口，请检查浏览器本地存储设置。');
          }
        }
      }
    } catch {
      this.report('会话索引无法读取，当前页面仍可使用；新建或切换后将尝试重新保存。');
    }
    return structuredClone(this.memory);
  }
  private update(change: (index: SessionIndex) => void): void {
    const value = this.read();
    change(value);
    this.memory = value;
    try { this.storage().setItem(this.key, JSON.stringify(value)); this.writeFailed = false; }
    catch { this.writeFailed = true; this.report('无法保存会话恢复入口，请检查浏览器本地存储设置。'); }
  }
  select(sessionId: string | null): void { this.update((index) => { index.activeSessionId = sessionId; }); }
  patchMetadata(entry: Omit<SessionIndexEntry, 'pending'>): void {
    this.update((index) => {
      const old = index.entries.find((item) => item.sessionId === entry.sessionId);
      const next = old ? { ...entry, lastActivityAt: old.lastActivityAt, pinned: old.pinned, pending: old.pending }
        : { ...entry, pending: [] };
      if (!old) index.entries.push(next);
      else index.entries[index.entries.indexOf(old)] = next;
    });
  }
  recordActivity(entry: Omit<SessionIndexEntry, 'pending' | 'pinned'> & { pinned?: boolean }): void {
    this.update((index) => {
      const old = index.entries.find((item) => item.sessionId === entry.sessionId);
      const keepCurrentTitle = old && !['新会话', '已恢复的会话'].includes(old.title);
      const next = { ...entry, title: keepCurrentTitle ? old.title : entry.title,
        pinned: old?.pinned ?? entry.pinned ?? false, pending: old?.pending ?? [] };
      index.entries = orderEntries([...index.entries.filter((item) => item.sessionId !== entry.sessionId), next]);
    });
  }
  setPinned(sessionId: string, pinned: boolean): void {
    this.update((index) => {
      const entry = index.entries.find((item) => item.sessionId === sessionId);
      if (entry) { entry.pinned = pinned; index.entries = orderEntries(index.entries); }
    });
  }
  forget(sessionId: string): void {
    this.update((index) => {
      index.entries = index.entries.filter((item) => item.sessionId !== sessionId);
      if (index.activeSessionId === sessionId) index.activeSessionId = null;
    });
  }
  list(sessionId: string): PendingOperation[] { return this.read().entries.find((entry) => entry.sessionId === sessionId)?.pending ?? []; }
  put(sessionId: string, operation: PendingOperation): void {
    this.update((index) => {
      const entry = index.entries.find((item) => item.sessionId === sessionId);
      if (entry) entry.pending = [...entry.pending.filter((item) => item.id !== operation.id), decodeOperation(operation)];
    });
  }
  remove(sessionId: string, operationId: string): void {
    this.update((index) => {
      const entry = index.entries.find((item) => item.sessionId === sessionId);
      if (entry) entry.pending = entry.pending.filter((item) => item.id !== operationId);
    });
  }
}
