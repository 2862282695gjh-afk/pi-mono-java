import { createServer, request } from 'node:http';
import type { Server } from 'node:http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAuthMiddleware, devAuthPlugin } from './authMiddleware';
import { extractAccessToken, parseAuthIp } from './authCenter';
const servers: Server[] = [];
afterEach(async () => { await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => { server.closeAllConnections(); server.close(() => resolve()); }))); });
async function setup(config = { account: 'synthetic-account', password: 'synthetic-password' }) {
  const login = vi.fn().mockResolvedValue('synthetic-token');
  const middleware = createAuthMiddleware(config, login);
  const server = createServer((req, res) => void middleware(req, res, () => { res.statusCode = 404; res.end(); }));
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as { port: number }).port;
  const base = `http://127.0.0.1:${port}`;
  const send = (body: unknown = { ip: '192.0.2.10' }, headers: Record<string, string> = {}, method = 'POST') => fetch(base + '/__debug/auth/token', {
    method, headers: { Origin: base, 'Content-Type': 'application/json', ...headers }, ...(method === 'POST' ? { body: JSON.stringify(body) } : {}),
  });
  return { send, login, base };
}
describe('local development auth boundary', () => {
  it('returns only the extracted token, never a browser Cookie or local credentials', async () => {
    const { send, login } = await setup();
    const response = await send();
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(response.headers.has('set-cookie')).toBe(false);
    expect(await response.json()).toEqual({ resCode: '0', resMsg: 'Token 已获取', result: { accessToken: 'synthetic-token' } });
    expect(login).toHaveBeenCalledWith('192.0.2.10', { account: 'synthetic-account', password: 'synthetic-password' }, expect.any(AbortSignal));
    expect(devAuthPlugin({ account: '', password: '' })).not.toHaveProperty('configurePreviewServer');
  });
  it('rejects cross-origin, forged Host, methods and non-JSON before calling upstream', async () => {
    const { send, login, base } = await setup();
    expect((await send(undefined, { Origin: 'https://other.test' })).status).toBe(403);
    const forged = await new Promise<number>((resolve) => {
      const req = request(base + '/__debug/auth/token', { method: 'POST', headers: { Host: 'other.test', Origin: base, 'Content-Type': 'application/json' } }, (res) => { res.resume(); resolve(res.statusCode!); });
      req.end(JSON.stringify({ ip: '192.0.2.10' }));
    });
    expect(forged).toBe(403);
    expect((await send(undefined, {}, 'GET')).status).toBe(405);
    expect((await send(undefined, { 'Content-Type': 'text/plain' })).status).toBe(415);
    expect(login).not.toHaveBeenCalled();
  });
  it('rejects missing config, hostnames, URLs, redirects supplied as input and unexpected fields', async () => {
    const { send, login } = await setup({ account: '', password: '' });
    const missing = await send();
    expect(missing.status).toBe(503);
    expect(await missing.json()).toEqual({ resCode: 'DEV_AUTH_NOT_CONFIGURED', resMsg: expect.any(String) });
    for (const value of [{ ip: 'https://192.0.2.10' }, { ip: 'example.test' }, { ip: '192.0.2.10:443' }, { ip: '192.0.2.10', password: 'x' }]) {
      expect((await send(value)).status).toBe(400);
    }
    expect(login).not.toHaveBeenCalled();
    expect(parseAuthIp({ ip: '2001:db8::1' })).toBe('2001:db8::1');
  });
  it('redacts unknown upstream errors without returning result or error details', async () => {
    const { send, login } = await setup();
    login.mockRejectedValueOnce(new Error('synthetic-password token=secret-cookie'));
    const response = await send();
    expect(response.status).toBe(502);
    expect(await response.text()).not.toMatch(/synthetic-password|secret-cookie|result/);
  });
  it('extracts independent Set-Cookie entries and rejects deletion, missing or conflicting values', () => {
    expect(extractAccessToken(['JSESSIONID=ignored; HttpOnly', 'access-token=synthetic-token; Expires=Wed, 01 Jan 2042 00:00:00 GMT; HttpOnly; Secure'])).toBe('synthetic-token');
    expect(extractAccessToken(['access-token=x; Max-Age=60; Expires=Thu, 01 Jan 1970 00:00:00 GMT'])).toBe('x');
    for (const cookies of [[], ['access-token='], ['access-token=x; Max-Age=0'], ['access-token=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT'], ['access-token=x', 'access-token=y']]) {
      expect(() => extractAccessToken(cookies)).toThrowError(expect.objectContaining({ code: 'AUTH_TOKEN_MISSING' }));
    }
  });
});
