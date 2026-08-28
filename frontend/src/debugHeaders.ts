export interface DebugHeaderInput {
  enabled: boolean;
  key: string;
  value: string;
}

export interface DebugHeaderValidation {
  errors: string[];
  headers: Headers;
  enabledCount: number;
  valid: boolean;
}

export interface DebugHeadersExpose {
  snapshot: () => Promise<Headers | null>;
}

const HEADER_NAME_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/u;
const FORBIDDEN_HEADER_NAMES = new Set([
  'accept-charset',
  'accept-encoding',
  'access-control-request-headers',
  'access-control-request-method',
  'connection',
  'content-length',
  'cookie',
  'cookie2',
  'date',
  'dnt',
  'expect',
  'host',
  'keep-alive',
  'origin',
  'permissions-policy',
  'referer',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'via',
  'x-http-method',
  'x-http-method-override',
  'x-method-override',
]);

export function validateDebugHeaders(rows: readonly DebugHeaderInput[]): DebugHeaderValidation {
  const errors = rows.map(() => '');
  const candidates: Array<{ index: number; key: string; normalized: string; value: string }> = [];
  const nameCounts = new Map<string, number>();

  rows.forEach((row, index) => {
    if (!row.enabled) return;
    const key = row.key.trim();
    if (key === '' && row.value === '') return;
    const error = validateHeaderValue(key, row.value);
    if (error) {
      errors[index] = error;
      return;
    }
    const normalized = key.toLowerCase();
    candidates.push({ index, key, normalized, value: row.value });
    nameCounts.set(normalized, (nameCounts.get(normalized) ?? 0) + 1);
  });

  candidates.forEach(({ index, normalized }) => {
    if ((nameCounts.get(normalized) ?? 0) > 1) errors[index] = 'Header Key 不能重复';
  });

  const headers = new Headers();
  let enabledCount = 0;
  candidates.forEach(({ index, key, value }) => {
    if (errors[index]) return;
    headers.set(key, value);
    enabledCount += 1;
  });
  return { errors, headers, enabledCount, valid: errors.every((error) => error === '') };
}

function validateHeaderValue(key: string, value: string): string {
  if (key === '' || value === '') return 'Key 和 Value 需要同时填写';
  if (!HEADER_NAME_PATTERN.test(key)) return 'Header Key 格式无效';
  const normalized = key.toLowerCase();
  if (isForbiddenHeaderName(normalized)) return '浏览器禁止设置这个 Header';
  if (/\r|\n/u.test(value)) return 'Header Value 不能包含换行';
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/u.test(value)
      || /[^\u0000-\u00FF]/u.test(value)) {
    return 'Header Value 格式无效';
  }
  return '';
}

function isForbiddenHeaderName(name: string): boolean {
  return FORBIDDEN_HEADER_NAMES.has(name)
    || name.startsWith('proxy-')
    || name.startsWith('sec-');
}
