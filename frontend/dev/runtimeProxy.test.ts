import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer, type Server, type RequestListener } from 'node:http';
import { afterEach, describe, expect, it } from 'vitest';
import { DevConfigService, LocalConfigRepository } from './localConfig';
import { createRuntimeProxyMiddleware } from './runtimeProxy';

const servers: Server[] = [];
const directories: string[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => {
    server.closeAllConnections(); server.close(() => resolve());
  })));
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});
async function listen(handler: RequestListener): Promise<string> {
  const server = createServer(handler);
  servers.push(server);
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`;
}
async function setup(backendUrl: string) {
  const directory = await mkdtemp(join(tmpdir(), 'campusclaw-proxy-test-'));
  directories.push(directory);
  const service = new DevConfigService(new LocalConfigRepository(join(directory, 'settings.json'), {
    backendUrl, account: '', password: '',
  }));
  const middleware = createRuntimeProxyMiddleware(service);
  const base = await listen((req, res) => void middleware(req, res, () => { res.statusCode = 404; res.end(); }));
  const headers = async () => {
    const status = await service.status();
    return { 'X-CampusClaw-Proxy-Version': String(status.proxyVersion), 'X-CampusClaw-Proxy-Key': status.proxyKey };
  };
  return { base, service, headers, directory };
}

describe('development proxy request snapshots', () => {
  it('requires both version and actual target identity, strips guards, and preserves the request', async () => {
    const received: unknown[] = [];
    const target = await listen(async (req, res) => {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      received.push({ url: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString() });
      res.end('accepted');
    });
    const { base, headers, directory } = await setup(target);
    const path = '/campusclaw-service/v1/sessions/test/events?offset=1';
    const valid = await headers();
    for (const guard of [{}, { ...valid, 'X-CampusClaw-Proxy-Version': '2' }, { ...valid, 'X-CampusClaw-Proxy-Key': 'other-target' }]) {
      const rejected = await fetch(base + path, { headers: guard });
      expect(rejected.status).toBe(409);
      expect((await rejected.json()).resCode).toBe('DEV_PROXY_VERSION_MISMATCH');
    }
    expect(received).toHaveLength(0);
    const response = await fetch(base + path, { method: 'POST', headers: { ...valid, Authorization: 'synthetic-test' }, body: 'payload' });
    expect(await response.text()).toBe('accepted');
    expect(received).toEqual([expect.objectContaining({ url: path, method: 'POST', body: 'payload', headers: expect.objectContaining({ authorization: 'synthetic-test' }) })]);
    const receivedHeaders = (received[0] as { headers: Record<string, unknown> }).headers;
    expect(receivedHeaders).not.toHaveProperty('x-campusclaw-proxy-version');
    expect(receivedHeaders).not.toHaveProperty('x-campusclaw-proxy-key');
    // A fresh process using a different startup backend still has version zero.
    const restarted = new DevConfigService(new LocalConfigRepository(join(directory, 'settings.json'), {
      backendUrl: 'http://127.0.0.1:9', account: '', password: '',
    }));
    expect((await restarted.status()).proxyVersion).toBe(0);
    expect((await restarted.status()).proxyKey).not.toBe(valid['X-CampusClaw-Proxy-Key']);
  });

  it('keeps an in-flight response on its original upstream after a saved backend switch', async () => {
    let release: (() => void) | undefined;
    const first = await listen((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write('first:');
      release = () => res.end('original');
    });
    let secondRequests = 0;
    const second = await listen((_req, res) => { secondRequests++; res.end('second'); });
    const { base, headers, service } = await setup(first);
    const initialHeaders = await headers();
    const inFlight = await fetch(base + '/campusclaw-service/v1/events', { headers: initialHeaders });
    await service.update(0, { backendUrl: { operation: 'replace', value: second } });
    release?.();
    expect(await inFlight.text()).toBe('first:original');
    expect((await fetch(base + '/campusclaw-service/v1/events', { headers: initialHeaders })).status).toBe(409);
    expect(secondRequests).toBe(0);
    expect(await (await fetch(base + '/campusclaw-service/v1/events', { headers: await headers() })).text()).toBe('second');
    expect(secondRequests).toBe(1);
  });
});
