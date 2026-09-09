import { afterEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive } from 'vue';
import { useCommandComposer, type CommandComposerProps } from './useCommandComposer';
import type { CommandDescriptor } from '../runtime/commands';
import type { SubmissionOutcome } from '../types/runtime';

const catalog: CommandDescriptor[] = [
  { name: 'help', kind: 'builtin', description: '查看帮助' },
  { name: 'status', kind: 'builtin', description: '查看状态' },
  { name: 'name', kind: 'builtin', description: '修改名称', input: { hint: '新名称' } },
  { name: 'model', kind: 'builtin', description: '切换模型', input: { hint: '模型 ID' } },
  { name: 'skill:inspect', kind: 'skill', description: '巡检', input: { hint: '巡检要求', acceptsFiles: true } },
];
const scopes: ReturnType<typeof effectScope>[] = [];
afterEach(() => { scopes.splice(0).forEach((scope) => scope.stop()); });

function setup(overrides: Partial<CommandComposerProps> = {}) {
  const props = reactive<CommandComposerProps>({
    modelValue: '', running: false, submitting: false, canSend: true, disabled: false,
    commands: structuredClone(catalog), catalogStatus: 'ready', catalogError: '',
    loadCommands: vi.fn().mockResolvedValue(undefined), executeCommand: vi.fn().mockResolvedValue('confirmed'),
    ...overrides,
  });
  const actions = { updateMessage: vi.fn((value: string) => { props.modelValue = value; }), submitMessage: vi.fn(), focus: vi.fn() };
  const scope = effectScope();
  scopes.push(scope);
  const composer = scope.run(() => useCommandComposer(props, actions))!;
  function select(name: string) {
    composer.enter();
    composer.choose(props.commands.find((command) => command.name === name)!);
  }
  return { props, actions, composer, scope, select };
}
function key(key: string, extra: Partial<KeyboardEvent> = {}): KeyboardEvent {
  return { key, preventDefault: vi.fn(), ...extra } as unknown as KeyboardEvent;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe('command Composer user interactions', () => {
  it('selects on first Enter, ignores key repeat, and executes on a separate Enter', async () => {
    const { composer: c, props } = setup();
    c.updateText('/he');
    expect(c.visible.value.map((item) => item.name)).toEqual(['help']);
    expect(c.submitDisabled.value).toBe(true);
    c.keydown(key('Enter'));
    expect(c.selection.value?.name).toBe('help');
    expect(c.paletteOpen.value).toBe(false);
    expect(c.showInput.value).toBe(false);
    expect(props.executeCommand).not.toHaveBeenCalled();
    c.keydown(key('Enter', { repeat: true }));
    expect(props.executeCommand).not.toHaveBeenCalled();
    c.keydown(key('Enter'));
    expect(props.executeCommand).toHaveBeenCalledWith(expect.objectContaining({ request: { name: 'help' }, executionMode: 'builtinJson' }));
    await nextTick();
    expect(c.commandMode.value).toBe(false);
  });

  it('preserves an ordinary draft when entering from the menu, cancelling or succeeding', async () => {
    const { composer: c, props, select } = setup({ modelValue: '待发送消息' });
    c.toggleMore();
    expect(c.moreOpen.value).toBe(true);
    select('name');
    c.updateText('会话名称');
    c.leave();
    expect(c.text.value).toBe('待发送消息');
    select('help');
    await c.submit();
    expect(c.text.value).toBe('待发送消息');
    expect(props.modelValue).toBe('待发送消息');
  });

  it('freezes canonical names and verbatim separate arguments, including leading whitespace', async () => {
    const { composer: c, props, select } = setup();
    select('skill:inspect');
    const argumentsText = '  /路径\n 保留全部参数 😀';
    c.updateText(argumentsText);
    await c.submit();
    const invocation = vi.mocked(props.executeCommand).mock.calls[0][0];
    expect(invocation.request).toEqual({ name: 'skill:inspect', arguments: argumentsText });
    expect(invocation.executionMode).toBe('skillEvents');
    expect(Object.isFrozen(invocation)).toBe(true);
    expect(Object.isFrozen(invocation.request)).toBe(true);
  });

  it('closes the palette before cancelling and restores the prior command during replacement', () => {
    const { composer: c, select } = setup({ modelValue: '消息草稿' });
    c.enter();
    c.updateText('/na');
    c.escape();
    expect(c.text.value).toBe('/na');
    expect(c.commandMode.value).toBe(true);
    c.escape();
    expect(c.text.value).toBe('消息草稿');
    select('name');
    c.updateText('原名称');
    c.enter();
    c.updateText('/mo');
    c.escape();
    expect(c.selection.value?.name).toBe('name');
    expect(c.text.value).toBe('原名称');
    c.enter();
    c.choose(c.visible.value.find((item) => item.name === 'name')!);
    expect(c.text.value).toBe('原名称');
  });

  it('cancels on Backspace only for a lone slash or empty selected arguments', () => {
    const { composer: c, select } = setup();
    c.updateText('/');
    c.keydown(key('Backspace'));
    expect(c.commandMode.value).toBe(false);
    select('name');
    c.updateText('a');
    c.keydown(key('Backspace'));
    expect(c.commandMode.value).toBe(true);
    c.updateText('');
    c.keydown(key('Backspace'));
    expect(c.commandMode.value).toBe(false);
    select('help');
    c.keydown(key('Backspace'));
    expect(c.commandMode.value).toBe(false);
  });

  it('does not discard typed arguments for a command without input', async () => {
    const { composer: c, props } = setup();
    c.updateText('/help important');
    c.keydown(key('Enter'));
    expect(c.selection.value).toBeNull();
    expect(c.text.value).toBe('/help important');
    expect(c.feedback.value).toContain('不接收参数');
    await c.submit();
    expect(props.executeCommand).not.toHaveBeenCalled();
  });

  it('keeps drafts when commands disappear or the latest catalog stops accepting arguments', async () => {
    const { composer: c, props, select } = setup();
    select('name');
    c.updateText('保留的名称');
    props.commands = props.commands.map((item) => item.name === 'name' ? { name: 'name', kind: 'builtin', description: '只读' } : item);
    expect(c.showInput.value).toBe(true);
    expect(c.feedback.value).toContain('不接收参数');
    await c.submit();
    expect(props.executeCommand).not.toHaveBeenCalled();
    props.commands = [];
    expect(c.feedback.value).toContain('不可用');
    expect(c.text.value).toBe('保留的名称');
  });

  it('reloads stale catalogs and blocks writes during loading and errors', async () => {
    const { composer: c, props, select } = setup();
    select('help');
    props.catalogStatus = 'stale';
    await nextTick();
    expect(props.loadCommands).toHaveBeenCalledTimes(2);
    for (const status of ['stale', 'loading', 'error'] as const) {
      props.catalogStatus = status;
      await c.submit();
      expect(c.submitDisabled.value).toBe(true);
    }
    expect(props.executeCommand).not.toHaveBeenCalled();
    props.catalogStatus = 'ready';
    expect(c.submitDisabled.value).toBe(false);
  });

  it.each(['uncertain', 'rejection'] as const)('preserves command and message drafts after %s', async (outcome) => {
    const { composer: c, props, select } = setup({ modelValue: '普通草稿', executeCommand: outcome === 'uncertain'
      ? vi.fn().mockResolvedValue('uncertain') : vi.fn().mockRejectedValue(new Error('fixture failure')) });
    select('name');
    c.updateText('命令草稿');
    await c.submit();
    expect(c.selection.value?.name).toBe('name');
    expect(c.text.value).toBe('命令草稿');
    expect(c.feedback.value).toContain('草稿已保留');
    expect(props.modelValue).toBe('普通草稿');
  });

  it('prevents duplicate execution and ignores completion after disposal', async () => {
    const pending = deferred<SubmissionOutcome>();
    const { composer: c, props, actions, scope, select } = setup({ executeCommand: vi.fn(() => pending.promise) });
    select('name');
    c.updateText('固定参数');
    const first = c.submit();
    c.updateText('不能修改');
    c.leave();
    await c.submit();
    expect(c.text.value).toBe('固定参数');
    expect(props.executeCommand).toHaveBeenCalledTimes(1);
    scope.stop();
    actions.focus.mockClear();
    pending.resolve('confirmed');
    await first;
    expect(actions.focus).not.toHaveBeenCalled();
    expect(actions.updateMessage).not.toHaveBeenCalled();
  });

  it('resets a confirmed draft even if an external busy flag changed during execution', async () => {
    const pending = deferred<SubmissionOutcome>();
    const { composer: c, props, select } = setup({ executeCommand: vi.fn(() => pending.promise) });
    select('help');
    const submission = c.submit();
    props.disabled = true;
    pending.resolve('confirmed');
    await submission;
    expect(c.commandMode.value).toBe(false);
  });

  it('does not execute or cancel during IME composition', () => {
    const { composer: c, props } = setup();
    c.updateText('/');
    for (const extra of [{ isComposing: true }, { keyCode: 229 }]) {
      c.keydown(key('Enter', extra));
      c.keydown(key('Escape', extra));
      expect(c.paletteOpen.value).toBe(true);
      expect(c.selection.value).toBeNull();
    }
    c.composing.value = true;
    c.keydown(key('Enter'));
    c.keydown(key('Backspace'));
    expect(c.commandMode.value).toBe(true);
    expect(props.executeCommand).not.toHaveBeenCalled();
  });

  it('never submits unknown slash queries as messages and matches prefixes case sensitively', async () => {
    const { composer: c, actions, props } = setup();
    c.updateText('/Status');
    expect(c.visible.value).toEqual([]);
    c.keydown(key('Enter'));
    c.escape();
    await c.submit();
    c.keydown(key('Enter'));
    expect(c.paletteOpen.value).toBe(true);
    expect(actions.submitMessage).not.toHaveBeenCalled();
    expect(props.executeCommand).not.toHaveBeenCalled();
  });

  it('keeps service order for keyboard selection and resets the active option after filtering', async () => {
    const { composer: c } = setup();
    c.enter();
    c.keydown(key('End'));
    expect(c.visible.value[c.activeIndex.value].name).toBe('skill:inspect');
    c.keydown(key('Home'));
    c.keydown(key('ArrowDown'));
    expect(c.visible.value[c.activeIndex.value].name).toBe('status');
    c.updateText('/mo');
    await nextTick();
    expect(c.activeIndex.value).toBe(0);
    c.keydown(key('Enter'));
    expect(c.selection.value?.name).toBe('model');
  });

  it('allows commands offered while running but leaves ordinary message submission disabled', async () => {
    const { composer: c, props, select, actions } = setup({ running: true, canSend: false, modelValue: '下一条消息' });
    await c.submit();
    expect(actions.submitMessage).not.toHaveBeenCalled();
    select('help');
    await c.submit();
    expect(props.executeCommand).toHaveBeenCalledTimes(1);
    expect(c.text.value).toBe('下一条消息');
  });

  it('counts Unicode code points and preserves over-limit input for correction', async () => {
    const { composer: c, props, select } = setup();
    select('name');
    c.updateText('😀'.repeat(2048));
    expect(c.count.value).toBe(2048);
    expect(c.submitDisabled.value).toBe(false);
    c.updateText(c.text.value + 'a');
    await c.submit();
    expect(c.feedback.value).toContain('2048');
    expect(props.executeCommand).not.toHaveBeenCalled();
  });

  it('keeps slashes inside existing messages and accepts drafts during pending message submission', () => {
    const { composer: c, props } = setup({ modelValue: '正文' });
    c.updateText('正文 /help');
    expect(c.commandMode.value).toBe(false);
    expect(props.modelValue).toBe('正文 /help');
    props.modelValue = '';
    props.submitting = true;
    c.updateText('/');
    expect(props.modelValue).toBe('/');
    expect(c.commandMode.value).toBe(false);
  });
});
