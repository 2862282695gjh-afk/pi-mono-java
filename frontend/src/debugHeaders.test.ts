import { describe, expect, it } from 'vitest';
import { revealFirstDebugHeaderError, validateDebugHeaders } from './debugHeaders';

describe('debug header validation', () => {
  it('returns enabled complete rows without preset header names', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'Authorization', value: 'Bearer fixture-token' },
      { enabled: true, key: 'X-Trace-Id', value: 'trace-1' },
      { enabled: true, key: '', value: '' },
    ]);

    expect(result.valid).toBe(true);
    expect(result.enabledCount).toBe(2);
    expect(result.headers.get('authorization')).toBe('Bearer fixture-token');
    expect(result.headers.get('x-trace-id')).toBe('trace-1');
  });

  it('rejects duplicate names without considering case', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'X-Trace-Id', value: 'trace-1' },
      { enabled: true, key: 'x-trace-id', value: 'trace-2' },
    ]);

    expect(result.valid).toBe(false);
    expect(result.enabledCount).toBe(0);
    expect(result.errors).toEqual(['Header Key 不能重复', 'Header Key 不能重复']);
    expect(result.errorFields).toEqual(['key', 'key']);
  });

  it('rejects invalid names, line breaks, and Fetch-forbidden names', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'Bad Header', value: 'value' },
      { enabled: true, key: 'X-Trace-Id', value: 'line-1\nline-2' },
      { enabled: true, key: 'Content-Length', value: '10' },
      { enabled: true, key: 'Sec-Debug', value: 'value' },
    ]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual([
      'Header Key 格式无效',
      'Header Value 不能包含换行',
      '浏览器禁止设置这个 Header',
      '浏览器禁止设置这个 Header',
    ]);
    expect(result.errorFields).toEqual(['key', 'value', 'key', 'key']);
  });

  it('implements the Fetch forbidden request-header name and value rules', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'Set-Cookie', value: 'fixture=value' },
      { enabled: true, key: 'Permissions-Policy', value: 'geolocation=()' },
      { enabled: true, key: 'X-HTTP-Method', value: 'PATCH' },
      { enabled: true, key: 'x-http-method-override', value: '"TRACE", PATCH' },
      { enabled: true, key: 'X-Method-Override', value: 'PATCH, track' },
    ]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual([
      '浏览器禁止设置这个 Header',
      '',
      '',
      '',
      '浏览器禁止设置这个 Header Value',
    ]);
    expect(result.errorFields).toEqual(['key', null, null, null, 'value']);
    expect(result.headers.get('permissions-policy')).toBe('geolocation=()');
    expect(result.headers.get('x-http-method')).toBe('PATCH');
    expect(result.headers.get('x-http-method-override')).toBe('"TRACE", PATCH');
  });

  it('marks the missing half of an incomplete row', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: '', value: 'value' },
      { enabled: true, key: 'X-Debug', value: '' },
    ]);

    expect(result.errorFields).toEqual(['key', 'value']);
  });

  it('rejects values that the Fetch Headers implementation cannot represent', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'X-Debug', value: 'zero\u0000byte' },
      { enabled: true, key: 'X-Label', value: '中文' },
    ]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual(['Header Value 格式无效', 'Header Value 格式无效']);
    expect(result.errorFields).toEqual(['value', 'value']);
  });

  it('ignores disabled and trailing blank rows', () => {
    const result = validateDebugHeaders([
      { enabled: false, key: 'Bad Header', value: '' },
      { enabled: true, key: '', value: '' },
    ]);

    expect(result.valid).toBe(true);
    expect(result.enabledCount).toBe(0);
    expect([...result.headers]).toEqual([]);
  });

  it('opens a collapsed panel before focusing the precise invalid field', async () => {
    let selector = '';
    let focused = false;
    let openBeforeRender = false;
    const input = { focus: () => { focused = true; } };
    const row = {
      querySelector: (value: string) => {
        selector = value;
        return input;
      },
    };
    const root = {
      open: false,
      querySelectorAll: () => [row],
    } as unknown as HTMLDetailsElement;
    const result = validateDebugHeaders([{ enabled: true, key: 'X-Debug', value: '' }]);

    await revealFirstDebugHeaderError(root, result, async () => {
      openBeforeRender = root.open;
    });

    expect(openBeforeRender).toBe(true);
    expect(selector).toBe('.debug-header-value');
    expect(focused).toBe(true);
  });
});
