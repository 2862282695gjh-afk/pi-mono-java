import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { request } from 'node:https';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginAuthCenter } from './authCenter';
vi.mock('node:https', () => ({ request: vi.fn() }));
afterEach(() => { vi.useRealTimers(); vi.resetAllMocks(); });
function upstream(status = 200, cookies = ['access-token=synthetic-token; HttpOnly; Secure']) {
  const response = Object.assign(new PassThrough(), { statusCode: status, headers: { 'set-cookie': cookies } });
  const emitter = new EventEmitter();
  const req = Object.assign(emitter, { end: vi.fn(), destroy(error: Error) { emitter.emit('error', error); emitter.emit('close'); return emitter; } });
  vi.mocked(request).mockImplementation(((_options: unknown, callback: (value: unknown) => void) => {
    callback(response); return req;
  }) as unknown as typeof request);
  return { response, req };
}
const config = { account: 'synthetic-account', password: 'synthetic-password' };
describe('AuthCenter HTTPS client', () => {
  it('uses the fixed TLS target and configured JSON without forwarded headers', async () => {
    const { response, req } = upstream();
    const signal = new AbortController().signal;
    const result = loginAuthCenter('192.0.2.10', config, signal);
    response.end('body deliberately ignored');
    expect(await result).toBe('synthetic-token');
    req.emit('close');
    const options = vi.mocked(request).mock.calls[0][0];
    expect(options).toEqual({ hostname: '192.0.2.10', port: 443, method: 'POST', path: '/service/AuthCenter/0.1.0/auth/login',
      headers: { 'Content-Type': 'application/json', 'Content-Length': expect.any(Number), Accept: 'application/json' }, signal });
    expect(JSON.parse(req.end.mock.calls[0][0])).toEqual({ loginAccount: config.account, password: config.password });
  });
  it('rejects redirects instead of following them', async () => {
    const { response, req } = upstream(302);
    const result = loginAuthCenter('192.0.2.10', config, new AbortController().signal);
    response.end();
    await expect(result).rejects.toMatchObject({ code: 'AUTH_UPSTREAM_REJECTED' });
    req.emit('close');
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('enforces total timeout and response bounds and sanitizes TLS errors', async () => {
    vi.useFakeTimers();
    const first = upstream();
    const timeout = loginAuthCenter('192.0.2.10', config, new AbortController().signal);
    const assertion = expect(timeout).rejects.toMatchObject({ code: 'AUTH_UPSTREAM_TIMEOUT', status: 504 });
    await vi.advanceTimersByTimeAsync(10_000); await assertion;
    expect(first.req.end).toHaveBeenCalledTimes(1);
    const second = upstream();
    const oversized = loginAuthCenter('192.0.2.10', config, new AbortController().signal);
    second.response.write(Buffer.alloc(65_537));
    await expect(oversized).rejects.toMatchObject({ code: 'AUTH_UPSTREAM_UNAVAILABLE' });
    const third = upstream();
    const tls = loginAuthCenter('192.0.2.10', config, new AbortController().signal);
    third.req.destroy(new Error('TLS internal secret'));
    await expect(tls).rejects.toMatchObject({ message: '认证中心暂不可用，请检查网络与证书配置。' });
  });
});
