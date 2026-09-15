import { describe, expect, it } from 'vitest';
import { validateSessionName } from './sessionName';

describe('session display name validation', () => {
  it('uses Java strip, UTF-8 bytes, and rejects control characters', () => {
    expect(validateSessionName('\u2000 名称 \u2000')).toEqual({ value: '名称' });
    expect(validateSessionName('\u00a0')).toEqual({ value: '\u00a0' });
    expect(validateSessionName('')).toHaveProperty('error');
    expect(validateSessionName('a\u202Eb')).toHaveProperty('error');
    expect(validateSessionName('中'.repeat(27))).toHaveProperty('error');
    expect(validateSessionName('中'.repeat(26))).toEqual({ value: '中'.repeat(26) });
  });
});
