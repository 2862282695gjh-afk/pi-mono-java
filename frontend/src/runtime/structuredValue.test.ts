import { describe, expect, it } from 'vitest';
import {
  analyzeStructuredText,
  STRUCTURED_VALUE_MAX_DEPTH,
  STRUCTURED_VALUE_MAX_NODES,
  STRUCTURED_VALUE_MAX_SOURCE_LENGTH,
} from './structuredValue';

describe('structured value analysis', () => {
  it('preserves the original JSON text and number lexemes in a read-only tree', () => {
    const source = '{"id":9007199254740993,"ratio":1.2300e+4,"items":[true,null,"{\\"nested\\":true}"]}';
    const analysis = analyzeStructuredText(source);

    expect(analysis.raw).toBe(source);
    expect(analysis.tooLarge).toBe(false);
    expect(analysis.format).toBe('json');
    expect(analysis.node).toMatchObject({
      kind: 'object',
      entries: [
        { key: 'id', value: { kind: 'number', raw: '9007199254740993' } },
        { key: 'ratio', value: { kind: 'number', raw: '1.2300e+4' } },
        { key: 'items', value: { kind: 'array' } },
      ],
    });
  });

  it('uses plain text for scalars, incomplete JSON and JSON encoded inside a string', () => {
    for (const source of ['42', '"{\\"key\\":1}"', '{"key":', 'normal text']) {
      expect(analyzeStructuredText(source)).toEqual({ raw: source, tooLarge: false, format: 'text' });
    }
  });

  it('does not accept JavaScript-only whitespace around JSON', () => {
    for (const source of ['\u00a0{"key":1}\u00a0', '\ufeff[1]']) {
      expect(analyzeStructuredText(source)).toEqual({ raw: source, tooLarge: false, format: 'text' });
    }
    expect(analyzeStructuredText(' \t\r\n{"key":1}\n').format).toBe('json');
  });

  it('renders empty containers structurally and falls back before exceeding display budgets', () => {
    expect(analyzeStructuredText('[]').node).toEqual({ kind: 'array', items: [] });
    const tooMany = `[${Array.from({ length: STRUCTURED_VALUE_MAX_NODES }, () => '0').join(',')}]`;
    expect(analyzeStructuredText(tooMany)).toEqual({ raw: tooMany, tooLarge: true, format: 'json' });
    const tooDeep = `${'['.repeat(STRUCTURED_VALUE_MAX_DEPTH + 2)}0${']'.repeat(STRUCTURED_VALUE_MAX_DEPTH + 2)}`;
    expect(analyzeStructuredText(tooDeep)).toEqual({ raw: tooDeep, tooLarge: true, format: 'json' });
    const tooLong = `{"value":"${'x'.repeat(STRUCTURED_VALUE_MAX_SOURCE_LENGTH)}"}`;
    expect(analyzeStructuredText(tooLong)).toEqual({ raw: tooLong, tooLarge: true, format: 'json' });
    const tooManyBytes = `{"value":"${'中'.repeat(Math.ceil(STRUCTURED_VALUE_MAX_SOURCE_LENGTH / 3))}"}`;
    expect(analyzeStructuredText(tooManyBytes)).toEqual({ raw: tooManyBytes, tooLarge: true, format: 'json' });
  });
});
