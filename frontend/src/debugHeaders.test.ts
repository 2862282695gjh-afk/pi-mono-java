import { describe, expect, it } from 'vitest';
import { validateDebugHeaders } from './debugHeaders';

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
  });

  it('rejects values that the Fetch Headers implementation cannot represent', () => {
    const result = validateDebugHeaders([
      { enabled: true, key: 'X-Debug', value: 'zero\u0000byte' },
      { enabled: true, key: 'X-Label', value: '中文' },
    ]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual(['Header Value 格式无效', 'Header Value 格式无效']);
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
});
