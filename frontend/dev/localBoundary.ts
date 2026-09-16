import type { IncomingMessage } from 'node:http';
import { authFailure } from './authCenter';

export function assertLocalRequest(req: IncomingMessage): void {
  let valid = false;
  try {
    const host = new URL(`http://${req.headers.host ?? ''}`);
    const local = loopback(req.socket.remoteAddress)
      && loopback(req.socket.localAddress)
      && ['localhost', '127.0.0.1', '[::1]'].includes(host.hostname)
      && Number(host.port || 80) === req.socket.localPort;
    const origin = requestOrigin(req);
    valid = local && origin === host.origin;
  } catch {
    valid = false;
  }
  if (!valid) throw authFailure('DEV_AUTH_FORBIDDEN', 403, '本地开发服务仅允许本机页面访问。');
}

function requestOrigin(req: IncomingMessage): string {
  if (req.headers.origin) {
    const origin = new URL(req.headers.origin);
    if (origin.username || origin.password) return '';
    return origin.origin;
  }
  const localRead = req.method === 'GET' && req.headers['x-campusclaw-local-request'] === 'settings';
  if (!localRead) return '';
  if (req.headers['sec-fetch-site'] === 'same-origin') return new URL(`http://${req.headers.host ?? ''}`).origin;
  const referer = new URL(req.headers.referer ?? '');
  return referer.origin;
}

export async function readJsonBody(req: IncomingMessage, maximumBytes = 4096): Promise<unknown> {
  if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') {
    throw authFailure('INVALID_AUTH_REQUEST', 415, '请求必须为 JSON。');
  }
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk), 'utf8');
    bytes += buffer.length;
    if (bytes > maximumBytes) throw authFailure('INVALID_AUTH_REQUEST', 413, '请求过大。');
    chunks.push(buffer);
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } catch {
    throw authFailure('INVALID_AUTH_REQUEST', 400, '请求格式无效。');
  }
}

export function parseVersionHeader(req: IncomingMessage, name: string): number {
  const raw = req.headers[name];
  const text = Array.isArray(raw) ? '' : raw;
  if (typeof text !== 'string' || !/^(0|[1-9]\d*)$/u.test(text)) {
    throw authFailure('DEV_CONFIG_VERSION_REQUIRED', 409, '本地开发配置已变化，请重新读取。');
  }
  const value = Number(text);
  if (!Number.isSafeInteger(value)) throw authFailure('DEV_CONFIG_VERSION_REQUIRED', 409, '本地开发配置已变化，请重新读取。');
  return value;
}

function loopback(address?: string): boolean {
  return address === '::1' || address === '127.0.0.1' || address === '::ffff:127.0.0.1';
}
