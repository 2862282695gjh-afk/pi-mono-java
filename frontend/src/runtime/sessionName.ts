import { javaStrip } from './protocol';

const forbidden = /[\p{Cc}\p{Cs}\u202A-\u202E\u2066-\u2069]/u;

export function validateSessionName(input: string): { value?: string; error?: string } {
  const value = javaStrip(input);
  if (!value) return { error: '会话名称不能为空。' };
  if (forbidden.test(value)) return { error: '会话名称不能包含控制字符或方向控制符。' };
  if (new TextEncoder().encode(value).length > 80) return { error: '会话名称不能超过 80 个 UTF-8 字节。' };
  return { value };
}
