<script setup lang="ts">
import { ref, watch } from 'vue';
import type { AcceptedControl, FollowUpMode } from '../types/runtime';

const props = defineProps<{
  modelValue: string;
  running: boolean;
  submitting: boolean;
  mode: FollowUpMode;
  acceptedControls: AcceptedControl[];
  disabled: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'update:mode': [value: FollowUpMode];
  submit: [overrideMode?: FollowUpMode];
}>();
const composerTextarea = ref<HTMLTextAreaElement | null>(null);

watch(
  () => props.modelValue,
  (value) => {
    if (!value && composerTextarea.value) composerTextarea.value.style.height = '';
  },
);

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter') return;
  if (event.shiftKey && !(event.metaKey || event.ctrlKey)) return;
  event.preventDefault();
  if (event.shiftKey && (event.metaKey || event.ctrlKey) && props.running) {
    emit('submit', props.mode === 'steer' ? 'queue' : 'steer');
    return;
  }
  emit('submit');
}

function updateText(event: Event): void {
  const textarea = event.target as HTMLTextAreaElement;
  textarea.style.height = 'auto';
  textarea.style.height = `${Math.min(textarea.scrollHeight, 220)}px`;
  emit('update:modelValue', textarea.value);
}

function updateMode(event: Event): void {
  emit('update:mode', (event.target as HTMLSelectElement).value as FollowUpMode);
}
</script>

<template>
  <div class="composer-wrap">
    <div v-if="acceptedControls.length" class="accepted-list" aria-label="等待处理的要求">
      <div v-for="control in acceptedControls" :key="control.key" class="accepted-item">
        <span class="accepted-status"></span>
        <span class="accepted-copy">
          <strong>{{ control.mode === 'steer' ? '已接受调整' : '已加入队列' }}</strong>
          <small>{{ control.message }}</small>
        </span>
      </div>
    </div>

    <div class="composer" :class="{ running }">
      <div v-if="running" class="follow-up-mode">
        <label for="follow-up-mode">本条消息</label>
        <select id="follow-up-mode" :value="mode" @change="updateMode">
          <option value="steer">调整方向</option>
          <option value="queue">加入队列</option>
        </select>
        <span>{{ mode === 'steer' ? '在下一次模型调用前优先处理' : '当前任务自然结束后继续' }}</span>
      </div>
      <textarea
        ref="composerTextarea"
        :value="modelValue"
        :placeholder="running ? (mode === 'steer' ? '补充要求，调整接下来的处理方向…' : '添加一个完成后继续处理的要求…') : '描述你希望 Agent 完成的任务…'"
        rows="2"
        maxlength="262144"
        :disabled="disabled"
        aria-label="给 Agent 发送消息"
        @input="updateText"
        @keydown="onKeydown"
      ></textarea>
      <div class="composer-actions">
        <button class="icon-button attach-button" type="button" disabled title="附件上传需要公共附件接口" aria-label="添加附件（暂不可用）">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 12 5-5a3 3 0 0 1 4 4l-7 7a5 5 0 0 1-7-7l7-7" /></svg>
        </button>
        <span class="shortcut-hint">{{ running ? '⌘⇧↵ 反转本条处理方式' : 'Enter 发送 · Shift+Enter 换行' }}</span>
        <button
          class="submit-button"
          type="button"
          :disabled="disabled || submitting || !modelValue.trim()"
          @click="emit('submit')"
        >
          <span>{{ submitting ? '正在提交…' : running ? (mode === 'steer' ? '调整方向' : '加入队列') : '发送' }}</span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6" /></svg>
        </button>
      </div>
    </div>
    <p class="composer-note">Agent 可能会出错，请核对重要信息。</p>
  </div>
</template>
