<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue';
import { useCommandComposer } from '../composables/useCommandComposer';
import type { CommandComposerProps } from '../composables/useCommandComposer';
import ModeControl from './ModeControl.vue';

const props = defineProps<CommandComposerProps & {
  mode: 'build' | 'plan'; thinking: boolean; modelId: string; models: string[]; configurationDisabled: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: []; stop: []; 'change-mode': [mode: 'build' | 'plan'];
  'toggle-thinking': []; 'change-model': [event: Event]; 'palette-change': [open: boolean] }>();
const root = ref<HTMLElement | null>(null);
const textarea = ref<HTMLTextAreaElement | null>(null);
const submitButton = ref<HTMLButtonElement | null>(null);
const chipButton = ref<HTMLButtonElement | null>(null);
const moreButton = ref<HTMLButtonElement | null>(null);
const menuItem = ref<HTMLDivElement | null>(null);
const commandButton = ref<HTMLButtonElement | null>(null);
const id = useId();
const listId = 'command-list-' + id;
const hintId = 'command-hint-' + id;
const menuId = 'composer-menu-' + id;
const {
  commandMode, selection, selected, paletteOpen, moreOpen, activeIndex, composing, locked,
  visible, count, showInput, feedback, submitDisabled, text, placeholder,
  enter, leave, choose, updateText, toggleMore, dismiss, escape, submit, keydown, load,
} = useCommandComposer(props, {
  updateMessage: (value) => emit('update:modelValue', value),
  submitMessage: () => emit('submit'),
  focus: (target) => {
    void nextTick(() => {
      if (!root.value) return;
      const controls = { input: textarea, submit: submitButton, command: commandButton, chip: chipButton, more: moreButton, menu: menuItem };
      controls[target].value?.focus({ preventScroll: true });
    });
  },
});

function primaryKeydown(event: KeyboardEvent): void {
  if (!props.stopMode) { keydown(event); return; }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    if (!event.repeat && !event.isComposing && event.keyCode !== 229 && !composing.value && props.canStop) emit('stop');
  }
}
function optionId(index: number): string { return listId + '-option-' + index; }
function resizeInput(): void {
  const element = textarea.value;
  if (!element) return;
  element.style.height = 'auto';
  element.style.height = Math.min(element.scrollHeight, 180) + 'px';
}
function onInput(event: Event): void {
  updateText((event.target as HTMLTextAreaElement).value);
  resizeInput();
}
function outsideClick(event: MouseEvent): void {
  if (root.value && !event.composedPath().includes(root.value)) dismiss();
}
function containerEscape(event: KeyboardEvent): void {
  if (!event.defaultPrevented && !event.isComposing && !composing.value && event.keyCode !== 229) {
    event.preventDefault(); escape();
  }
}
function menuKeydown(event: KeyboardEvent): void {
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault();
    menuItem.value?.focus();
  }
}
function menuFocusout(event: FocusEvent): void {
  if (!(event.relatedTarget instanceof Node) || !(event.currentTarget as HTMLElement).contains(event.relatedTarget)) {
    moreOpen.value = false;
  }
}
watch(text, () => void nextTick(resizeInput));
watch(paletteOpen, (open) => emit('palette-change', open), { immediate: true });
watch(activeIndex, async () => {
  await nextTick();
  root.value?.querySelector('#' + CSS.escape(optionId(activeIndex.value)))?.scrollIntoView({ block: 'nearest' });
});
onMounted(() => document.addEventListener('click', outsideClick));
onUnmounted(() => document.removeEventListener('click', outsideClick));
</script>

<template>
  <div ref="root" class="composer-wrap" @keydown.esc="containerEscape">
    <section v-if="commandMode && paletteOpen" class="command-palette" aria-label="命令清单">
      <p v-if="catalogStatus === 'loading' || catalogStatus === 'stale'" role="status">正在读取命令…</p>
      <div v-else-if="catalogStatus === 'error'" role="alert">
        <p>{{ catalogError || '命令清单读取失败' }}</p>
        <button class="secondary-button" type="button" @click="load">重试清单</button>
      </div>
      <ul v-else :id="listId" role="listbox" aria-label="可用命令">
        <li v-for="(command, index) in visible" :id="optionId(index)" :key="command.name"
          role="option" :aria-selected="index === activeIndex" :class="{ selected: index === activeIndex }"
          @pointermove="activeIndex = index" @mousedown.prevent @click="choose(command)">
          <strong>{{ command.name }}</strong><span>{{ command.description }}</span>
        </li>
        <li v-if="!visible.length" class="palette-empty" role="presentation">{{ commands.length ? '没有匹配命令' : '当前没有可用命令' }}</li>
      </ul>
    </section>
    <div class="composer">
      <div class="composer-editor">
        <div v-if="selection" class="command-chip">
          <button ref="chipButton" class="command-chip-name" type="button" :disabled="locked || submitting"
            :aria-label="'更换命令 ' + selection.name" @click="enter">{{ selection.name }}</button>
          <button class="command-chip-remove" type="button" :disabled="locked"
            :aria-label="'移除命令 ' + selection.name" @click="leave">×</button>
        </div>
        <textarea v-show="showInput" ref="textarea" :value="text" rows="2" :disabled="locked"
          :aria-label="selection ? '编辑 ' + selection.name + ' 的参数' : commandMode ? '查找命令' : '给 Agent 发送消息'"
          :role="commandMode && !selection ? 'combobox' : undefined"
          :aria-autocomplete="commandMode && !selection ? 'list' : undefined"
          :aria-expanded="commandMode && !selection ? paletteOpen : undefined"
          :aria-controls="paletteOpen && catalogStatus === 'ready' ? listId : undefined"
          :aria-activedescendant="paletteOpen && catalogStatus === 'ready' && visible.length ? optionId(activeIndex) : undefined"
          :aria-invalid="Boolean(feedback)" :aria-describedby="selection && selected?.input ? hintId : undefined"
          :placeholder="placeholder" @input="onInput" @keydown="keydown"
          @compositionstart="composing = true" @compositionend="composing = false" />
      </div>
      <button v-if="stopMode && selection" ref="commandButton" class="secondary-button composer-command-run" type="button"
        :disabled="submitDisabled" :aria-label="'运行 ' + selection.name" @click="submit" @keydown="keydown">执行命令</button>
      <span v-if="selection && selected?.input" :id="hintId" class="sr-only">{{ selected.input.hint }}</span>
      <p v-if="feedback" class="composer-error" role="alert">{{ feedback }}</p>
      <p v-else-if="commandMode && !paletteOpen && !selection && !visible.length" class="composer-error">没有匹配命令</p>
      <div v-if="selection && catalogStatus === 'error'" class="composer-error" role="alert">
        {{ catalogError || '命令清单读取失败' }} <button class="secondary-button" type="button" @click="load">重试清单</button>
      </div>
      <div class="composer-actions">
        <div class="composer-more">
          <button ref="moreButton" class="icon-button composer-more-button" type="button" aria-label="添加"
            aria-haspopup="menu" :aria-expanded="moreOpen" :aria-controls="menuId" :disabled="locked || submitting" @click="toggleMore">+</button>
          <div v-if="moreOpen" ref="menuItem" :id="menuId" tabindex="-1" class="composer-menu" role="menu" aria-label="添加"
            @keydown="menuKeydown" @focusout="menuFocusout">
            <button type="button" role="menuitem" disabled title="暂未接入附件上传">添加附件 · 暂未接入</button>
          </div>
        </div>
        <div class="composer-session-tools" aria-label="会话配置">
          <ModeControl :mode="mode" :disabled="configurationDisabled" @select="emit('change-mode', $event)" />
          <button class="thinking-control" type="button" :class="{ active: thinking }" :aria-pressed="thinking"
            :disabled="configurationDisabled" @click="emit('toggle-thinking')">深度思考</button>
          <label class="model-select">
            <span class="sr-only">选择模型</span>
            <select :value="modelId" :disabled="configurationDisabled" @change="emit('change-model', $event)">
              <option v-if="!models.includes(modelId)" :value="modelId">{{ modelId }}</option>
              <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
            </select>
          </label>
          <details class="mobile-session-settings">
            <summary>模型与思考设置</summary>
            <div>
              <button type="button" :class="{ active: thinking }" :aria-pressed="thinking"
                :disabled="configurationDisabled" @click="emit('toggle-thinking')">深度思考：{{ thinking ? '开' : '关' }}</button>
              <label><span>模型</span><select :value="modelId" :disabled="configurationDisabled" @change="emit('change-model', $event)">
                <option v-if="!models.includes(modelId)" :value="modelId">{{ modelId }}</option>
                <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
              </select></label>
            </div>
          </details>
        </div>
        <small v-if="count > 1800" class="input-count">{{ count }}/2048</small>
        <button ref="submitButton" class="submit-button" type="button" :disabled="stopMode ? !canStop : submitDisabled"
          :aria-label="stopMode ? '停止生成' : selection ? '运行 ' + selection.name : '发送消息'"
          :title="stopMode ? stopping ? '正在停止' : canStop ? '停止生成' : '正在确认执行状态，暂不可停止' : selection ? '运行 ' + selection.name : '发送消息'"
          @click="stopMode ? emit('stop') : submit()" @keydown="primaryKeydown">
          <svg v-if="stopMode" viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1" class="stop-glyph" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6" /></svg>
        </button>
      </div>
    </div>
    <span class="sr-only" aria-live="polite">{{ paletteOpen && catalogStatus === 'ready' ? visible.length + ' 条匹配命令' : selection ? '已选择 ' + selection.name : '' }}</span>
  </div>
</template>
