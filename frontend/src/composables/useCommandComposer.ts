import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { filterCommands, freezeCommandArguments, parseCommand } from '../runtime/commands';
import type { CommandDescriptor, CommandInvocation } from '../runtime/commands';
import { codePointLength } from '../runtime/protocol';
import type { SubmissionOutcome } from '../types/runtime';

export interface CommandComposerProps {
  modelValue: string;
  running: boolean;
  submitting: boolean;
  canSend: boolean;
  disabled: boolean;
  commands: CommandDescriptor[];
  catalogStatus: 'stale' | 'loading' | 'ready' | 'error';
  catalogError: string;
  loadCommands: () => Promise<unknown>;
  executeCommand: (invocation: CommandInvocation) => Promise<SubmissionOutcome>;
}

interface ComposerActions {
  updateMessage: (value: string) => void;
  submitMessage: () => void;
  focus: (target: 'input' | 'submit' | 'chip' | 'more' | 'menu') => void;
}

export function useCommandComposer(props: CommandComposerProps, actions: ComposerActions) {
  const commandMode = ref(false);
  const query = ref('/');
  const argumentsText = ref('');
  const selection = shallowRef<CommandDescriptor | null>(null);
  const paletteOpen = ref(false);
  const moreOpen = ref(false);
  const activeIndex = ref(0);
  const executing = ref(false);
  const composing = ref(false);
  const error = ref('');
  let replacement: { descriptor: CommandDescriptor; argumentsText: string } | null = null;
  let alive = true;
  let draftGeneration = 0;
  onScopeDispose(() => { alive = false; draftGeneration++; });

  const locked = computed(() => props.disabled || executing.value);
  const visible = computed(() => parseCommand(query.value) ? filterCommands(props.commands, query.value) : []);
  const current = computed(() => props.commands.find((item) => item.name === selection.value?.name));
  const selected = computed(() => props.catalogStatus === 'ready' && current.value ? current.value : selection.value);
  const count = computed(() => codePointLength(commandMode.value
    ? selection.value ? argumentsText.value : parseCommand(query.value)?.argumentsText ?? ''
    : props.modelValue));
  const showInput = computed(() => !selection.value || !!selected.value?.input || !!argumentsText.value);
  const selectionError = computed(() => {
    if (!selection.value || props.catalogStatus !== 'ready') return '';
    if (!current.value || current.value.kind !== selection.value.kind) return '当前命令已不可用，请重新选择。';
    if (!current.value.input && argumentsText.value) return '当前命令不接收参数，请清除参数或更换命令。';
    return '';
  });
  const feedback = computed(() => count.value > 2048 ? '最多 2048 个字符，请精简内容。' : selectionError.value || error.value);
  const canRun = computed(() => !!selection.value && !!current.value && props.catalogStatus === 'ready'
    && !selectionError.value && count.value <= 2048);
  const submitDisabled = computed(() => locked.value || props.submitting || paletteOpen.value
    || (commandMode.value ? !canRun.value : !props.canSend || !props.modelValue.trim() || count.value > 2048));
  const text = computed(() => selection.value ? argumentsText.value : commandMode.value ? query.value : props.modelValue);
  const placeholder = computed(() => selected.value?.input?.hint || (commandMode.value ? '查找命令'
    : props.running ? '当前任务结束后可发送，草稿可以继续编辑' : '描述任务，或输入 / 选择命令…'));

  watch(visible, () => { activeIndex.value = 0; });
  watch(() => props.catalogStatus, (status) => {
    if (commandMode.value && status === 'stale') void load();
  });

  function focusEditor(): void {
    if (!alive) return;
    actions.focus(showInput.value ? 'input' : submitDisabled.value ? 'chip' : 'submit');
  }
  async function load(): Promise<void> {
    const generation = draftGeneration;
    try { await props.loadCommands(); }
    catch {
      if (alive && commandMode.value && generation === draftGeneration) error.value = '命令清单读取失败，请重试清单。';
    }
  }
  function enter(): void {
    if (locked.value || props.submitting) return;
    draftGeneration++;
    if (selection.value) {
      replacement = { descriptor: selection.value, argumentsText: argumentsText.value };
      selection.value = null;
      query.value = '/';
    } else if (!commandMode.value) query.value = '/';
    commandMode.value = true;
    paletteOpen.value = true;
    moreOpen.value = false;
    error.value = '';
    activeIndex.value = 0;
    focusEditor();
    void load();
  }
  function leave(): void {
    if (locked.value) return;
    resetDraft();
  }
  function resetDraft(): void {
    draftGeneration++;
    commandMode.value = false;
    selection.value = null;
    replacement = null;
    argumentsText.value = '';
    query.value = '/';
    paletteOpen.value = false;
    moreOpen.value = false;
    error.value = '';
    focusEditor();
  }
  function choose(command: CommandDescriptor): void {
    if (locked.value || composing.value || props.submitting || props.catalogStatus !== 'ready' || !paletteOpen.value) return;
    const descriptor = props.commands.find((item) => item === command);
    if (!descriptor) return;
    const parsedArguments = parseCommand(query.value)?.argumentsText ?? '';
    if (!descriptor.input && parsedArguments) { error.value = '此命令不接收参数。'; return; }
    argumentsText.value = replacement?.descriptor.name === descriptor.name && !parsedArguments
      ? replacement.argumentsText : parsedArguments;
    selection.value = descriptor;
    replacement = null;
    paletteOpen.value = false;
    error.value = '';
    focusEditor();
  }
  function updateText(value: string): void {
    if (locked.value) return;
    error.value = '';
    if (selection.value) argumentsText.value = value;
    else if (commandMode.value) { query.value = value; paletteOpen.value = true; }
    else if (!props.modelValue && value.startsWith('/') && !composing.value && !props.submitting) {
      enter();
      query.value = value;
    } else actions.updateMessage(value);
  }
  function toggleMore(): void {
    if (locked.value || props.submitting) return;
    moreOpen.value = !moreOpen.value;
    if (moreOpen.value) closePalette();
    actions.focus(moreOpen.value ? 'menu' : 'more');
  }
  function closePalette(): void {
    paletteOpen.value = false;
    if (replacement) {
      selection.value = replacement.descriptor;
      argumentsText.value = replacement.argumentsText;
      replacement = null;
    }
  }
  function escape(): void {
    if (locked.value) return;
    if (moreOpen.value) { moreOpen.value = false; actions.focus('more'); }
    else if (paletteOpen.value) { closePalette(); focusEditor(); }
    else if (commandMode.value) leave();
  }
  function dismiss(): void {
    moreOpen.value = false;
    closePalette();
  }
  async function submit(): Promise<void> {
    if (!alive || composing.value || submitDisabled.value) return;
    if (!commandMode.value) { actions.submitMessage(); return; }
    const descriptor = current.value;
    if (!descriptor) return;
    executing.value = true;
    error.value = '';
    try {
      const invocation = freezeCommandArguments(descriptor, argumentsText.value);
      const outcome = await props.executeCommand(invocation);
      if (!alive) return;
      executing.value = false;
      if (outcome === 'confirmed') resetDraft();
      else error.value = '未确认接收，命令草稿已保留，请先核对历史。';
    } catch {
      if (alive) error.value = '命令未完成，草稿已保留。请核对页面错误和最新清单。';
    } finally {
      executing.value = false;
    }
  }
  function moveActive(key: string): void {
    const last = Math.max(visible.value.length - 1, 0);
    activeIndex.value = key === 'Home' ? 0 : key === 'End' ? last
      : Math.max(0, Math.min(last, activeIndex.value + (key === 'ArrowDown' ? 1 : -1)));
  }
  function keydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && event.repeat) { event.preventDefault(); return; }
    if (event.isComposing || composing.value || event.keyCode === 229 || locked.value) return;
    if (event.key === 'Escape') { event.preventDefault(); escape(); return; }
    if (commandMode.value && event.key === 'Backspace'
      && (selection.value ? !argumentsText.value : query.value === '/')) {
      event.preventDefault(); leave(); return;
    }
    if (paletteOpen.value && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); moveActive(event.key); return;
    }
    if (event.key !== 'Enter' || event.shiftKey) return;
    event.preventDefault();
    if (paletteOpen.value) {
      const command = visible.value[activeIndex.value];
      if (command) choose(command);
    } else if (commandMode.value && !selection.value) enter();
    else void submit();
  }

  return { commandMode, query, argumentsText, selection, selected, paletteOpen, moreOpen, activeIndex,
    executing, composing, locked, visible, count, showInput, feedback, submitDisabled, text, placeholder,
    enter, leave, choose, updateText, toggleMore, dismiss, escape, submit, keydown, load };
}
