import type { DebugHeaderInput } from './debugHeaders';
import type { DeveloperConnectionSettings, DeveloperSettingsDefaults } from './types/developerSettings';

const STORAGE_KEY = 'campusclaw.developer-settings.v1';
const SCHEMA_VERSION = 1;
const MAX_SCOPES = 100;
const MAX_HEADERS = 100;

interface StoredAuthIp {
  backendIdentity: string;
  authIp: string;
}

interface StoredHeaderScope {
  environmentKey: string;
  rows: DebugHeaderInput[];
}

export interface DeveloperSettingsRecord {
  schemaVersion: 1;
  revision: number;
  connection?: DeveloperConnectionSettings;
  authIps: StoredAuthIp[];
  headerScopes: StoredHeaderScope[];
}

export class DeveloperSettingsStorageError extends Error {
  constructor(readonly code: 'unavailable' | 'corrupt' | 'version' | 'conflict', message: string) {
    super(message);
  }
}

export class DeveloperSettingsRepository {
  readonly key = STORAGE_KEY;

  constructor(private readonly storage: () => Storage) {}

  read(): DeveloperSettingsRecord {
    let raw: string | null;
    try {
      raw = this.storage().getItem(this.key);
    } catch {
      throw new DeveloperSettingsStorageError('unavailable', '浏览器本地设置无法读取。');
    }
    if (raw === null) return emptyRecord();
    return decodeRecord(raw);
  }

  saveConnection(expectedRevision: number, connection: DeveloperConnectionSettings): DeveloperSettingsRecord {
    return this.update(expectedRevision, (record) => {
      record.connection = cloneConnection(connection);
    });
  }

  saveAuthIp(expectedRevision: number, backendIdentity: string, authIp: string): DeveloperSettingsRecord {
    return this.update(expectedRevision, (record) => {
      record.authIps = record.authIps.filter((item) => item.backendIdentity !== backendIdentity);
      record.authIps.push({ backendIdentity, authIp });
      record.authIps = record.authIps.slice(-MAX_SCOPES);
    });
  }

  saveHeaders(expectedRevision: number, environmentKey: string, rows: readonly DebugHeaderInput[]): DeveloperSettingsRecord {
    return this.update(expectedRevision, (record) => {
      record.headerScopes = record.headerScopes.filter((item) => item.environmentKey !== environmentKey);
      record.headerScopes.push({ environmentKey, rows: rows.map(cloneHeader) });
      record.headerScopes = record.headerScopes.slice(-MAX_SCOPES);
    });
  }

  reset(): DeveloperSettingsRecord {
    const value = emptyRecord();
    this.write(value);
    return value;
  }

  private update(
    expectedRevision: number,
    change: (record: DeveloperSettingsRecord) => void,
  ): DeveloperSettingsRecord {
    const current = this.read();
    if (current.revision !== expectedRevision) {
      throw new DeveloperSettingsStorageError('conflict', '设置已在另一个页面更新，请重新读取后再保存。');
    }
    change(current);
    current.revision += 1;
    this.write(current);
    return cloneRecord(current);
  }

  private write(value: DeveloperSettingsRecord): void {
    const raw = JSON.stringify(value);
    try {
      this.storage().setItem(this.key, raw);
      if (this.storage().getItem(this.key) !== raw) throw new Error('Storage did not retain the value');
    } catch {
      throw new DeveloperSettingsStorageError('unavailable', '浏览器本地设置无法保存。');
    }
  }
}

export function connectionFromRecord(
  record: DeveloperSettingsRecord,
  defaults: DeveloperSettingsDefaults,
): DeveloperConnectionSettings {
  return record.connection ? cloneConnection(record.connection) : { ...defaults, proxyVersion: 0, proxyKey: '' };
}

export function authIpFromRecord(record: DeveloperSettingsRecord, backendIdentity: string): string {
  return record.authIps.find((item) => item.backendIdentity === backendIdentity)?.authIp ?? '';
}

export function headersFromRecord(
  record: DeveloperSettingsRecord,
  environmentKey: string,
  fallback: () => DebugHeaderInput[],
): DebugHeaderInput[] {
  const saved = record.headerScopes.find((item) => item.environmentKey === environmentKey);
  return saved ? saved.rows.map(cloneHeader) : fallback();
}

export function developerEnvironmentKey(backendIdentity: string, authIp: string): string {
  return JSON.stringify([backendIdentity, authIp.trim()]);
}

function decodeRecord(raw: string): DeveloperSettingsRecord {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new DeveloperSettingsStorageError('corrupt', '浏览器中的开发设置已损坏。');
  }
  if (!isObject(value)) throw corrupt();
  if (value.schemaVersion !== SCHEMA_VERSION) {
    throw new DeveloperSettingsStorageError('version', '浏览器中的开发设置版本不受支持。');
  }
  if (!isRevision(value.revision) || !Array.isArray(value.authIps) || !Array.isArray(value.headerScopes)) throw corrupt();
  const connection = value.connection === undefined ? undefined : decodeConnection(value.connection);
  const authIps = value.authIps.map(decodeAuthIp);
  const headerScopes = value.headerScopes.map(decodeHeaderScope);
  if (authIps.length > MAX_SCOPES || headerScopes.length > MAX_SCOPES) throw corrupt();
  return { schemaVersion: 1, revision: value.revision, ...(connection ? { connection } : {}), authIps, headerScopes };
}

function decodeConnection(value: unknown): DeveloperConnectionSettings {
  if (!isObject(value)) throw corrupt();
  const fields = ['apiBase', 'backendUrl', 'callerId', 'agentId', 'agentName', 'agentDescription', 'agentCategory'] as const;
  if (fields.some((field) => typeof value[field] !== 'string' || value[field].length > 4096)
    || !isRevision(value.proxyVersion) || typeof value.proxyKey !== 'string' || value.proxyKey.length > 128) throw corrupt();
  return {
    apiBase: value.apiBase as string,
    backendUrl: value.backendUrl as string,
    callerId: value.callerId as string,
    agentId: value.agentId as string,
    agentName: value.agentName as string,
    agentDescription: value.agentDescription as string,
    agentCategory: value.agentCategory as string,
    proxyVersion: value.proxyVersion as number,
    proxyKey: value.proxyKey as string,
  };
}

function decodeAuthIp(value: unknown): StoredAuthIp {
  if (!isObject(value) || !shortText(value.backendIdentity) || typeof value.authIp !== 'string'
    || value.authIp.length > 128) throw corrupt();
  return { backendIdentity: value.backendIdentity, authIp: value.authIp };
}

function decodeHeaderScope(value: unknown): StoredHeaderScope {
  if (!isObject(value) || !shortText(value.environmentKey) || !Array.isArray(value.rows)
    || value.rows.length > MAX_HEADERS) throw corrupt();
  return { environmentKey: value.environmentKey, rows: value.rows.map(decodeHeader) };
}

function decodeHeader(value: unknown): DebugHeaderInput {
  if (!isObject(value) || typeof value.enabled !== 'boolean' || typeof value.key !== 'string'
    || typeof value.value !== 'string' || value.key.length > 512 || value.value.length > 16_384
    || (value.presetKey !== undefined && typeof value.presetKey !== 'string')) throw corrupt();
  return {
    enabled: value.enabled,
    key: value.key,
    value: value.value,
    ...(typeof value.presetKey === 'string' ? { presetKey: value.presetKey } : {}),
  };
}

function emptyRecord(): DeveloperSettingsRecord {
  return { schemaVersion: 1, revision: 0, authIps: [], headerScopes: [] };
}

function cloneRecord(value: DeveloperSettingsRecord): DeveloperSettingsRecord {
  return {
    schemaVersion: 1,
    revision: value.revision,
    ...(value.connection ? { connection: cloneConnection(value.connection) } : {}),
    authIps: value.authIps.map((item) => ({ ...item })),
    headerScopes: value.headerScopes.map((item) => ({
      environmentKey: item.environmentKey,
      rows: item.rows.map(cloneHeader),
    })),
  };
}

function cloneConnection(value: DeveloperConnectionSettings): DeveloperConnectionSettings {
  return { ...value };
}

function cloneHeader(value: DebugHeaderInput): DebugHeaderInput {
  return { ...value };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function shortText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 4096;
}

function corrupt(): DeveloperSettingsStorageError {
  return new DeveloperSettingsStorageError('corrupt', '浏览器中的开发设置结构无效。');
}
