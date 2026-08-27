import MarkdownIt, { type Token } from 'markdown-it';

export const MAX_ASSISTANT_MARKDOWN_SOURCE_LENGTH = 200_000;
export const MAX_ASSISTANT_RICH_TEXT_NODES = 10_000;

export type RichElementName =
  | 'p'
  | 'h2'
  | 'h3'
  | 'h4'
  | 'ul'
  | 'ol'
  | 'li'
  | 'blockquote'
  | 'hr'
  | 'strong'
  | 'em'
  | 'del'
  | 'br'
  | 'table'
  | 'thead'
  | 'tbody'
  | 'tr'
  | 'th'
  | 'td';

export interface RichTextValueNode {
  kind: 'text';
  value: string;
}

export interface RichElementNode {
  kind: 'element';
  name: RichElementName;
  children: RichTextNode[];
  orderedStart?: number;
  taskChecked?: boolean;
  textAlign?: 'left' | 'center' | 'right';
}

export interface RichInlineCodeNode {
  kind: 'inlineCode';
  value: string;
}

export interface RichCodeBlockNode {
  kind: 'codeBlock';
  value: string;
  language: string;
}

export interface RichLinkNode {
  kind: 'link';
  children: RichTextNode[];
  href: string | null;
  host: string | null;
}

export interface RichImagePlaceholderNode {
  kind: 'imagePlaceholder';
  alt: string;
}

export interface RichStreamingTailNode {
  kind: 'streamingTail';
  value: string;
}

export type RichTextNode =
  | RichTextValueNode
  | RichElementNode
  | RichInlineCodeNode
  | RichCodeBlockNode
  | RichLinkNode
  | RichImagePlaceholderNode
  | RichStreamingTailNode;

export interface AssistantRichTextDocument {
  nodes: RichTextNode[];
  fallback: boolean;
  fallbackReason?: 'sourceLength' | 'nodeCount' | 'parseError';
}

interface ParseBudget {
  nodeCount: number;
}

interface ParseFrame {
  children: RichTextNode[];
  closeType: string | null;
}

interface SafeLink {
  href: string;
  host: string;
}

class NodeBudgetExceeded extends Error {}

const markdown = new MarkdownIt('default', {
  html: false,
  breaks: false,
  linkify: false,
  typographer: false,
  maxNesting: 32,
});

const elementByTokenType: Partial<Record<string, RichElementName>> = {
  paragraph_open: 'p',
  bullet_list_open: 'ul',
  ordered_list_open: 'ol',
  list_item_open: 'li',
  blockquote_open: 'blockquote',
  table_open: 'table',
  thead_open: 'thead',
  tbody_open: 'tbody',
  tr_open: 'tr',
  th_open: 'th',
  td_open: 'td',
  strong_open: 'strong',
  em_open: 'em',
  s_open: 'del',
};

export function parseAssistantMarkdown(source: string, streaming = false): AssistantRichTextDocument {
  if (source.length > MAX_ASSISTANT_MARKDOWN_SOURCE_LENGTH) {
    return fallbackDocument(source, 'sourceLength');
  }

  const { stable, tail } = streaming ? splitStableMarkdown(source) : { stable: source, tail: '' };
  const budget: ParseBudget = { nodeCount: 0 };
  try {
    const nodes = parseTokens(markdown.parse(stable, {}), budget);
    markTaskItems(nodes);
    if (tail) appendNode(nodes, { kind: 'streamingTail', value: tail }, budget);
    return { nodes, fallback: false };
  } catch (error) {
    if (error instanceof NodeBudgetExceeded) return fallbackDocument(source, 'nodeCount');
    return fallbackDocument(source, 'parseError');
  }
}

export function safeExternalLink(value: string): SafeLink | null {
  try {
    const url = new URL(value);
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) return null;
    return { href: url.href, host: url.host };
  } catch {
    return null;
  }
}

export function splitStableMarkdown(source: string): { stable: string; tail: string } {
  let position = 0;
  let stableBoundary = 0;
  let fenceMarker = '';

  while (position < source.length) {
    const newlineIndex = source.indexOf('\n', position);
    const lineEnd = newlineIndex === -1 ? source.length : newlineIndex + 1;
    const line = source.slice(position, newlineIndex === -1 ? source.length : newlineIndex).replace(/\r$/, '');
    const fence = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (fence) fenceMarker = updateFenceMarker(fenceMarker, fence[1], fence[2]);
    if (!fenceMarker && /^\s*$/.test(line)) stableBoundary = lineEnd;
    position = lineEnd;
  }

  return {
    stable: source.slice(0, stableBoundary),
    tail: source.slice(stableBoundary),
  };
}

function updateFenceMarker(current: string, candidate: string, suffix: string): string {
  if (!current) return candidate;
  const closesCurrent = candidate[0] === current[0]
    && candidate.length >= current.length
    && /^\s*$/.test(suffix);
  return closesCurrent ? '' : current;
}

function parseTokens(tokens: Token[], budget: ParseBudget): RichTextNode[] {
  const roots: RichTextNode[] = [];
  const frames: ParseFrame[] = [{ children: roots, closeType: null }];

  for (const token of tokens) {
    const current = frames[frames.length - 1].children;
    if (token.type === 'inline') {
      appendChildren(current, parseTokens(token.children ?? [], budget));
      continue;
    }
    if (token.type === 'text' || token.type === 'html_inline' || token.type === 'html_block') {
      appendText(current, token.content, budget);
      continue;
    }
    if (token.type === 'code_inline') {
      appendNode(current, { kind: 'inlineCode', value: token.content }, budget);
      continue;
    }
    if (token.type === 'fence' || token.type === 'code_block') {
      appendNode(current, createCodeBlock(token), budget);
      continue;
    }
    if (token.type === 'image') {
      appendNode(current, { kind: 'imagePlaceholder', alt: token.content.trim() }, budget);
      continue;
    }
    if (token.type === 'softbreak') {
      appendText(current, '\n', budget);
      continue;
    }
    if (token.type === 'hardbreak') {
      appendNode(current, createElement('br'), budget);
      continue;
    }
    if (token.type === 'hr') {
      appendNode(current, createElement('hr'), budget);
      continue;
    }
    if (token.type === 'heading_open') {
      openElement(frames, clampHeading(token.tag), token, budget);
      continue;
    }
    if (token.type === 'link_open') {
      openLink(frames, token, budget);
      continue;
    }
    const elementName = elementByTokenType[token.type];
    if (token.nesting === 1 && elementName) {
      if (!token.hidden) openElement(frames, elementName, token, budget);
      continue;
    }
    if (token.nesting === -1) closeFrame(frames, token.type);
  }

  return roots;
}

function createCodeBlock(token: Token): RichCodeBlockNode {
  return {
    kind: 'codeBlock',
    value: token.content,
    language: token.info.trim().split(/\s+/, 1)[0] ?? '',
  };
}

function createElement(name: RichElementName): RichElementNode {
  return { kind: 'element', name, children: [] };
}

function openElement(
  frames: ParseFrame[],
  name: RichElementName,
  token: Token,
  budget: ParseBudget,
): void {
  const node = createElement(name);
  if (name === 'ol') {
    const start = Number(token.attrGet('start'));
    if (Number.isInteger(start) && start > 1) node.orderedStart = start;
  }
  if (name === 'th' || name === 'td') node.textAlign = readTextAlignment(token);
  appendNode(frames[frames.length - 1].children, node, budget);
  frames.push({ children: node.children, closeType: token.type.replace(/_open$/, '_close') });
}

function readTextAlignment(token: Token): RichElementNode['textAlign'] {
  const style = String(token.attrGet('style') ?? '');
  const match = style.match(/^text-align:(left|center|right)$/);
  return match?.[1] as RichElementNode['textAlign'];
}

function openLink(frames: ParseFrame[], token: Token, budget: ParseBudget): void {
  const safeLink = safeExternalLink(String(token.attrGet('href') ?? ''));
  const node: RichLinkNode = {
    kind: 'link',
    children: [],
    href: safeLink?.href ?? null,
    host: safeLink?.host ?? null,
  };
  appendNode(frames[frames.length - 1].children, node, budget);
  frames.push({ children: node.children, closeType: 'link_close' });
}

function closeFrame(frames: ParseFrame[], closeType: string): void {
  if (frames.length > 1 && frames[frames.length - 1].closeType === closeType) frames.pop();
}

function appendChildren(target: RichTextNode[], children: RichTextNode[]): void {
  for (const child of children) target.push(child);
}

function appendText(target: RichTextNode[], value: string, budget: ParseBudget): void {
  if (!value) return;
  const previous = target[target.length - 1];
  if (previous?.kind === 'text') {
    previous.value += value;
    return;
  }
  appendNode(target, { kind: 'text', value }, budget);
}

function appendNode(target: RichTextNode[], node: RichTextNode, budget: ParseBudget): void {
  budget.nodeCount += 1;
  if (budget.nodeCount > MAX_ASSISTANT_RICH_TEXT_NODES) throw new NodeBudgetExceeded();
  target.push(node);
}

function clampHeading(tag: string): 'h2' | 'h3' | 'h4' {
  if (tag === 'h1' || tag === 'h2') return 'h2';
  if (tag === 'h3') return 'h3';
  return 'h4';
}

function markTaskItems(nodes: RichTextNode[]): void {
  for (const node of nodes) {
    if (node.kind !== 'element') continue;
    if (node.name === 'li') markTaskItem(node);
    markTaskItems(node.children);
  }
}

function markTaskItem(item: RichElementNode): void {
  const firstText = findFirstText(item.children);
  if (!firstText) return;
  const marker = firstText.value.match(/^\[([ xX])\]\s+/);
  if (!marker) return;
  item.taskChecked = marker[1].toLowerCase() === 'x';
  firstText.value = firstText.value.slice(marker[0].length);
}

function findFirstText(nodes: RichTextNode[]): RichTextValueNode | null {
  for (const node of nodes) {
    if (node.kind === 'text') return node;
    if (node.kind === 'element' || node.kind === 'link') {
      const nested = findFirstText(node.children);
      if (nested) return nested;
    }
  }
  return null;
}

function fallbackDocument(
  source: string,
  fallbackReason: AssistantRichTextDocument['fallbackReason'],
): AssistantRichTextDocument {
  return {
    nodes: [{ kind: 'streamingTail', value: source }],
    fallback: true,
    fallbackReason,
  };
}
