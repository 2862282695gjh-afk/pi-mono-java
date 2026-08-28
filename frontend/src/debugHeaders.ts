export interface DebugHeaderInput {
  enabled: boolean;
  key: string;
  value: string;
}

export type DebugHeaderField = 'key' | 'value';

export interface DebugHeaderValidation {
  errors: string[];
  errorFields: Array<DebugHeaderField | null>;
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
  'referer',
  'set-cookie',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'via',
]);
const FORBIDDEN_METHOD_HEADER_NAMES = new Set([
  'x-http-method',
  'x-http-method-override',
  'x-method-override',
]);
const FORBIDDEN_METHODS = new Set(['CONNECT', 'TRACE', 'TRACK']);

interface HeaderValidationError {
  field: DebugHeaderField;
  message: string;
}

export function validateDebugHeaders(rows: readonly DebugHeaderInput[]): DebugHeaderValidation {
  const errors = rows.map(() => '');
  const errorFields: Array<DebugHeaderField | null> = rows.map(() => null);
  const candidates: Array<{ index: number; key: string; normalized: string; value: string }> = [];
  const nameCounts = new Map<string, number>();

  rows.forEach((row, index) => {
    if (!row.enabled) return;
    const key = row.key.trim();
    if (key === '' && row.value === '') return;
    const error = validateHeaderValue(key, row.value);
    if (error) {
      errors[index] = error.message;
      errorFields[index] = error.field;
      return;
    }
    const normalized = key.toLowerCase();
    candidates.push({ index, key, normalized, value: row.value });
    nameCounts.set(normalized, (nameCounts.get(normalized) ?? 0) + 1);
  });

  candidates.forEach(({ index, normalized }) => {
    if ((nameCounts.get(normalized) ?? 0) > 1) {
      errors[index] = 'Header Key 不能重复';
      errorFields[index] = 'key';
    }
  });

  const headers = new Headers();
  let enabledCount = 0;
  candidates.forEach(({ index, key, value }) => {
    if (errors[index]) return;
    headers.set(key, value);
    enabledCount += 1;
  });
  return { errors, errorFields, headers, enabledCount, valid: errors.every((error) => error === '') };
}

export async function revealFirstDebugHeaderError(
  root: HTMLDetailsElement | null,
  result: DebugHeaderValidation,
  afterOpen: () => Promise<void>,
): Promise<void> {
  if (!root) return;
  root.open = true;
  await afterOpen();
  const index = result.errors.findIndex((error) => error !== '');
  const field = result.errorFields[index];
  if (index < 0 || !field) return;
  const row = root.querySelectorAll<HTMLElement>('[data-debug-header-row]')[index];
  row?.querySelector<HTMLInputElement>(`.debug-header-${field}`)?.focus();
}

function validateHeaderValue(key: string, value: string): HeaderValidationError | null {
  if (key === '') return { field: 'key', message: 'Key 和 Value 需要同时填写' };
  if (value === '') return { field: 'value', message: 'Key 和 Value 需要同时填写' };
  if (!HEADER_NAME_PATTERN.test(key)) return { field: 'key', message: 'Header Key 格式无效' };
  const normalized = key.toLowerCase();
  if (isForbiddenHeaderName(normalized)) {
    return { field: 'key', message: '浏览器禁止设置这个 Header' };
  }
  if (/\r|\n/u.test(value)) return { field: 'value', message: 'Header Value 不能包含换行' };
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/u.test(value)
      || /[^\u0000-\u00FF]/u.test(value)) {
    return { field: 'value', message: 'Header Value 格式无效' };
  }
  if (hasForbiddenMethodOverride(normalized, value)) {
    return { field: 'value', message: '浏览器禁止设置这个 Header Value' };
  }
  return null;
}

function isForbiddenHeaderName(name: string): boolean {
  return FORBIDDEN_HEADER_NAMES.has(name)
    || name.startsWith('proxy-')
    || name.startsWith('sec-');
}

function hasForbiddenMethodOverride(name: string, value: string): boolean {
  if (!FORBIDDEN_METHOD_HEADER_NAMES.has(name)) return false;
  return splitHeaderValues(value).some((item) => FORBIDDEN_METHODS.has(item.toUpperCase()));
}

function splitHeaderValues(value: string): string[] {
  const values: string[] = [];
  let start = 0;
  let quoted = false;
  let escaped = false;
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (quoted && escaped) {
      escaped = false;
    } else if (quoted && character === '\\') {
      escaped = true;
    } else if (character === '"') {
      quoted = !quoted;
    } else if (!quoted && character === ',') {
      values.push(trimHttpWhitespace(value.slice(start, index)));
      start = index + 1;
    }
  }
  values.push(trimHttpWhitespace(value.slice(start)));
  return values;
}

function trimHttpWhitespace(value: string): string {
  return value.replace(/^[\t ]+|[\t ]+$/gu, '');
}
