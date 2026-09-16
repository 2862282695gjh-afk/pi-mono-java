export const STRUCTURED_VALUE_MAX_DEPTH = 20;
export const STRUCTURED_VALUE_MAX_NODES = 200;
export const STRUCTURED_VALUE_MAX_SOURCE_LENGTH = 64 * 1024;

export type StructuredScalarKind = 'string' | 'number' | 'boolean' | 'null';

export interface StructuredScalarNode {
  kind: StructuredScalarKind;
  raw: string;
}

export interface StructuredArrayNode {
  kind: 'array';
  items: StructuredNode[];
}

export interface StructuredObjectEntry {
  key: string;
  value: StructuredNode;
}

export interface StructuredObjectNode {
  kind: 'object';
  entries: StructuredObjectEntry[];
}

export type StructuredNode = StructuredScalarNode | StructuredArrayNode | StructuredObjectNode;

export interface StructuredValueAnalysis {
  raw: string;
  node?: StructuredArrayNode | StructuredObjectNode;
  tooLarge: boolean;
  format: 'json' | 'text';
}

class ParseLimitError extends Error {}

class JsonTreeParser {
  private index = 0;

  private nodes = 0;

  constructor(private readonly source: string) {}

  parse(): StructuredNode {
    const node = this.parseValue(0);
    this.skipWhitespace();
    if (this.index !== this.source.length) throw new SyntaxError('Trailing JSON content');
    return node;
  }

  private parseValue(depth: number): StructuredNode {
    this.skipWhitespace();
    this.nodes += 1;
    if (this.nodes > STRUCTURED_VALUE_MAX_NODES || depth > STRUCTURED_VALUE_MAX_DEPTH) throw new ParseLimitError();
    const character = this.source[this.index];
    if (character === '{') return this.parseObject(depth);
    if (character === '[') return this.parseArray(depth);
    if (character === '"') return { kind: 'string', raw: this.readStringToken() };
    if (character === 't') return this.readLiteral('true', 'boolean');
    if (character === 'f') return this.readLiteral('false', 'boolean');
    if (character === 'n') return this.readLiteral('null', 'null');
    return { kind: 'number', raw: this.readNumberToken() };
  }

  private parseObject(depth: number): StructuredObjectNode {
    this.index += 1;
    const entries: StructuredObjectEntry[] = [];
    this.skipWhitespace();
    if (this.take('}')) return { kind: 'object', entries };
    while (true) {
      this.skipWhitespace();
      const rawKey = this.readStringToken();
      const key = JSON.parse(rawKey) as string;
      this.skipWhitespace();
      this.expect(':');
      entries.push({ key, value: this.parseValue(depth + 1) });
      this.skipWhitespace();
      if (this.take('}')) return { kind: 'object', entries };
      this.expect(',');
    }
  }

  private parseArray(depth: number): StructuredArrayNode {
    this.index += 1;
    const items: StructuredNode[] = [];
    this.skipWhitespace();
    if (this.take(']')) return { kind: 'array', items };
    while (true) {
      items.push(this.parseValue(depth + 1));
      this.skipWhitespace();
      if (this.take(']')) return { kind: 'array', items };
      this.expect(',');
    }
  }

  private readStringToken(): string {
    const start = this.index;
    this.expect('"');
    let escaped = false;
    while (this.index < this.source.length) {
      const character = this.source[this.index++];
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '"') {
        const token = this.source.slice(start, this.index);
        JSON.parse(token);
        return token;
      } else if (character.charCodeAt(0) < 0x20) {
        throw new SyntaxError('Invalid JSON string');
      }
    }
    throw new SyntaxError('Unclosed JSON string');
  }

  private readNumberToken(): string {
    const rest = this.source.slice(this.index);
    const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/u.exec(rest);
    if (!match) throw new SyntaxError('Invalid JSON value');
    this.index += match[0].length;
    return match[0];
  }

  private readLiteral(raw: string, kind: 'boolean' | 'null'): StructuredScalarNode {
    if (!this.source.startsWith(raw, this.index)) throw new SyntaxError('Invalid JSON literal');
    this.index += raw.length;
    return { kind, raw };
  }

  private skipWhitespace(): void {
    while (isJsonWhitespace(this.source[this.index])) this.index += 1;
  }

  private take(character: string): boolean {
    if (this.source[this.index] !== character) return false;
    this.index += 1;
    return true;
  }

  private expect(character: string): void {
    if (!this.take(character)) throw new SyntaxError(`Expected ${character}`);
  }
}

export function analyzeStructuredText(source: string): StructuredValueAnalysis {
  const trimmed = trimJsonWhitespace(source);
  const candidate = isStructuredRoot(trimmed);
  if (new TextEncoder().encode(source).byteLength > STRUCTURED_VALUE_MAX_SOURCE_LENGTH) {
    return { raw: source, tooLarge: true, format: candidate ? 'json' : 'text' };
  }
  if (!candidate) return { raw: source, tooLarge: false, format: 'text' };
  try {
    const node = new JsonTreeParser(trimmed).parse();
    return node.kind === 'array' || node.kind === 'object'
      ? { raw: source, node, tooLarge: false, format: 'json' }
      : { raw: source, tooLarge: false, format: 'text' };
  } catch (error) {
    return { raw: source, tooLarge: error instanceof ParseLimitError, format: error instanceof ParseLimitError ? 'json' : 'text' };
  }
}

export function analyzeStructuredValue(value: unknown): StructuredValueAnalysis {
  let raw: string;
  try {
    raw = JSON.stringify(value, null, 2);
  } catch {
    return { raw: String(value), tooLarge: false, format: 'text' };
  }
  return analyzeStructuredText(raw ?? String(value));
}

function isStructuredRoot(source: string): boolean {
  return (source.startsWith('{') && source.endsWith('}')) || (source.startsWith('[') && source.endsWith(']'));
}

function trimJsonWhitespace(source: string): string {
  return source.replace(/^[ \t\r\n]+|[ \t\r\n]+$/gu, '');
}

function isJsonWhitespace(character: string | undefined): boolean {
  return character === ' ' || character === '\t' || character === '\r' || character === '\n';
}
