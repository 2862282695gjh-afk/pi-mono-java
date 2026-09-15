import type { IncomingMessage, ServerResponse } from 'node:http';
import { DevAuthError, authFailure } from './authCenter';
import { LocalConfigError } from './localConfig';
import type { DevConfigService } from './localConfig';
import { assertLocalRequest, parseVersionHeader, readJsonBody } from './localBoundary';
import type { LocalConfigPatch, LocalFieldChange } from '../src/types/developerSettings';

const CONFIG_PATH = '/__debug/config';

export function createConfigMiddleware(service: DevConfigService) {
  return async (req: IncomingMessage, res: ServerResponse, next: () => void): Promise<void> => {
    if (req.url?.split('?')[0] !== CONFIG_PATH) {
      next();
      return;
    }
    configureResponse(res);
    try {
      assertLocalRequest(req);
      if (req.method === 'GET') {
        sendSuccess(res, await service.status());
        return;
      }
      if (req.method !== 'PUT') throw authFailure('INVALID_CONFIG_REQUEST', 405, '请使用读取或保存配置操作。');
      const version = parseVersionHeader(req, 'x-campusclaw-config-version');
      const patch = decodePatch(await readJsonBody(req, 8192));
      sendSuccess(res, await service.update(version, patch));
    } catch (error) {
      sendFailure(res, error);
    }
  };
}

function configureResponse(res: ServerResponse): void {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
}

function sendSuccess(res: ServerResponse, result: unknown): void {
  res.end(JSON.stringify({ resCode: '0', resMsg: '配置已更新', result }));
}

function sendFailure(res: ServerResponse, error: unknown): void {
  const failure = normalizeError(error);
  res.statusCode = failure.status;
  res.end(JSON.stringify({ resCode: failure.code, resMsg: failure.message }));
}

function normalizeError(error: unknown): { code: string; status: number; message: string } {
  if (error instanceof DevAuthError || error instanceof LocalConfigError) return error;
  return { code: 'DEV_CONFIG_SAVE_FAILED', status: 500, message: '本地开发配置无法保存。' };
}

function decodePatch(value: unknown): LocalConfigPatch {
  if (!isObject(value)) throw invalidRequest();
  const allowed = new Set(['backendUrl', 'account', 'password']);
  if (Object.keys(value).some((key) => !allowed.has(key))) throw invalidRequest();
  const patch: LocalConfigPatch = {};
  for (const field of allowed) {
    const change = value[field];
    if (change !== undefined) patch[field as keyof LocalConfigPatch] = decodeChange(change);
  }
  return patch;
}

function decodeChange(value: unknown): LocalFieldChange {
  if (!isObject(value) || typeof value.operation !== 'string') throw invalidRequest();
  if (value.operation === 'keep' || value.operation === 'clear' || value.operation === 'restore-default') {
    if (Object.keys(value).length !== 1) throw invalidRequest();
    return { operation: value.operation };
  }
  if (value.operation !== 'replace' || Object.keys(value).length !== 2 || typeof value.value !== 'string') {
    throw invalidRequest();
  }
  return { operation: 'replace', value: value.value };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function invalidRequest(): DevAuthError {
  return authFailure('INVALID_CONFIG_REQUEST', 400, '本地开发配置请求格式无效。');
}
