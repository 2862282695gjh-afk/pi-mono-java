import { mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { DevConfigService, LocalConfigRepository } from './localConfig';

const temporaryDirectories: string[] = [];
const startup = {
  backendUrl: 'https://startup.example.test',
  account: 'startup-account',
  password: 'startup-password',
};

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function setup() {
  const directory = await mkdtemp(join(tmpdir(), 'campusclaw-local-config-'));
  temporaryDirectories.push(directory);
  const configDirectory = join(directory, 'private');
  const filePath = join(configDirectory, 'config.json');
  const repository = new LocalConfigRepository(filePath, startup);
  return { directory: configDirectory, filePath, repository, service: new DevConfigService(repository) };
}

describe('local development config repository', () => {
  it('never returns secrets in public status and distinguishes clear from restoring startup defaults', async () => {
    const { service } = await setup();
    const initial = await service.status();
    expect(initial).toMatchObject({ version: 0, authVersion: 0, accountConfigured: true, passwordConfigured: true,
      accountSource: 'startup', passwordSource: 'startup' });
    expect(JSON.stringify(initial)).not.toMatch(/startup-account|startup-password|"account"\s*:|"password"\s*:/u);

    const cleared = await service.update(0, {
      account: { operation: 'replace', value: 'saved-account' },
      password: { operation: 'clear' },
    });
    expect(cleared).toMatchObject({ version: 1, authVersion: 1, accountConfigured: true, passwordConfigured: false,
      accountSource: 'saved', passwordSource: 'cleared' });
    expect((await service.snapshot()).auth).toEqual({ account: 'saved-account', password: '' });
    expect(JSON.stringify(cleared)).not.toContain('saved-account');

    const restored = await service.update(1, {
      account: { operation: 'restore-default' },
      password: { operation: 'restore-default' },
    });
    expect(restored).toMatchObject({ version: 2, authVersion: 2, accountConfigured: true, passwordConfigured: true,
      accountSource: 'startup', passwordSource: 'startup' });
    expect((await service.snapshot()).auth).toEqual({ account: 'startup-account', password: 'startup-password' });
  });

  it('atomically replaces a private file and keeps its directory and file permissions restrictive', async () => {
    const { directory, filePath, service } = await setup();
    await service.update(0, {
      backendUrl: { operation: 'replace', value: 'https://saved.example.test' },
      account: { operation: 'replace', value: 'saved-account' },
    });

    expect((await stat(directory)).mode & 0o777).toBe(0o700);
    expect((await stat(filePath)).mode & 0o777).toBe(0o600);
    expect(await readdir(directory)).toEqual(['config.json']);
    expect(JSON.parse(await readFile(filePath, 'utf8'))).toMatchObject({
      schemaVersion: 1,
      version: 1,
      proxyVersion: 1,
      authVersion: 1,
      backendUrl: 'https://saved.example.test',
      account: 'saved-account',
    });
  });

  it('serializes competing updates and rejects a stale version without overwriting the accepted change', async () => {
    const { service } = await setup();
    const first = service.update(0, { backendUrl: { operation: 'replace', value: 'https://first.example.test' } });
    const stale = service.update(0, { backendUrl: { operation: 'replace', value: 'https://stale.example.test' } });

    await expect(first).resolves.toMatchObject({ version: 1, proxyVersion: 1, backendUrl: 'https://first.example.test' });
    await expect(stale).rejects.toMatchObject({ code: 'DEV_CONFIG_VERSION_MISMATCH', status: 409 });
    expect(await service.status()).toMatchObject({ version: 1, proxyVersion: 1, backendUrl: 'https://first.example.test' });
  });
});
