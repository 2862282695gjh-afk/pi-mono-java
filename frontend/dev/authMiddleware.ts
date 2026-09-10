import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin } from 'vite';
import { DevAuthError, authFailure, loginAuthCenter, parseAuthIp } from './authCenter';
import type { AuthConfig } from './authCenter';

const PATH = '/__debug/auth/token';
const loopback = (address?: string) => address === '::1' || address === '127.0.0.1' || address === '::ffff:127.0.0.1';
function checkOrigin(req: IncomingMessage): void {
  let valid = false;
  try {
    const origin = new URL(req.headers.origin ?? '');
    const host = new URL('http://' + req.headers.host);
    valid = loopback(req.socket.remoteAddress) && loopback(req.socket.localAddress)
      && ['localhost', '127.0.0.1', '[::1]'].includes(host.hostname)
      && Number(host.port || 80) === req.socket.localPort
      && origin.origin === host.origin && !origin.username && !origin.password;
  } catch { valid = false; }
  if (!valid) throw authFailure('DEV_AUTH_FORBIDDEN', 403, '认证助手仅允许本机页面访问。');
}
async function readBody(req: IncomingMessage): Promise<unknown> {
  if (req.method !== 'POST') throw authFailure('INVALID_AUTH_REQUEST', 405, '请使用获取 Token 操作。');
  if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') {
    throw authFailure('INVALID_AUTH_REQUEST', 415, '认证请求必须为 JSON。');
  }
  let body = '';
  for await (const chunk of req) {
    body += String(chunk);
    if (Buffer.byteLength(body) > 2048) throw authFailure('INVALID_AUTH_REQUEST', 413, '认证请求过大。');
  }
  try { return JSON.parse(body); }
  catch { throw authFailure('INVALID_AUTH_REQUEST', 400, '认证请求格式无效。'); }
}
export function createAuthMiddleware(config: AuthConfig, login = loginAuthCenter) {
  return async (req: IncomingMessage, res: ServerResponse, next: () => void): Promise<void> => {
    if (req.url?.split('?')[0] !== PATH) { next(); return; }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const controller = new AbortController();
    const close = () => controller.abort();
    res.once('close', close);
    try {
      checkOrigin(req);
      const ip = parseAuthIp(await readBody(req));
      if (!config.account || !config.password) throw authFailure('DEV_AUTH_NOT_CONFIGURED', 503, '请在本地开发服务配置认证账号和密码。');
      const accessToken = await login(ip, config, controller.signal);
      res.end(JSON.stringify({ resCode: '0', resMsg: 'Token 已获取', result: { accessToken } }));
    } catch (error) {
      const failure = error instanceof DevAuthError ? error : authFailure();
      res.statusCode = failure.status;
      res.end(JSON.stringify({ resCode: failure.code, resMsg: failure.message }));
    } finally { res.off('close', close); }
  };
}
export function devAuthPlugin(config: AuthConfig): Plugin {
  return { name: 'campusclaw-dev-auth', apply: 'serve',
    configureServer(server) { server.middlewares.use(createAuthMiddleware(config)); } };
}
