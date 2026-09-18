import { request } from 'node:https';
import { isIP } from 'node:net';

export class DevAuthError extends Error {
  constructor(readonly code: string, readonly status: number, message: string) { super(message); }
}
export interface AuthConfig { account: string; password: string }
export const authFailure = (code = 'AUTH_UPSTREAM_UNAVAILABLE', status = 502, message = '认证中心暂不可用，请检查网络与证书配置。') => new DevAuthError(code, status, message);

export function parseAuthIp(body: unknown): string {
  if (!body || typeof body !== 'object' || Array.isArray(body)
    || Object.keys(body).length !== 1 || !('ip' in body) || typeof body.ip !== 'string' || !isIP(body.ip)) {
    throw authFailure('INVALID_AUTH_IP', 400, '请输入有效的认证中心 IP。');
  }
  return body.ip;
}
export function extractAccessToken(cookies: string[]): string {
  const tokens = new Set<string>();
  for (const cookie of cookies) {
    const [pair, ...attributes] = cookie.split(';');
    const separator = pair.indexOf('=');
    if (pair.slice(0, separator).trim() !== 'access-token') continue;
    const value = pair.slice(separator + 1).trim();
    let maxAge: number | undefined;
    let expires: number | undefined;
    for (const attribute of attributes) {
      const [name, ...parts] = attribute.trim().split('=');
      const data = parts.join('=');
      if (name.toLowerCase() === 'max-age' && /^-?\d+$/u.test(data)) maxAge = Number(data);
      if (name.toLowerCase() === 'expires' && Number.isFinite(Date.parse(data))) expires = Date.parse(data);
    }
    const expired = maxAge !== undefined ? maxAge <= 0 : expires !== undefined && expires <= Date.now();
    if (!value || expired || /[^\x21-\x7E]|[,;"\\]/u.test(value)) throw authFailure('AUTH_TOKEN_MISSING', 502, '认证中心未返回有效 Token。');
    tokens.add(value);
  }
  if (tokens.size !== 1) throw authFailure('AUTH_TOKEN_MISSING', 502, '认证中心未返回唯一有效 Token。');
  return [...tokens][0];
}
export function loginAuthCenter(ip: string, config: AuthConfig, signal: AbortSignal): Promise<string> {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ loginAccount: config.account, password: config.password });
    const upstream = request({ hostname: ip, port: 443, method: 'POST', path: '/service/AuthCenter/0.1.0/auth/login',
      // 本地联调认证请求跳过证书校验，不修改进程级 TLS 配置。
      rejectUnauthorized: false,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), Accept: 'application/json' }, signal }, (response) => {
      let bytes = 0;
      response.on('data', (chunk: Buffer) => {
        bytes += chunk.length;
        if (bytes > 64 * 1024) upstream.destroy(authFailure());
      });
      response.on('error', () => reject(authFailure()));
      response.on('end', () => {
        if (!response.statusCode || response.statusCode < 200 || response.statusCode >= 300) {
          reject(authFailure('AUTH_UPSTREAM_REJECTED', 502, '认证中心未接受登录，请检查本地账号配置。')); return;
        }
        try { resolve(extractAccessToken(response.headers['set-cookie'] ?? [])); }
        catch (error) { reject(error); }
      });
    });
    const timer = setTimeout(() => upstream.destroy(authFailure('AUTH_UPSTREAM_TIMEOUT', 504, '认证中心响应超时，请稍后重新获取。')), 10_000);
    upstream.on('error', (error) => reject(error instanceof DevAuthError ? error : authFailure()));
    upstream.on('close', () => clearTimeout(timer));
    upstream.end(body);
  });
}
