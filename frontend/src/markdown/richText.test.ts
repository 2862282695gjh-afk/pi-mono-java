import { describe, expect, it } from 'vitest';
import {
  MAX_RICH_TEXT_SOURCE_LENGTH,
  parseSafeMarkdown,
  safeExternalLink,
  splitStableMarkdown,
  type RichTextNode,
} from './richText';

function walk(nodes: RichTextNode[]): RichTextNode[] {
  return nodes.flatMap((node) => {
    if (node.kind === 'element' || node.kind === 'link') return [node, ...walk(node.children)];
    return [node];
  });
}

function textContent(nodes: RichTextNode[]): string {
  return walk(nodes).flatMap((node) => {
    if (node.kind === 'text' || node.kind === 'streamingTail') return [node.value];
    if (node.kind === 'inlineCode' || node.kind === 'codeBlock') return [node.value];
    if (node.kind === 'imagePlaceholder') return [node.alt];
    return [];
  }).join('');
}

describe('parseSafeMarkdown', () => {
  it('projects supported block and inline syntax into controlled nodes', () => {
    const source = [
      '# Heading',
      '',
      '> **bold** and *emphasis* with `inline`',
      '',
      '| Name | Value |',
      '| :--- | ---: |',
      '| alpha | one |',
      '',
      '```ts',
      'const answer = 42;',
      '```',
    ].join('\n');

    const document = parseSafeMarkdown(source);
    const nodes = walk(document.nodes);

    expect(document.fallback).toBe(false);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'h2')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'blockquote')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'strong')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'em')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'table')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'th' && node.textAlign === 'left')).toBe(true);
    expect(nodes.some((node) => node.kind === 'element' && node.name === 'th' && node.textAlign === 'right')).toBe(true);
    expect(nodes.find((node) => node.kind === 'inlineCode')).toMatchObject({ value: 'inline' });
    expect(nodes.find((node) => node.kind === 'codeBlock')).toMatchObject({
      value: 'const answer = 42;\n',
      language: 'ts',
    });
  });

  it('clamps heading hierarchy to h2 through h4', () => {
    const nodes = walk(parseSafeMarkdown('# One\n\n### Three\n\n###### Six').nodes);
    const headings = nodes
      .filter((node) => node.kind === 'element' && /^h\d$/.test(node.name))
      .map((node) => node.kind === 'element' ? node.name : '');

    expect(headings).toEqual(['h2', 'h3', 'h4']);
  });

  it('keeps raw HTML inert and replaces Markdown images with a placeholder', () => {
    const source = '<script>alert("x")</script>\n\n![diagram](https://attacker.example/pixel.png)';
    const nodes = walk(parseSafeMarkdown(source).nodes);

    expect(textContent(nodes)).toContain('<script>alert("x")</script>');
    expect(nodes.some((node) => node.kind === 'element' && ['script', 'img'].includes(node.name))).toBe(false);
    expect(nodes.find((node) => node.kind === 'imagePlaceholder')).toMatchObject({ alt: 'diagram' });
  });

  it('allows only absolute http links without embedded credentials', () => {
    const source = [
      '[safe](https://example.com/path)',
      '',
      '[relative](/settings)',
      '',
      '[script](javascript:alert(1))',
      '',
      '[credentials](https://user:password@example.com/)',
    ].join('\n');
    const links = walk(parseSafeMarkdown(source).nodes).filter((node) => node.kind === 'link');

    expect(links).toContainEqual(expect.objectContaining({
      href: 'https://example.com/path',
      host: 'example.com',
    }));
    expect(links.filter((node) => node.kind === 'link' && node.href !== null)).toHaveLength(1);
    expect(safeExternalLink('data:text/html,test')).toBeNull();
    expect(safeExternalLink('file:///tmp/a')).toBeNull();
  });

  it('marks task-list items as read-only task state', () => {
    const nodes = walk(parseSafeMarkdown('- [x] shipped\n- [ ] pending').nodes);
    const items = nodes.filter((node) => node.kind === 'element' && node.name === 'li');

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ taskChecked: true });
    expect(items[1]).toMatchObject({ taskChecked: false });
    expect(textContent(items)).toContain('shipped');
    expect(textContent(items)).not.toContain('[x]');
  });

  it('keeps the unstable streaming tail plain until a safe block boundary', () => {
    const source = 'Stable paragraph.\n\n| Name | Value |\n| --- | --- |\n| alpha | one |';
    const streaming = parseSafeMarkdown(source, true);
    const completed = parseSafeMarkdown(source, false);

    expect(streaming.nodes.at(-1)).toMatchObject({
      kind: 'streamingTail',
      value: '| Name | Value |\n| --- | --- |\n| alpha | one |',
    });
    expect(walk(streaming.nodes).some((node) => node.kind === 'element' && node.name === 'table')).toBe(false);
    expect(walk(completed.nodes).some((node) => node.kind === 'element' && node.name === 'table')).toBe(true);
  });

  it('does not split on blank lines inside an unfinished fenced code block', () => {
    const source = '```text\nfirst\n\nsecond';
    expect(splitStableMarkdown(source)).toEqual({ stable: '', tail: source });
  });

  it('falls back to plain text when source or node budgets are exceeded', () => {
    const oversized = 'x'.repeat(MAX_RICH_TEXT_SOURCE_LENGTH + 1);
    const deeplyPopulated = Array.from({ length: 6_000 }, (_, index) => `paragraph ${index}`).join('\n\n');

    expect(parseSafeMarkdown(oversized)).toMatchObject({
      fallback: true,
      fallbackReason: 'sourceLength',
    });
    expect(parseSafeMarkdown(deeplyPopulated)).toMatchObject({
      fallback: true,
      fallbackReason: 'nodeCount',
    });
  });
});
