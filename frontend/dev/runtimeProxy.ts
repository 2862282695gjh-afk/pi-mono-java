import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import type { IncomingMessage, IncomingHttpHeaders, ServerResponse } from 'node:http';
import type { RequestOptions } from 'node:https';
import type { DevConfigService } from './localConfig';

const PROXY_PATH = '/campusclaw-service';
const VERSION_HEADER = 'x-campusclaw-proxy-version';
const KEY_HEADER = 'x-campusclaw-proxy-key';

export function createRuntimeProxyMiddleware(service: DevConfigService) {
  return async (req: IncomingMessage, res: ServerResponse, next: () => void): Promise<void> => {
    if (!req.url?.startsWith(PROXY_PATH)) {
      next();
      return;
    }
    try {
      const snapshot = await service.status();
      if (!matchesVersion(req, snapshot.proxyVersion, snapshot.proxyKey)) {
        sendProxyFailure(res, 409, 'DEV_PROXY_VERSION_MISMATCH', '连接配置已变化，请保存并应用后重试。');
        return;
      }
      forward(req, res, snapshot.backendUrl);
    } catch {
      sendProxyFailure(res, 502, 'DEV_PROXY_UNAVAILABLE', '本地代理暂时不可用。');
    }
  };
}

function forward(req: IncomingMessage, res: ServerResponse, backendUrl: string): void {
  const target = new URL(backendUrl);
  const headers: IncomingHttpHeaders = { ...req.headers, host: target.host };
  delete headers[VERSION_HEADER];
  delete headers[KEY_HEADER];
  const options: RequestOptions = {
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port || undefined,
    method: req.method,
    path: req.url,
    headers,
    rejectUnauthorized: false,
  };
  const request = target.protocol === 'https:' ? httpsRequest : httpRequest;
  const upstream = request(options, (response) => {
    res.writeHead(response.statusCode ?? 502, response.headers);
    response.pipe(res);
  });
  upstream.on('error', () => {
    if (!res.headersSent) sendProxyFailure(res, 502, 'DEV_PROXY_UNAVAILABLE', '本地代理暂时不可用。');
    else res.destroy();
  });
  res.once('close', () => upstream.destroy());
  req.pipe(upstream);
}

function matchesVersion(req: IncomingMessage, expected: number, expectedKey: string): boolean {
  const header = req.headers[VERSION_HEADER];
  const key = req.headers[KEY_HEADER];
  return typeof header === 'string' && header === String(expected) && key === expectedKey;
}

function sendProxyFailure(res: ServerResponse, status: number, code: string, message: string): void {
  if (res.headersSent) return;
  res.statusCode = status;
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ resCode: code, resMsg: message }));
}
