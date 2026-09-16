import { chmod, mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
import type { AuthConfig } from './authCenter';
import type { LocalConfigPatch, LocalConfigStatus, LocalFieldChange } from '../src/types/developerSettings';

const SCHEMA_VERSION = 1;
const DEFAULT_BACKEND_URL = 'http://localhost:8080';

interface StoredLocalConfig {
  schemaVersion: 1;
  version: number;
  proxyVersion: number;
  authVersion: number;
  backendUrl?: string;
  account?: string;
  password?: string;
}

interface LocalConfigSnapshot {
  status: LocalConfigStatus;
  auth: AuthConfig;
}

export class LocalConfigError extends Error {
  constructor(
    readonly code: 'DEV_CONFIG_INVALID' | 'DEV_CONFIG_VERSION_MISMATCH' | 'DEV_CONFIG_SAVE_FAILED',
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export class LocalConfigRepository {
  constructor(
    private readonly filePath: string,
    private readonly startup: { backendUrl: string; account: string; password: string },
  ) {}

  async snapshot(): Promise<LocalConfigSnapshot> {
    return this.toSnapshot(await this.readStored());
  }

  async update(expectedVersion: number, patch: LocalConfigPatch): Promise<LocalConfigSnapshot> {
    const stored = await this.readStored();
    if (stored.version !== expectedVersion) {
      throw new LocalConfigError('DEV_CONFIG_VERSION_MISMATCH', 409, '本地开发配置已变化，请重新读取。');
    }
    const next = this.applyPatch(stored, patch);
    if (JSON.stringify(next) === JSON.stringify(stored)) return this.toSnapshot(stored);
    next.version += 1;
    if (backendChanged(stored, next)) next.proxyVersion += 1;
    if (authChanged(stored, next)) next.authVersion += 1;
    await this.writeStored(next);
    return this.toSnapshot(next);
  }

  private async readStored(): Promise<StoredLocalConfig> {
    let raw: string;
    try {
      raw = await readFile(this.filePath, 'utf8');
    } catch (error) {
      if (isMissing(error)) return emptyStored();
      throw new LocalConfigError('DEV_CONFIG_INVALID', 500, '本地开发配置无法读取。');
    }
    return decodeStored(raw);
  }

  private applyPatch(stored: StoredLocalConfig, patch: LocalConfigPatch): StoredLocalConfig {
    const next = { ...stored };
    if (patch.backendUrl) applyBackendChange(next, patch.backendUrl);
    if (patch.account) applySecretChange(next, 'account', patch.account);
    if (patch.password) applySecretChange(next, 'password', patch.password);
    return next;
  }

  private toSnapshot(stored: StoredLocalConfig): LocalConfigSnapshot {
    const backend = stored.backendUrl ?? cleanBackendUrl(this.startup.backendUrl || DEFAULT_BACKEND_URL);
    const account = stored.account ?? this.startup.account;
    const password = stored.password ?? this.startup.password;
    return {
      status: {
        version: stored.version,
        proxyVersion: stored.proxyVersion,
        proxyKey: proxyKey(backend),
        authVersion: stored.authVersion,
        authKey: '',
        backendUrl: backend,
        backendSource: stored.backendUrl === undefined
          ? (this.startup.backendUrl ? 'startup' : 'default')
          : 'saved',
        accountConfigured: Boolean(account),
        passwordConfigured: Boolean(password),
        accountSource: stored.account === undefined ? (this.startup.account ? 'startup' : 'cleared')
          : (stored.account ? 'saved' : 'cleared'),
        passwordSource: stored.password === undefined ? (this.startup.password ? 'startup' : 'cleared')
          : (stored.password ? 'saved' : 'cleared'),
      },
      auth: { account, password },
    };
  }

  private async writeStored(value: StoredLocalConfig): Promise<void> {
    const directory = dirname(this.filePath);
    const temporary = `${this.filePath}.${randomUUID()}.tmp`;
    try {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      await chmod(directory, 0o700);
      await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });
      await rename(temporary, this.filePath);
      await chmod(this.filePath, 0o600);
    } catch {
      await unlink(temporary).catch(() => undefined);
      throw new LocalConfigError('DEV_CONFIG_SAVE_FAILED', 500, '本地开发配置无法保存。');
    }
  }
}

export class DevConfigService {
  private queue: Promise<void> = Promise.resolve();
  private readonly authKey = randomUUID();

  constructor(private readonly repository: LocalConfigRepository) {}

  status(): Promise<LocalConfigStatus> {
    return this.repository.snapshot().then((value) => ({ ...value.status, authKey: this.authKey }));
  }

  snapshot(): Promise<LocalConfigSnapshot> {
    return this.repository.snapshot().then((value) => ({
      ...value,
      status: { ...value.status, authKey: this.authKey },
    }));
  }

  update(expectedVersion: number, patch: LocalConfigPatch): Promise<LocalConfigStatus> {
    const operation = this.queue.then(() => this.repository.update(expectedVersion, patch));
    this.queue = operation.then(() => undefined, () => undefined);
    return operation.then((value) => ({ ...value.status, authKey: this.authKey }));
  }
}

function applyBackendChange(value: StoredLocalConfig, change: LocalFieldChange): void {
  if (change.operation === 'keep') return;
  if (change.operation === 'restore-default') {
    delete value.backendUrl;
    return;
  }
  if (change.operation !== 'replace') throw invalidConfig();
  value.backendUrl = cleanBackendUrl(change.value);
}

function applySecretChange(
  value: StoredLocalConfig,
  field: 'account' | 'password',
  change: LocalFieldChange,
): void {
  if (change.operation === 'keep') return;
  if (change.operation === 'restore-default') {
    delete value[field];
    return;
  }
  if (change.operation === 'clear') {
    value[field] = '';
    return;
  }
  const secret = change.value;
  if (!secret || secret.length > 1024 || /\r|\n|\0/u.test(secret)) throw invalidConfig();
  value[field] = secret;
}

function decodeStored(raw: string): StoredLocalConfig {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw invalidConfig();
  }
  if (!isObject(value) || value.schemaVersion !== SCHEMA_VERSION || !revision(value.version)
    || !revision(value.proxyVersion) || !revision(value.authVersion)) throw invalidConfig();
  const stored: StoredLocalConfig = {
    schemaVersion: 1,
    version: value.version,
    proxyVersion: value.proxyVersion,
    authVersion: value.authVersion,
  };
  for (const field of ['backendUrl', 'account', 'password'] as const) {
    if (value[field] !== undefined && typeof value[field] !== 'string') throw invalidConfig();
    if (typeof value[field] === 'string') stored[field] = value[field];
  }
  if (stored.backendUrl !== undefined) stored.backendUrl = cleanBackendUrl(stored.backendUrl);
  if ((stored.account?.length ?? 0) > 1024 || (stored.password?.length ?? 0) > 1024) throw invalidConfig();
  return stored;
}

function cleanBackendUrl(value: string): string {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw invalidConfig();
  }
  const allowed = url.protocol === 'http:' || url.protocol === 'https:';
  if (!allowed || url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    throw invalidConfig();
  }
  return url.origin;
}

function proxyKey(backendUrl: string): string {
  return createHash('sha256').update(backendUrl, 'utf8').digest('hex');
}

function emptyStored(): StoredLocalConfig {
  return { schemaVersion: 1, version: 0, proxyVersion: 0, authVersion: 0 };
}

function backendChanged(left: StoredLocalConfig, right: StoredLocalConfig): boolean {
  return left.backendUrl !== right.backendUrl;
}

function authChanged(left: StoredLocalConfig, right: StoredLocalConfig): boolean {
  return left.account !== right.account || left.password !== right.password;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function revision(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isMissing(error: unknown): boolean {
  return isObject(error) && error.code === 'ENOENT';
}

function invalidConfig(): LocalConfigError {
  return new LocalConfigError('DEV_CONFIG_INVALID', 400, '本地开发配置格式无效。');
}
