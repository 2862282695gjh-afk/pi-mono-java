import { describe, expect, it, vi } from 'vitest';
import { SessionIndexRepository } from './sessionIndex';
import { memoryStorage } from './storageFixture';

const scope = { apiBase: '', agentId: 'agent-a', callerId: 'caller-a' };
const entry = { sessionId: 'session-a', agentId: 'agent-a', title: '测试会话', agentName: 'Agent', updatedAt: '2026-09-10T00:00:00Z' };
describe('browser session index', () => {
  it('restores the selected ID, metadata and pending identities without body or credentials', () => {
    const storage = memoryStorage();
    const first = new SessionIndexRepository(scope, () => storage, vi.fn());
    first.upsert(entry); first.select(entry.sessionId);
    first.put(entry.sessionId, { id: 'op-1', kind: 'message', rootId: 'root', receiptId: 'root' });
    const second = new SessionIndexRepository(scope, () => storage, vi.fn());
    expect(second.read()).toEqual({ schemaVersion: 1, activeSessionId: 'session-a', entries: [{ ...entry, pending: [{ id: 'op-1', kind: 'message', rootId: 'root', receiptId: 'root' }] }] });
    expect(new SessionIndexRepository({ ...scope, apiBase: 'https://other.test' }, () => storage, vi.fn()).read().entries).toEqual([]);
    expect(new SessionIndexRepository({ ...scope, callerId: 'other' }, () => storage, vi.fn()).read().entries).toEqual([]);
  });
  it('merges another tab entries and operations and only removes its own operation', () => {
    const storage = memoryStorage();
    const a = new SessionIndexRepository(scope, () => storage, vi.fn());
    const b = new SessionIndexRepository(scope, () => storage, vi.fn());
    a.upsert(entry); b.upsert({ ...entry, sessionId: 'session-b' });
    a.put('session-a', { id: 'a', kind: 'message' }); b.put('session-a', { id: 'b', kind: 'command' });
    a.remove('session-a', 'a');
    expect(a.read().entries).toHaveLength(2);
    expect(b.list('session-a')).toEqual([{ id: 'b', kind: 'command' }]);
    a.select('session-a'); b.forget('session-a');
    expect(a.read().activeSessionId).toBeNull();
    expect(a.read().entries.map((item) => item.sessionId)).toEqual(['session-b']);
  });
  it('contains corruption and quota failures in its own namespace and retains in-memory navigation', () => {
    const storage = memoryStorage(), report = vi.fn();
    const repo = new SessionIndexRepository(scope, () => storage, report);
    storage.setItem(repo.key, '{bad'); storage.setItem('unrelated', 'keep');
    expect(repo.read().entries).toEqual([]);
    storage.setItem = () => { throw new Error('quota'); };
    repo.upsert(entry); repo.select('session-a');
    expect(repo.read().activeSessionId).toBe('session-a');
    expect(repo.read().entries[0].title).toBe('测试会话');
    expect(report).toHaveBeenCalled();
    expect(storage.getItem('unrelated')).toBe('keep');
  });
});
