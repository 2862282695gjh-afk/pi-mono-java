import { mkdtemp, rm } from 'node:fs/promises';
import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createConfigMiddleware } from './configMiddleware';
import { DevConfigService, LocalConfigRepository } from './localConfig';

const temporaryDirectories: string[] = [];
const localPort = 4173;
const localOrigin = `http://localhost:${localPort}`;
const localReadMarker = { 'x-campusclaw-local-request': 'settings' };

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function setup() {
  const directory = await mkdtemp(join(tmpdir(), 'campusclaw-config-middleware-'));
  temporaryDirectories.push(directory);
  const repository = new LocalConfigRepository(join(directory, 'config.json'), {
    backendUrl: 'https://startup.example.test', account: 'secret-account', password: 'secret-password',
  });
  const service = new DevConfigService(repository);
  const middleware = createConfigMiddleware(service);
  return { service, middleware };
}

interface RequestOptions {
  method?: string;
  url?: string;
  headers?: IncomingHttpHeaders;
  body?: string;
}

function request(options: RequestOptions = {}): IncomingMessage {
  const stream = new PassThrough();
  Object.assign(stream, {
    method: options.method ?? 'GET',
    url: options.url ?? '/__debug/config',
    headers: { host: `localhost:${localPort}`, ...options.headers },
    socket: { remoteAddress: '127.0.0.1', localAddress: '127.0.0.1', localPort },
  });
  stream.end(options.body ?? '');
  return stream as unknown as IncomingMessage;
}

function response() {
  const headers = new Map<string, string>();
  const captured = { statusCode: 200, body: '', ended: false, headers };
  const serverResponse = {
    statusCode: 200,
    setHeader(name: string, value: string) {
      headers.set(name.toLowerCase(), String(value));
      return this;
    },
    end(value?: string) {
      captured.statusCode = serverResponse.statusCode;
      captured.body = value ?? '';
      captured.ended = true;
      return this;
    },
  } as unknown as ServerResponse;
  return { captured, serverResponse };
}

async function send(middleware: ReturnType<typeof createConfigMiddleware>, options: RequestOptions = {}) {
  const { captured, serverResponse } = response();
  const next = vi.fn();
  await middleware(request(options), serverResponse, next);
  return { ...captured, json: captured.body ? JSON.parse(captured.body) as Record<string, unknown> : undefined, next };
}

function put(body: unknown, version = 0, headers: IncomingHttpHeaders = {}): RequestOptions {
  return {
    method: 'PUT',
    headers: {
      origin: localOrigin,
      'content-type': 'application/json',
      'x-campusclaw-config-version': String(version),
      ...headers,
    },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  };
}

describe('development config middleware', () => {
  it('returns credentials only to local same-origin settings requests without caching them', async () => {
    const { middleware } = await setup();
    const result = await send(middleware, {
      headers: { ...localReadMarker, 'sec-fetch-site': 'same-origin' },
    });

    expect(result.statusCode).toBe(200);
    expect(result.headers.get('cache-control')).toBe('no-store');
    expect(result.json).toMatchObject({ resCode: '0', result: {
      version: 0, accountConfigured: true, passwordConfigured: true, accountSource: 'startup', passwordSource: 'startup',
    } });
    expect(result.json).toMatchObject({ result: { account: 'secret-account', password: 'secret-password' } });

    const refererResult = await send(middleware, {
      headers: { ...localReadMarker, referer: `${localOrigin}/settings` },
    });
    expect(refererResult.statusCode).toBe(200);
    for (const headers of [
      { ...localReadMarker, origin: 'https://other.test' },
      { ...localReadMarker, 'sec-fetch-site': 'cross-site' },
      { 'sec-fetch-site': 'same-origin' },
    ]) {
      const rejected = await send(middleware, { headers });
      expect(rejected.statusCode).toBe(403);
      expect(rejected.body).not.toMatch(/secret-account|secret-password/u);
    }
  });

  it('rejects unrelated paths, forged Host and Origin, cross-site GET, missing write Origin and unsupported methods', async () => {
    const { middleware } = await setup();
    const unrelated = await send(middleware, {
      url: '/__debug/config/other',
      headers: { ...localReadMarker, 'sec-fetch-site': 'same-origin' },
    });
    expect(unrelated.next).toHaveBeenCalledOnce();
    expect(unrelated.ended).toBe(false);

    expect((await send(middleware, {
      headers: { ...localReadMarker, host: 'other.test', origin: localOrigin },
    })).statusCode).toBe(403);
    expect((await send(middleware, {
      headers: { ...localReadMarker, origin: 'https://other.test' },
    })).statusCode).toBe(403);
    expect((await send(middleware, {
      headers: { ...localReadMarker, 'sec-fetch-site': 'cross-site' },
    })).statusCode).toBe(403);
    expect((await send(middleware, {
      headers: { 'sec-fetch-site': 'same-origin' },
    })).statusCode).toBe(403);
    expect((await send(middleware, put({}, 0, {
      ...localReadMarker,
      origin: undefined,
      referer: `${localOrigin}/settings`,
      'sec-fetch-site': 'same-origin',
    }))).statusCode).toBe(403);
    expect((await send(middleware, { method: 'DELETE', headers: { origin: localOrigin } })).statusCode).toBe(405);
  });

  it('rejects malformed, oversized and structurally malicious write bodies before changing configuration', async () => {
    const { service, middleware } = await setup();
    const invalidRequests: Array<{ request: RequestOptions; status: number; code: string }> = [
      { request: put('{', 0), status: 400, code: 'INVALID_AUTH_REQUEST' },
      { request: put([], 0), status: 400, code: 'INVALID_CONFIG_REQUEST' },
      { request: put({ unexpected: { operation: 'clear' } }, 0), status: 400, code: 'INVALID_CONFIG_REQUEST' },
      { request: put('{"__proto__":{"operation":"clear"}}', 0), status: 400, code: 'INVALID_CONFIG_REQUEST' },
      { request: put({ account: { operation: 'clear', value: 'smuggled' } }, 0),
        status: 400, code: 'INVALID_CONFIG_REQUEST' },
      { request: put({ password: { operation: 'replace' } }, 0), status: 400, code: 'INVALID_CONFIG_REQUEST' },
      { request: put({ password: { operation: 'replace', value: 'line\nbreak' } }, 0),
        status: 400, code: 'DEV_CONFIG_INVALID' },
      { request: put({ backendUrl: { operation: 'replace', value: 'file:///etc/passwd' } }, 0),
        status: 400, code: 'DEV_CONFIG_INVALID' },
      { request: put({ account: { operation: 'replace', value: 'x'.repeat(8_200) } }, 0),
        status: 413, code: 'INVALID_AUTH_REQUEST' },
      { request: { ...put({}, 0), headers: { origin: localOrigin, 'content-type': 'text/plain',
        'x-campusclaw-config-version': '0' } }, status: 415, code: 'INVALID_AUTH_REQUEST' },
    ];

    for (const invalid of invalidRequests) {
      const result = await send(middleware, invalid.request);
      expect(result).toMatchObject({ statusCode: invalid.status, json: { resCode: invalid.code } });
    }
    expect(await service.status()).toMatchObject({ version: 0, proxyVersion: 0, authVersion: 0 });
  });

  it('requires a current safe integer version and returns a conflict without replaying a stale write', async () => {
    const { service, middleware } = await setup();
    for (const version of [undefined, '-1', '01', '9007199254740992']) {
      const headers: IncomingHttpHeaders = { origin: localOrigin, 'content-type': 'application/json' };
      if (version !== undefined) headers['x-campusclaw-config-version'] = version;
      expect((await send(middleware, { method: 'PUT', headers, body: '{}' })).statusCode).toBe(409);
    }

    expect((await send(middleware, put({ backendUrl: { operation: 'replace', value: 'https://first.example.test' } }))).statusCode)
      .toBe(200);
    const stale = await send(middleware, put({ backendUrl: { operation: 'replace', value: 'https://stale.example.test' } }));
    expect(stale).toMatchObject({ statusCode: 409, json: { resCode: 'DEV_CONFIG_VERSION_MISMATCH' } });
    expect(await service.status()).toMatchObject({ version: 1, backendUrl: 'https://first.example.test' });
  });
});
