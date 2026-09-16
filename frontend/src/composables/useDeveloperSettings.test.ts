import { describe, expect, it, vi } from 'vitest';
import { memoryStorage } from '../runtime/storageFixture';
import { createDeveloperSettingsController } from './useDeveloperSettings';

const defaults = {
  apiBase: '',
  backendUrl: 'http://backend.test',
  callerId: 'browser-test',
  agentId: 'agent-a',
  agentName: 'Agent',
  agentDescription: '',
  agentCategory: '',
};
const status = {
  version: 0,
  proxyVersion: 0,
  proxyKey: 'backend-key',
  authVersion: 0,
  authKey: 'auth-key',
  backendUrl: 'http://backend.test',
  backendSource: 'startup' as const,
  account: 'startup-account',
  password: 'startup-password',
  accountConfigured: true,
  passwordConfigured: true,
  accountSource: 'startup' as const,
  passwordSource: 'startup' as const,
};

function json(result: unknown): Response {
  return new Response(JSON.stringify({ resCode: '0', resMsg: 'ok', result }), {
    headers: { 'Content-Type': 'application/json' },
  });
}

function deferredResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((done) => { resolve = done; });
  return { promise, resolve };
}

describe('developer settings controller', () => {
  it('ignores a token returned after the authentication draft changes', async () => {
    const token = deferredResponse();
    const storage = memoryStorage();
    const fetcher = vi.fn((input: RequestInfo | URL) => String(input) === '/__debug/config'
      ? Promise.resolve(json(status)) : token.promise);
    const settings = createDeveloperSettingsController({
      defaults,
      fetcher: fetcher as typeof fetch,
      storage: () => storage,
      pageOrigin: 'http://localhost:4173',
      listenForStorage: false,
    });
    await settings.initialize();
    settings.authIpDraft.value = '192.0.2.1';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: true });

    const pending = settings.getToken();
    expect(fetcher).toHaveBeenCalledTimes(2);
    settings.authIpDraft.value = '192.0.2.2';
    token.resolve(json({ accessToken: 'late-token' }));

    await expect(pending).resolves.toMatchObject({ ok: false });
    expect(settings.applied.value.headers.some((row) => row.value === 'late-token')).toBe(false);
    expect(settings.tokenPending.value).toBe(false);
  });

  it('does not let an older aborted token request clear or overwrite the newer request', async () => {
    const first = deferredResponse();
    const second = deferredResponse();
    let tokenCalls = 0;
    const fetcher = vi.fn((input: RequestInfo | URL) => {
      if (String(input) === '/__debug/config') return Promise.resolve(json(status));
      tokenCalls += 1;
      return tokenCalls === 1 ? first.promise : second.promise;
    });
    const storage = memoryStorage();
    const settings = createDeveloperSettingsController({
      defaults,
      fetcher: fetcher as typeof fetch,
      storage: () => storage,
      pageOrigin: 'http://localhost:4173',
      listenForStorage: false,
    });
    await settings.initialize();
    settings.authIpDraft.value = '192.0.2.1';
    await settings.saveAuthentication();

    const stale = settings.getToken();
    settings.cancelToken();
    const current = settings.getToken();
    first.resolve(json({ accessToken: 'stale-token' }));
    await stale;
    expect(settings.tokenPending.value).toBe(true);
    expect(settings.authStatus.value).toBe('正在获取…');

    second.resolve(json({ accessToken: 'current-token' }));
    await expect(current).resolves.toMatchObject({ ok: true });
    expect(settings.tokenPending.value).toBe(false);
    expect(settings.applied.value.headers.find((row) => row.key === 'access-token')?.value).toBe('current-token');
    expect(storage.getItem('campusclaw.developer-settings.v1')).not.toContain('stale-token');
  });

  it('persists only the fetched token while preserving unrelated unsaved header drafts', async () => {
    const storage = memoryStorage();
    const fetcher = vi.fn((input: RequestInfo | URL) => String(input) === '/__debug/config'
      ? Promise.resolve(json(status)) : Promise.resolve(json({ accessToken: 'fresh-token' })));
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    settings.authIpDraft.value = '192.0.2.1';
    await settings.saveAuthentication();
    settings.appendHeader();
    const draft = settings.headerRows.value.at(-1)!;
    draft.key = 'x-unsaved-draft';
    draft.value = 'draft-value';

    await expect(settings.getToken()).resolves.toMatchObject({ ok: true });

    expect(settings.headerRows.value.some((row) => row.key === 'x-unsaved-draft')).toBe(true);
    expect(settings.applied.value.headers.some((row) => row.key === 'x-unsaved-draft')).toBe(false);
    expect(storage.getItem('campusclaw.developer-settings.v1')).not.toContain('x-unsaved-draft');
    expect(settings.applied.value.headers.find((row) => row.key === 'access-token')?.value).toBe('fresh-token');
  });

  it('stops using cleared headers immediately when durable removal fails', async () => {
    const storage = memoryStorage();
    const fetcher = vi.fn(() => Promise.resolve(json(status)));
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    settings.headerRows.value = [{ id: 1, enabled: true, key: 'x-test-secret', value: 'temporary-value', revealed: false }];
    await expect(settings.saveHeaders()).resolves.toMatchObject({ ok: true });
    const retained = storage.getItem('campusclaw.developer-settings.v1');
    storage.setItem = () => { throw new Error('blocked'); };

    await expect(settings.clearHeaders()).resolves.toMatchObject({ ok: false });

    expect(settings.applied.value.headers).toEqual([]);
    expect([...settings.snapshotHeaders()!.entries()]).toEqual([]);
    expect(storage.getItem('campusclaw.developer-settings.v1')).toBe(retained);
  });

  it('applies the connection snapshot being saved and retains edits made while the server write is pending', async () => {
    const saving = deferredResponse();
    const storage = memoryStorage();
    const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/__debug/config' && init?.method === 'PUT') return saving.promise;
      return Promise.resolve(json(status));
    });
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    settings.connectionDraft.value.backendUrl = 'http://saved-backend.test';
    settings.connectionDraft.value.callerId = 'saved-caller';

    const pending = settings.saveConnection();
    settings.connectionDraft.value.backendUrl = 'http://newer-backend.test';
    settings.connectionDraft.value.callerId = 'newer-caller';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: false });
    saving.resolve(json({ ...status, version: 1, proxyVersion: 1, proxyKey: 'saved-key',
      backendUrl: 'http://saved-backend.test', backendSource: 'saved' }));

    await expect(pending).resolves.toMatchObject({ ok: true });
    expect(settings.applied.value.connection).toMatchObject({
      backendUrl: 'http://saved-backend.test', callerId: 'saved-caller', proxyKey: 'saved-key',
    });
    expect(settings.connectionDraft.value).toMatchObject({
      backendUrl: 'http://newer-backend.test', callerId: 'newer-caller',
    });
  });

  it('keeps authentication and environment snapshots stable during a credential save', async () => {
    const saving = deferredResponse();
    const storage = memoryStorage();
    const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/__debug/config' && init?.method === 'PUT') return saving.promise;
      return Promise.resolve(json(status));
    });
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    settings.authIpDraft.value = '192.0.2.1';
    settings.accountDraft.value = 'saved-account';
    settings.passwordDraft.value = 'saved-password';

    const pending = settings.saveAuthentication();
    await expect(settings.getToken()).resolves.toMatchObject({ ok: false, message: expect.stringContaining('正在保存') });
    settings.authIpDraft.value = '192.0.2.2';
    settings.accountDraft.value = 'newer-account';
    settings.passwordDraft.value = 'newer-password';
    settings.connectionDraft.value.backendUrl = 'http://other-backend.test';
    await expect(settings.saveConnection()).resolves.toMatchObject({ ok: false });
    saving.resolve(json({ ...status, version: 1, authVersion: 1, authKey: 'new-auth-key',
      account: 'saved-account', password: 'saved-password',
      accountSource: 'saved', passwordSource: 'saved' }));

    await expect(pending).resolves.toMatchObject({ ok: true });
    expect(settings.applied.value.backendIdentity).toBe('proxy:http://backend.test');
    expect(settings.applied.value.authIp).toBe('192.0.2.1');
    expect(settings.authIpDraft.value).toBe('192.0.2.2');
    expect(settings.accountDraft.value).toBe('newer-account');
    expect(settings.passwordDraft.value).toBe('newer-password');
    expect(storage.getItem('campusclaw.developer-settings.v1')).toContain('192.0.2.1');
    expect(storage.getItem('campusclaw.developer-settings.v1')).not.toContain('192.0.2.2');
  });

  it.each(['account', 'password'] as const)('discards a late token when the %s draft changes', async (field) => {
    const token = deferredResponse();
    const storage = memoryStorage();
    const fetcher = vi.fn((input: RequestInfo | URL) => String(input) === '/__debug/config'
      ? Promise.resolve(json(status)) : token.promise);
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    settings.authIpDraft.value = '192.0.2.1';
    await settings.saveAuthentication();
    const pending = settings.getToken();
    settings[field === 'account' ? 'accountDraft' : 'passwordDraft'].value = 'newer-value';
    token.resolve(json({ accessToken: 'late-token' }));
    await expect(pending).resolves.toMatchObject({ ok: false });
    expect(settings.applied.value.headers.some((row) => row.value === 'late-token')).toBe(false);
    expect(storage.getItem('campusclaw.developer-settings.v1')).not.toContain('late-token');
  });

  it('preserves credential edits made before or during configuration loading', async () => {
    const loading = deferredResponse();
    const settings = createDeveloperSettingsController({ defaults,
      fetcher: vi.fn(() => loading.promise) as typeof fetch, storage: () => memoryStorage(),
      pageOrigin: 'http://localhost:4173', listenForStorage: false });
    settings.accountDraft.value = 'early-account';
    const pending = settings.initialize();
    settings.passwordDraft.value = 'newer-password';
    loading.resolve(json(status));
    await pending;
    expect(settings.accountDraft.value).toBe('early-account');
    expect(settings.passwordDraft.value).toBe('newer-password');
    expect(settings.localStatus.value?.account).toBe('startup-account');
  });

  it('refreshes pristine credentials but preserves dirty drafts across external reloads', async () => {
    const loading = deferredResponse();
    const fetcher = vi.fn().mockResolvedValueOnce(json(status)).mockReturnValueOnce(loading.promise);
    const storage = memoryStorage();
    const settings = createDeveloperSettingsController({ defaults, fetcher: fetcher as typeof fetch,
      storage: () => storage, pageOrigin: 'http://localhost:4173', listenForStorage: false });
    await settings.initialize();
    await settings.saveConnection();
    settings.passwordDraft.value = 'unsaved-password';
    const pending = settings.reloadExternalChanges();
    loading.resolve(json({ ...status, authVersion: 1, account: 'external-account', password: 'external-password' }));
    await expect(pending).resolves.toMatchObject({ ok: true });
    expect(settings.accountDraft.value).toBe('external-account');
    expect(settings.passwordDraft.value).toBe('unsaved-password');
    expect(settings.localStatus.value?.password).toBe('external-password');
    await expect(settings.getToken()).resolves.toMatchObject({ ok: false, message: expect.stringContaining('尚未保存') });
  });
});
