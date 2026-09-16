import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin } from 'vite';
import { DevAuthError, authFailure, loginAuthCenter, parseAuthIp } from './authCenter';
import type { AuthConfig } from './authCenter';
import { createConfigMiddleware } from './configMiddleware';
import type { DevConfigService } from './localConfig';
import { assertLocalRequest, parseVersionHeader, readJsonBody } from './localBoundary';
import { createRuntimeProxyMiddleware } from './runtimeProxy';

const PATH = '/__debug/auth/token';
async function readBody(req: IncomingMessage): Promise<unknown> {
  if (req.method !== 'POST') throw authFailure('INVALID_AUTH_REQUEST', 405, '请使用获取 Token 操作。');
  return readJsonBody(req, 2048);
}
export function createAuthMiddleware(config: AuthConfig | DevConfigService, login = loginAuthCenter) {
  return async (req: IncomingMessage, res: ServerResponse, next: () => void): Promise<void> => {
    if (req.url?.split('?')[0] !== PATH) { next(); return; }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const controller = new AbortController();
    const close = () => controller.abort();
    res.once('close', close);
    try {
      assertLocalRequest(req);
      const ip = parseAuthIp(await readBody(req));
      const auth = await authSnapshot(req, config);
      if (!auth.account || !auth.password) throw authFailure('DEV_AUTH_NOT_CONFIGURED', 503, '请在本地开发服务配置认证账号和密码。');
      const accessToken = await login(ip, auth, controller.signal);
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

export function devServicesPlugin(service: DevConfigService): Plugin {
  return {
    name: 'campusclaw-dev-services',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use(createConfigMiddleware(service));
      server.middlewares.use(createAuthMiddleware(service));
      server.middlewares.use(createRuntimeProxyMiddleware(service));
    },
  };
}

async function authSnapshot(req: IncomingMessage, source: AuthConfig | DevConfigService): Promise<AuthConfig> {
  if (!('snapshot' in source)) return source;
  const expected = parseVersionHeader(req, 'x-campusclaw-auth-version');
  const snapshot = await source.snapshot();
  const key = req.headers['x-campusclaw-auth-key'];
  if (snapshot.status.authVersion !== expected || key !== snapshot.status.authKey) {
    throw authFailure('DEV_AUTH_CONFIG_CHANGED', 409, '认证配置已变化，请重新读取后获取 Token。');
  }
  return snapshot.auth;
}
