<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { filterCommands, freezeInvocation, parseCommand } from '../runtime/commands';
import type { CommandDescriptor, CommandInvocation } from '../runtime/commands';
import { codePointLength } from '../runtime/protocol';
import type { SubmissionOutcome } from '../types/runtime';

const props = defineProps<{
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
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: [] }>();
const textarea = ref<HTMLTextAreaElement | null>(null);
const commandMode = ref(false);
const rawCommand = ref('/');
const selectedName = ref('');
const paletteOpen = ref(false);
const enteredByButton = ref(false);
const activeIndex = ref(0);
const executing = ref(false);
const composing = ref(false);
const error = ref('');
const visible = computed(() => filterCommands(props.commands, rawCommand.value));
const selected = computed(() => props.catalogStatus === 'ready' ? props.commands.find((item) => item.name === selectedName.value) : undefined);
const count = computed(() => codePointLength(commandMode.value ? parseCommand(rawCommand.value)?.argumentsText ?? '' : props.modelValue));
const canRun = computed(() => selected.value && parseCommand(rawCommand.value)?.nameToken === selected.value.name && count.value <= 2048
  && (selected.value.input || !parseCommand(rawCommand.value)?.argumentsText));

watch(() => props.catalogStatus, (status) => {
  if (commandMode.value && status === 'stale') void load();
});
watch(visible, () => { activeIndex.value = 0; });
watch(activeIndex, async () => {
  await nextTick();
  document.getElementById(`command-option-${activeIndex.value}`)?.scrollIntoView({ block: 'nearest' });
});
async function load(): Promise<void> {
  try { await props.loadCommands(); } catch { /* 清单错误由同位置内联显示。 */ }
}
async function enter(byButton: boolean): Promise<void> {
  commandMode.value = true; paletteOpen.value = true; enteredByButton.value = byButton;
  await load(); await nextTick(); textarea.value?.focus();
}
function leave(): void {
  commandMode.value = false; paletteOpen.value = false; selectedName.value = ''; rawCommand.value = '/'; error.value = '';
  void nextTick(() => textarea.value?.focus());
}
function choose(command: CommandDescriptor): void {
  if (props.catalogStatus !== 'ready' || executing.value) return;
  selectedName.value = command.name;
  rawCommand.value = `/${command.name}${command.input ? ' ' : ''}`;
  paletteOpen.value = false; error.value = '';
  void nextTick(() => textarea.value?.focus());
}
function updateText(event: Event): void {
  const element = event.target as HTMLTextAreaElement;
  element.style.height = 'auto'; element.style.height = `${Math.min(element.scrollHeight, 180)}px`;
  if (commandMode.value) {
    rawCommand.value = element.value;
    if (parseCommand(rawCommand.value)?.nameToken !== selectedName.value) { selectedName.value = ''; paletteOpen.value = true; }
  } else if (!props.modelValue && element.value.startsWith('/') && !composing.value) {
    rawCommand.value = element.value; void enter(false);
  } else emit('update:modelValue', element.value);
}
async function runCommand(): Promise<void> {
  if (!canRun.value || !selected.value || executing.value || props.disabled) return;
  executing.value = true; error.value = '';
  try {
    const invocation = freezeInvocation(selected.value, rawCommand.value);
    if (await props.executeCommand(invocation) === 'confirmed') leave();
    else error.value = '未确认接收，命令草稿已保留，请先核对历史。';
  } catch { error.value = '命令未完成，草稿已保留。请核对页面错误和最新清单。'; }
  finally { executing.value = false; }
}
function submit(): void {
  if (props.disabled || props.submitting || executing.value || count.value > 2048) return;
  if (commandMode.value) {
    if (paletteOpen.value && visible.value[activeIndex.value]) choose(visible.value[activeIndex.value]);
    else void runCommand();
  } else if (props.canSend && props.modelValue.trim()) emit('submit');
}
function onKeydown(event: KeyboardEvent): void {
  if (event.isComposing || composing.value || event.keyCode === 229) return;
  if (commandMode.value && event.key === 'Escape') {
    event.preventDefault();
    if (enteredByButton.value || !paletteOpen.value) leave();
    else paletteOpen.value = false;
    return;
  }
  if (commandMode.value && rawCommand.value === '/' && event.key === 'Backspace') { event.preventDefault(); leave(); return; }
  if (paletteOpen.value && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault();
    const last = Math.max(visible.value.length - 1, 0);
    activeIndex.value = event.key === 'Home' ? 0 : event.key === 'End' ? last
      : Math.max(0, Math.min(last, activeIndex.value + (event.key === 'ArrowDown' ? 1 : -1)));
    return;
  }
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); submit(); }
}
</script>

<template>
  <div class="composer-wrap">
    <section v-if="commandMode && paletteOpen" class="command-palette" aria-label="命令清单">
      <header class="region-header"><strong>命令清单</strong><small>↑ ↓ 选择 · Enter 填入</small></header>
      <p v-if="catalogStatus === 'loading' || catalogStatus === 'stale'" role="status">正在读取当前会话的命令…</p>
      <div v-else-if="catalogStatus === 'error'" role="alert"><p>{{ catalogError }}</p><button class="secondary-button" type="button" @click="load">重试清单</button></div>
      <ul v-else id="command-list" role="listbox" aria-label="可用命令">
        <li v-for="(command, index) in visible" :id="`command-option-${index}`" :key="command.name"
          role="option" :aria-selected="index === activeIndex" :class="{ selected: index === activeIndex }"
          @pointermove="activeIndex = index" @mousedown.prevent @click="choose(command)">
          <div><strong>/{{ command.name }}</strong><span class="command-kind">{{ command.kind }}</span><p>{{ command.description }}</p></div>
          <small v-if="command.input">{{ command.input.hint }}</small>
        </li>
        <li v-if="!visible.length" class="palette-empty" role="presentation">{{ commands.length ? '没有匹配命令；不会作为普通消息发送。' : '当前没有可用命令。' }}</li>
      </ul>
    </section>
    <div class="composer" :class="{ running }">
      <div v-if="commandMode" class="command-mode-header"><strong>命令模式</strong><button class="secondary-button" type="button" :disabled="executing" @click="leave">返回消息</button></div>
      <textarea ref="textarea" :value="commandMode ? rawCommand : modelValue" rows="2"
        :disabled="disabled || executing" :aria-label="commandMode ? '编辑命令' : '给 Agent 发送消息'"
        role="combobox" aria-autocomplete="list" :aria-expanded="commandMode && paletteOpen"
        :aria-controls="commandMode && paletteOpen && catalogStatus === 'ready' ? 'command-list' : undefined"
        :aria-activedescendant="commandMode && paletteOpen && catalogStatus === 'ready' && visible.length ? `command-option-${activeIndex}` : undefined"
        :aria-invalid="count > 2048" aria-describedby="composer-guidance"
        :placeholder="running ? '当前任务结束后才能发送；草稿可以继续编辑…' : '描述任务，或输入 / 选择命令…'"
        @input="updateText" @keydown="onKeydown" @compositionstart="composing = true" @compositionend="composing = false" />
      <p v-if="commandMode && selected?.input" class="command-hint">{{ selected.input.hint }}</p>
      <p v-if="error || count > 2048" class="composer-error" role="alert">{{ count > 2048 ? '最多 2048 个字符，请精简内容。' : error }}</p>
      <div class="composer-actions">
        <button class="icon-button slash-button" type="button" aria-label="打开命令清单" :disabled="disabled || executing" @click="enter(true)">/</button>
        <button class="icon-button attach-button" type="button" disabled title="上传接口尚未接入；已存在的附件引用可在历史中查看" aria-label="添加附件（上传尚未接入）">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 12 5-5a3 3 0 0 1 4 4l-7 7a5 5 0 0 1-7-7l7-7" /></svg>
        </button>
        <span class="shortcut-hint">{{ commandMode ? '选择只填入，再次 Enter 执行' : 'Enter 发送 · Shift+Enter 换行' }}</span>
        <small class="input-count">{{ count }}/2048</small>
        <button class="submit-button" type="button"
          :disabled="disabled || submitting || executing || count > 2048 || (commandMode ? (!canRun && !(paletteOpen && visible.length && catalogStatus === 'ready')) : (!canSend || !modelValue.trim()))"
          @click="submit">{{ executing || submitting ? '提交中…' : commandMode ? (paletteOpen ? '填入' : '运行') : '发送' }}</button>
      </div>
    </div>
    <p id="composer-guidance" class="composer-note">{{ running ? '任务执行中不接受下一条消息；可使用清单中的查询命令。' : 'Agent 可能会出错，请核对重要信息。' }}</p>
    <span class="sr-only" aria-live="polite">{{ commandMode && paletteOpen ? `${visible.length} 条匹配命令` : '' }}</span>
  </div>
</template>
