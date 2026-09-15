import { describe, expect, it } from 'vitest';
import { memoryStorage } from './runtime/storageFixture';
import {
  DeveloperSettingsRepository,
  DeveloperSettingsStorageError,
  authIpFromRecord,
  developerEnvironmentKey,
  headersFromRecord,
} from './developerSettingsStorage';

describe('developer settings browser storage', () => {
  it('isolates authentication IP by backend and headers by backend plus IP', () => {
    const storage = memoryStorage();
    const repository = new DeveloperSettingsRepository(() => storage);
    let record = repository.saveAuthIp(0, 'proxy:http://backend-a', '192.0.2.1');
    const scopeA = developerEnvironmentKey('proxy:http://backend-a', '192.0.2.1');
    record = repository.saveHeaders(record.revision, scopeA, [
      { enabled: true, key: 'access-token', value: 'token-a' },
    ]);
    record = repository.saveAuthIp(record.revision, 'proxy:http://backend-b', '192.0.2.2');
    const scopeB = developerEnvironmentKey('proxy:http://backend-b', '192.0.2.2');
    record = repository.saveHeaders(record.revision, scopeB, []);

    expect(authIpFromRecord(record, 'proxy:http://backend-a')).toBe('192.0.2.1');
    expect(authIpFromRecord(record, 'proxy:http://backend-b')).toBe('192.0.2.2');
    expect(headersFromRecord(record, scopeA, () => [])).toEqual([
      { enabled: true, key: 'access-token', value: 'token-a' },
    ]);
    expect(headersFromRecord(record, scopeB, () => [{ enabled: true, key: 'fallback', value: 'x' }])).toEqual([]);
  });

  it('rejects stale page writes instead of overwriting a newer revision', () => {
    const storage = memoryStorage();
    const first = new DeveloperSettingsRepository(() => storage);
    const second = new DeveloperSettingsRepository(() => storage);
    first.saveAuthIp(0, 'proxy:http://backend-a', '192.0.2.1');

    expect(() => second.saveHeaders(0, 'scope', [])).toThrowError(DeveloperSettingsStorageError);
    expect(() => second.saveHeaders(0, 'scope', [])).toThrow('另一个页面');
  });

  it('reports corruption and a storage write that was not retained', () => {
    const storage = memoryStorage();
    const repository = new DeveloperSettingsRepository(() => storage);
    storage.setItem(repository.key, '{bad json');
    expect(() => repository.read()).toThrow('已损坏');

    const clean = memoryStorage();
    const dropping = { ...clean, setItem: () => undefined } as Storage;
    const unavailable = new DeveloperSettingsRepository(() => dropping);
    expect(() => unavailable.saveAuthIp(0, 'backend', '')).toThrow('无法保存');
  });
});
