import { describe, expect, it, vi } from 'vitest';
import { SessionIndexRepository } from './sessionIndex';
import { memoryStorage } from './storageFixture';

const scope = { apiBase: '', agentId: 'agent-a', callerId: 'caller-a' };
const entry = { sessionId: 'session-a', agentId: 'agent-a', title: '测试会话', agentName: 'Agent',
  lastActivityAt: '2026-09-10T00:00:00Z', pinned: false };
describe('browser session index v2', () => {
  it('restores selected metadata and pending identities inside its environment scope', () => {
    const storage = memoryStorage();
    const first = new SessionIndexRepository(scope, () => storage, vi.fn());
    first.recordActivity(entry); first.select(entry.sessionId);
    first.put(entry.sessionId, { id: 'op-1', kind: 'message', rootId: 'root', receiptId: 'root' });
    const second = new SessionIndexRepository(scope, () => storage, vi.fn());
    expect(second.read()).toEqual({ schemaVersion: 2, activeSessionId: 'session-a', entries: [{ ...entry,
      pending: [{ id: 'op-1', kind: 'message', rootId: 'root', receiptId: 'root' }] }] });
    expect(new SessionIndexRepository({ ...scope, apiBase: 'https://other.test' }, () => storage, vi.fn()).read().entries).toEqual([]);
    expect(new SessionIndexRepository({ ...scope, callerId: 'other' }, () => storage, vi.fn()).read().entries).toEqual([]);
  });
  it('migrates v1 once without changing its array order or pending operations', () => {
    const storage = memoryStorage();
    const repo = new SessionIndexRepository(scope, () => storage, vi.fn());
    storage.setItem(repo.legacyKey, JSON.stringify({ schemaVersion: 1, activeSessionId: 'session-b', entries: [
      { sessionId: 'session-b', agentId: 'agent-a', title: 'B', agentName: 'Agent', updatedAt: '2026-09-01T00:00:00Z',
        pending: [{ id: 'pending', kind: 'command' }] },
      { sessionId: 'session-a', agentId: 'agent-a', title: 'A', agentName: 'Agent', updatedAt: '2026-09-10T00:00:00Z', pending: [] },
    ] }));
    expect(repo.read().entries.map((item) => item.sessionId)).toEqual(['session-b', 'session-a']);
    expect(repo.read().entries[0]).toMatchObject({ lastActivityAt: '2026-09-01T00:00:00Z', pinned: false,
      pending: [{ id: 'pending', kind: 'command' }] });
    expect(JSON.parse(storage.getItem(repo.key)!).schemaVersion).toBe(2);
  });
  it('patches selection and metadata in place, but reorders only accepted activity', () => {
    const storage = memoryStorage();
    const repo = new SessionIndexRepository(scope, () => storage, vi.fn());
    repo.recordActivity({ ...entry, sessionId: 'session-a', lastActivityAt: '2026-09-10T00:00:00Z' });
    repo.recordActivity({ ...entry, sessionId: 'session-b', title: 'B', lastActivityAt: '2026-09-11T00:00:00Z' });
    repo.select('session-a');
    repo.patchMetadata({ ...entry, title: '改名', lastActivityAt: '2000-01-01T00:00:00Z', pinned: true });
    expect(repo.read().entries.map((item) => `${item.sessionId}:${item.title}`)).toEqual(['session-b:B', 'session-a:改名']);
    expect(repo.read().entries[1]).toMatchObject({ pinned: false, lastActivityAt: '2026-09-10T00:00:00Z' });
    repo.recordActivity({ ...entry, title: '改名', lastActivityAt: '2026-09-12T00:00:00Z' });
    expect(repo.read().entries.map((item) => item.sessionId)).toEqual(['session-a', 'session-b']);
  });
  it('does not let a stale tab metadata patch overwrite another tab pin or activity', () => {
    const storage = memoryStorage();
    const stale = new SessionIndexRepository(scope, () => storage, vi.fn());
    const current = new SessionIndexRepository(scope, () => storage, vi.fn());
    stale.recordActivity(entry);
    const staleEntry = stale.read().entries[0];
    current.setPinned(entry.sessionId, true);
    current.recordActivity({ ...entry, lastActivityAt: '2026-09-13T00:00:00Z' });
    stale.patchMetadata({ ...staleEntry, title: '旧页签改名' });
    expect(current.read().entries[0]).toMatchObject({ title: '旧页签改名', pinned: true,
      lastActivityAt: '2026-09-13T00:00:00Z' });
  });
  it('does not let accepted activity from a stale tab overwrite a concurrent rename', () => {
    const storage = memoryStorage();
    const stale = new SessionIndexRepository(scope, () => storage, vi.fn());
    const current = new SessionIndexRepository(scope, () => storage, vi.fn());
    stale.recordActivity(entry);
    current.patchMetadata({ ...entry, title: '其他页签名称' });
    stale.recordActivity({ ...entry, title: '旧页签自动标题', lastActivityAt: '2026-09-14T00:00:00Z' });
    expect(current.read().entries[0]).toMatchObject({ title: '其他页签名称', lastActivityAt: '2026-09-14T00:00:00Z' });
  });
  it('keeps stable pinned/recent sections and returns an unpinned entry to activity order', () => {
    const storage = memoryStorage();
    const repo = new SessionIndexRepository(scope, () => storage, vi.fn());
    repo.recordActivity({ ...entry, sessionId: 'old', lastActivityAt: '2026-09-09T00:00:00Z' });
    repo.recordActivity({ ...entry, sessionId: 'new', lastActivityAt: '2026-09-11T00:00:00Z' });
    repo.setPinned('old', true);
    expect(repo.read().entries.map((item) => item.sessionId)).toEqual(['old', 'new']);
    repo.patchMetadata({ ...repo.read().entries[0], title: '置顶改名' });
    expect(repo.read().entries.map((item) => item.sessionId)).toEqual(['old', 'new']);
    repo.setPinned('old', false);
    expect(repo.read().entries.map((item) => item.sessionId)).toEqual(['new', 'old']);
  });
  it('merges other tab entries and only removes its own operation', () => {
    const storage = memoryStorage();
    const a = new SessionIndexRepository(scope, () => storage, vi.fn());
    const b = new SessionIndexRepository(scope, () => storage, vi.fn());
    a.recordActivity(entry); b.recordActivity({ ...entry, sessionId: 'session-b' });
    a.put('session-a', { id: 'a', kind: 'message' }); b.put('session-a', { id: 'b', kind: 'command' });
    a.remove('session-a', 'a');
    expect(a.read().entries).toHaveLength(2);
    expect(b.list('session-a')).toEqual([{ id: 'b', kind: 'command' }]);
    a.select('session-a'); b.forget('session-a');
    expect(a.read().activeSessionId).toBeNull();
  });
  it('contains corruption and quota failures and retains in-memory navigation', () => {
    const storage = memoryStorage(), report = vi.fn();
    const repo = new SessionIndexRepository(scope, () => storage, report);
    storage.setItem(repo.key, '{bad'); storage.setItem('unrelated', 'keep');
    expect(repo.read().entries).toEqual([]);
    storage.setItem = () => { throw new Error('quota'); };
    repo.recordActivity(entry); repo.select('session-a');
    expect(repo.read().activeSessionId).toBe('session-a');
    expect(repo.read().entries[0].title).toBe('测试会话');
    expect(report).toHaveBeenCalled();
    expect(storage.getItem('unrelated')).toBe('keep');
  });
});
