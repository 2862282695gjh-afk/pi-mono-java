import type { AvailableModels, RuntimeSession } from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';
import { codePointLength, decodeModels, decodeSession, invalidResponse, nonempty, record, strings, text } from './protocol';

export interface CommandDescriptor {
  name: string;
  kind: 'builtin' | 'skill';
  description: string;
  input?: { hint: string; acceptsFiles?: true };
}
export type BuiltinResultKind = 'agentGuide' | 'session' | 'models' | 'compaction' | 'skills';
export type CommandInvocation = Readonly<{
  request: Readonly<{ name: string; arguments?: string }>;
  startedAt: string;
} & ({ executionMode: 'builtinJson'; expectedResultKind: BuiltinResultKind } | { executionMode: 'skillEvents' })>;
export type CommandResult = { invocation: CommandInvocation } & (
  | { kind: 'agentGuide'; value: { displayName: string; description: string[]; userCases: string[] } }
  | { kind: 'session'; value: RuntimeSession }
  | { kind: 'models'; value: AvailableModels }
  | { kind: 'compaction'; value: { compacted: boolean } }
  | { kind: 'skills'; value: { skills: { name: string; description: string }[] } });

export function parseCommand(raw: string): { nameToken: string; argumentsText: string } | null {
  if (!raw.startsWith('/')) return null;
  const match = /^\/([^\t\n\v\f\r ]*)(?:[\t\n\v\f\r ]+([\s\S]*))?$/u.exec(raw)!;
  return { nameToken: match[1], argumentsText: match[2] ?? '' };
}
export function filterCommands(commands: CommandDescriptor[], raw: string): CommandDescriptor[] {
  const token = parseCommand(raw)?.nameToken ?? '';
  return commands.filter((command) => command.name.startsWith(token));
}
export function decodeCatalog(value: unknown): CommandDescriptor[] {
  const commands = record(value).commands;
  if (!Array.isArray(commands)) return invalidResponse();
  const names = new Set<string>();
  return commands.map((value) => {
    const data = record(value);
    const name = nonempty(data.name);
    if (names.has(name) || !/^(?:skill:)?[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(name)) invalidResponse();
    names.add(name);
    if (data.kind !== 'builtin' && data.kind !== 'skill') invalidResponse();
    if ((data.kind === 'skill') !== name.startsWith('skill:')) invalidResponse();
    const descriptor: CommandDescriptor = { name, kind: data.kind, description: text(data.description) };
    if (data.input !== undefined) {
      const input = record(data.input);
      if (input.acceptsFiles !== undefined && input.acceptsFiles !== true) invalidResponse();
      descriptor.input = { hint: nonempty(input.hint), ...(input.acceptsFiles === true ? { acceptsFiles: true as const } : {}) };
    }
    return descriptor;
  });
}
export function freezeInvocation(descriptor: CommandDescriptor, raw: string): CommandInvocation {
  const parsed = parseCommand(raw);
  if (!parsed || parsed.nameToken !== descriptor.name) {
    throw new RuntimeApiError({ code: 'INVALID_COMMAND_INPUT', message: '请从当前清单选择命令。' });
  }
  return freezeCommandArguments(descriptor, parsed.argumentsText);
}
export function freezeCommandArguments(descriptor: CommandDescriptor, argumentsText: string): CommandInvocation {
  if ((!descriptor.input && argumentsText.length) || codePointLength(argumentsText) > 2048) {
    throw new RuntimeApiError({ code: 'INVALID_COMMAND_INPUT', message: '请从当前清单选择命令，并检查参数（最多 2048 个字符）。' });
  }
  const request = Object.freeze({ name: descriptor.name, ...(argumentsText ? { arguments: argumentsText } : {}) });
  const base = { request, startedAt: new Date().toISOString() };
  if (descriptor.kind === 'skill') return Object.freeze({ ...base, executionMode: 'skillEvents' });
  const kinds: Record<string, BuiltinResultKind> = {
    help: 'agentGuide', status: 'session', name: 'session', thinking: 'session',
    model: argumentsText.trim() ? 'session' : 'models', compact: 'compaction', skills: 'skills',
  };
  if (!Object.hasOwn(kinds, descriptor.name)) return invalidResponse();
  return Object.freeze({ ...base, executionMode: 'builtinJson', expectedResultKind: kinds[descriptor.name] });
}
export function decodeCommandResult(invocation: CommandInvocation, value: unknown): CommandResult {
  if (invocation.executionMode !== 'builtinJson') return invalidResponse();
  const data = record(value);
  switch (invocation.expectedResultKind) {
    case 'session': return { invocation, kind: 'session', value: decodeSession(data) };
    case 'models': return { invocation, kind: 'models', value: decodeModels(data) };
    case 'agentGuide': return { invocation, kind: 'agentGuide', value: {
      displayName: nonempty(data.displayName), description: strings(data.description), userCases: strings(data.userCases),
    } };
    case 'compaction':
      if (typeof data.compacted !== 'boolean') return invalidResponse();
      return { invocation, kind: 'compaction', value: { compacted: data.compacted } };
    case 'skills':
      if (!Array.isArray(data.skills)) return invalidResponse();
      return { invocation, kind: 'skills', value: { skills: data.skills.map((value) => {
        const skill = record(value);
        return { name: nonempty(skill.name), description: text(skill.description) };
      }) } };
  }
}
