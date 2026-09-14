import type { ThreadSummary } from '../types/product';

export interface PendingOperation {
  id: string;
  kind: 'message' | 'skill' | 'command' | 'interrupt' | 'confirmation' | 'plan-confirmation';
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
interface SessionIndex { schemaVersion: 1; activeSessionId: string | null; entries: SessionIndexEntry[] }
const emptyIndex = (): SessionIndex => ({ schemaVersion: 1, activeSessionId: null, entries: [] });
const isText = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 2048;
const kinds = new Set(['message', 'skill', 'command', 'interrupt', 'confirmation', 'plan-confirmation']);
function decodeOperation(value: unknown): PendingOperation {
  const item = value as PendingOperation;
  if (!item || !isText(item.id) || !kinds.has(item.kind)
    || (item.rootId !== undefined && !isText(item.rootId)) || (item.receiptId !== undefined && !isText(item.receiptId))
    || (item.targetEventId !== undefined && !isText(item.targetEventId))) throw new Error('Invalid operation');
  return { id: item.id, kind: item.kind, ...(item.rootId ? { rootId: item.rootId } : {}),
    ...(item.receiptId ? { receiptId: item.receiptId } : {}), ...(item.targetEventId ? { targetEventId: item.targetEventId } : {}) };
}
function decodeIndex(raw: string): SessionIndex {
  const value = JSON.parse(raw) as SessionIndex;
  if (value?.schemaVersion !== 1 || !Array.isArray(value.entries)
    || (value.activeSessionId !== null && !isText(value.activeSessionId))) throw new Error('Invalid index');
  const ids = new Set<string>();
  const entries = value.entries.map((entry) => {
    if (!entry || !isText(entry.sessionId) || !isText(entry.agentId) || !isText(entry.title)
      || !isText(entry.agentName) || !isText(entry.updatedAt) || !Number.isFinite(Date.parse(entry.updatedAt))
      || !Array.isArray(entry.pending) || ids.has(entry.sessionId)) throw new Error('Invalid entry');
    ids.add(entry.sessionId);
    return { sessionId: entry.sessionId, agentId: entry.agentId, title: entry.title, agentName: entry.agentName,
      updatedAt: entry.updatedAt, pending: entry.pending.map(decodeOperation) };
  });
  return { schemaVersion: 1, activeSessionId: ids.has(value.activeSessionId ?? '') ? value.activeSessionId : null, entries };
}

/** 只保存恢复索引与请求身份；每次合并最新存储，当前页签的导航由页面自己持有。 */
export class SessionIndexRepository implements PendingStore {
  readonly key: string;
  private memory = emptyIndex();
  private writeFailed = false;
  constructor(scope: { apiBase: string; agentId: string; callerId: string },
    private storage: () => Storage, private report: (message: string) => void) {
    this.key = 'campusclaw.session-index.v1:' + JSON.stringify([scope.apiBase, scope.agentId, scope.callerId]);
  }
  read(): SessionIndex {
    if (this.writeFailed) return structuredClone(this.memory);
    try {
      const raw = this.storage().getItem(this.key);
      this.memory = raw === null ? emptyIndex() : decodeIndex(raw);
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
  upsert(entry: Omit<SessionIndexEntry, 'pending'>): void {
    this.update((index) => {
      const old = index.entries.find((item) => item.sessionId === entry.sessionId);
      index.entries = [{ sessionId: entry.sessionId, agentId: entry.agentId, title: entry.title, agentName: entry.agentName, updatedAt: entry.updatedAt, pending: old?.pending ?? [] }, ...index.entries.filter((item) => item.sessionId !== entry.sessionId)];
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
