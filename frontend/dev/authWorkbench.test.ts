import { createServer } from 'node:http';
import type { Server } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DevConfigService, LocalConfigRepository } from './localConfig';
import { createConfigMiddleware } from './configMiddleware';
import { createAuthMiddleware } from './authMiddleware';
import { createDeveloperSettingsController } from '../src/composables/useDeveloperSettings';
import { memoryStorage } from '../src/runtime/storageFixture';

const servers: Server[] = [];
const directories: string[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => {
    server.closeAllConnections();
    server.close(() => resolve());
  })));
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function setup() {
  const directory = await mkdtemp(join(tmpdir(), 'campusclaw-auth-workbench-'));
  directories.push(directory);
  const repository = new LocalConfigRepository(join(directory, 'config.json'), {
    backendUrl: 'http://backend.test', account: 'synthetic-account', password: 'synthetic-password',
  });
  const service = new DevConfigService(repository);
  const login = vi.fn().mockResolvedValue('synthetic-token');
  const configMiddleware = createConfigMiddleware(service);
  const authMiddleware = createAuthMiddleware(service, login);
  const server = createServer((req, res) => void configMiddleware(req, res,
    () => void authMiddleware(req, res, () => { res.statusCode = 404; res.end(); })));
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const storage = memoryStorage();
  const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    headers.set('Origin', origin);
    return fetch(new URL(String(input), origin), { ...init, headers });
  });
  const createSettings = () => createDeveloperSettingsController({
    defaults: { apiBase: '', backendUrl: 'http://backend.test', callerId: 'test', agentId: 'test',
      agentName: 'Test', agentDescription: '', agentCategory: '' },
    storage: () => storage, fetcher, pageOrigin: origin, listenForStorage: false,
  });
  const settings = createSettings();
  await settings.initialize();
  return { settings, createSettings, service, login, fetcher, storage };
}

describe('authentication workbench save and apply', () => {
  it('saves, echoes and uses credentials, then restores them after a page reload without treating them as dirty', async () => {
    const { settings, createSettings, login, fetcher, storage } = await setup();
    expect(settings.accountDraft.value).toBe('synthetic-account');
    expect(settings.passwordDraft.value).toBe('synthetic-password');
    settings.authIpDraft.value = '192.0.2.10';
    settings.accountDraft.value = 'saved-account';
    settings.passwordDraft.value = 'saved-password';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: true, message: '已保存' });
    expect(settings.accountDraft.value).toBe('saved-account');
    expect(settings.passwordDraft.value).toBe('saved-password');
    await expect(settings.getToken()).resolves.toMatchObject({ ok: true });
    expect(login).toHaveBeenCalledWith('192.0.2.10', { account: 'saved-account', password: 'saved-password' },
      expect.any(AbortSignal));
    expect(settings.headerRows.value.find((row) => row.key === 'access-token'))
      .toMatchObject({ value: 'synthetic-token', enabled: true, revealed: true });
    expect(storage.getItem('campusclaw.developer-settings.v1')).not.toMatch(/saved-account|saved-password/u);
    const tokenRequest = fetcher.mock.calls.find(([input]) => input === '/__debug/auth/token');
    expect(JSON.parse(String(tokenRequest?.[1]?.body))).toEqual({ ip: '192.0.2.10' });

    const reloaded = createSettings();
    await reloaded.initialize();
    expect(reloaded.accountDraft.value).toBe('saved-account');
    expect(reloaded.passwordDraft.value).toBe('saved-password');
    expect(reloaded.authIpDraft.value).toBe('192.0.2.10');
    await expect(reloaded.getToken()).resolves.toMatchObject({ ok: true });
    const putCount = () => fetcher.mock.calls.filter(([, init]) => init?.method === 'PUT').length;
    expect(putCount()).toBe(1);
    await expect(reloaded.saveAuthentication()).resolves.toMatchObject({ ok: true });
    expect(putCount()).toBe(1);
  });

  it('rejects only actual unsaved changes, and allows restoring an input to its saved value', async () => {
    const { settings, login } = await setup();
    settings.authIpDraft.value = '192.0.2.10';
    await settings.saveAuthentication();
    settings.passwordDraft.value = 'unsaved-password';
    await expect(settings.getToken()).resolves.toMatchObject({ ok: false, message: expect.stringContaining('尚未保存') });
    expect(login).not.toHaveBeenCalled();
    settings.passwordDraft.value = 'synthetic-password';
    await expect(settings.getToken()).resolves.toMatchObject({ ok: true });
    expect(login).toHaveBeenCalledOnce();
  });

  it('echoes cleared and restored credentials and allows editing just the password', async () => {
    const { settings, service, login } = await setup();
    settings.authIpDraft.value = '192.0.2.10';
    settings.passwordDraft.value = 'replacement-password';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: true });
    expect(await service.status()).toMatchObject({ account: 'synthetic-account', password: 'replacement-password' });
    await expect(settings.clearAuthentication()).resolves.toMatchObject({ ok: true });
    expect(settings.accountDraft.value).toBe('');
    expect(settings.passwordDraft.value).toBe('');
    await expect(settings.getToken()).resolves.toMatchObject({ ok: false, message: '请先配置认证账号和密码。' });
    expect(login).not.toHaveBeenCalled();
    await expect(settings.restoreAuthenticationDefaults()).resolves.toMatchObject({ ok: true });
    expect(settings.accountDraft.value).toBe('synthetic-account');
    expect(settings.passwordDraft.value).toBe('synthetic-password');
    await expect(settings.getToken()).resolves.toMatchObject({ ok: true });
    settings.accountDraft.value = '';
    settings.passwordDraft.value = '';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: true });
    expect(await service.status()).toMatchObject({ account: '', password: '' });
  });

  it('keeps unsaved input after a conflicting save and never logs in with an unconfirmed configuration', async () => {
    const { settings, service, login } = await setup();
    settings.authIpDraft.value = '192.0.2.10';
    await settings.saveAuthentication();
    await service.update(0, { password: { operation: 'replace', value: 'external-password' } });
    settings.passwordDraft.value = 'local-draft-password';
    await expect(settings.saveAuthentication()).resolves.toMatchObject({ ok: false });
    expect(settings.passwordDraft.value).toBe('local-draft-password');
    expect(await service.status()).toMatchObject({ password: 'external-password' });
    await expect(settings.getToken()).resolves.toMatchObject({ ok: false });
    expect(login).not.toHaveBeenCalled();
  });
});
