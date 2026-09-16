<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { revealFirstDebugHeaderError, validateDebugHeaders } from '../debugHeaders';
import type { DeveloperSettingsController } from '../types/developerSettings';

const props = defineProps<{ settings: DeveloperSettingsController }>();
const rows = props.settings.headerRows;
const root = ref<HTMLElement | null>(null);
const addButton = ref<HTMLButtonElement | null>(null);
const validation = computed(() => validateDebugHeaders(rows.value));

function appendRow(): void {
  props.settings.appendHeader();
  focusRow(rows.value.length - 1);
}

function deleteRow(id: number): void {
  const index = rows.value.findIndex((row) => row.id === id);
  props.settings.deleteHeader(id);
  focusRow(Math.min(index, rows.value.length - 1));
}

async function clearRows(): Promise<void> {
  await props.settings.clearHeaders();
  focusRow(-1);
}

function restorePresets(): void {
  props.settings.restoreHeaderPresets();
  focusRow(0);
}

async function save(): Promise<void> {
  const result = validation.value;
  if (!result.valid) {
    const details = root.value?.closest('details') as HTMLDetailsElement | null;
    await revealFirstDebugHeaderError(details, result, nextTick);
    return;
  }
  await props.settings.saveHeaders();
}

function focusRow(index: number): void {
  void nextTick(() => {
    const input = root.value?.querySelectorAll<HTMLInputElement>('.debug-header-key')[index];
    (input ?? addButton.value)?.focus();
  });
}
</script>

<template>
  <div ref="root" class="debug-headers">
    <div class="header-title-row">
      <span>Headers ({{ validation.enabledCount }})</span>
      <span class="storage-hint">保存在此浏览器</span>
    </div>
    <div class="debug-header-actions">
      <button ref="addButton" type="button" @click="appendRow">增加请求头</button>
      <button type="button" @click="restorePresets">恢复预置</button>
      <button type="button" @click="clearRows">全部清空</button>
    </div>
    <div class="debug-header-grid-head" aria-hidden="true">
      <span></span><span>Key</span><span>Value</span><span></span><span></span>
    </div>
    <div class="debug-header-rows" aria-label="自定义请求 Headers">
      <div
        v-for="(row, index) in rows"
        :key="row.id"
        class="debug-header-row"
        :class="{ 'is-disabled': !row.enabled, 'has-error': Boolean(validation.errors[index]) }"
        data-debug-header-row
      >
        <label class="debug-header-enabled">
          <input v-model="row.enabled" type="checkbox" aria-label="启用此请求头">
        </label>
        <label class="debug-header-field">
          <span class="sr-only">Header Key</span>
          <input
            v-model="row.key"
            class="debug-header-key"
            type="text"
            placeholder="Key"
            autocomplete="off"
            spellcheck="false"
            :aria-invalid="validation.errorFields[index] === 'key'"
          >
        </label>
        <label class="debug-header-field">
          <span class="sr-only">Header Value</span>
          <input
            v-model="row.value"
            class="debug-header-value"
            :type="row.revealed ? 'text' : 'password'"
            placeholder="Value"
            autocomplete="off"
            spellcheck="false"
            :aria-invalid="validation.errorFields[index] === 'value'"
          >
          <span v-if="validation.errors[index]" class="debug-header-error" role="alert">
            {{ validation.errors[index] }}
          </span>
        </label>
        <button
          class="debug-header-reveal"
          type="button"
          :aria-label="row.revealed ? '遮蔽此请求头值' : '显示此请求头值'"
          @click="row.revealed = !row.revealed"
        >{{ row.revealed ? '隐藏' : '显示' }}</button>
        <button class="debug-header-delete" type="button" aria-label="删除此请求头" @click="deleteRow(row.id)">×</button>
      </div>
    </div>
    <div class="group-save-row">
      <span role="status">{{ settings.headerStatus.value }}</span>
      <button type="button" class="primary" @click="save">保存并应用</button>
    </div>
    <span class="sr-only" aria-live="polite">已启用 {{ validation.enabledCount }} 个自定义请求头</span>
  </div>
</template>

<style scoped>
.debug-headers { display: grid; gap: 12px; font-size: 13px; line-height: 1.5; }
.header-title-row, .debug-header-actions, .group-save-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.header-title-row { justify-content: space-between; font-weight: 600; }
.storage-hint { color: #766f66; font-size: 12px; font-weight: 400; }
button { font-size: 13px; min-height: 32px; border: 1px solid #cfc8be; border-radius: 8px; background: #fffdf9; color: #302d29; padding: 5px 10px; }
button:hover { border-color: #9f968a; }
button.primary { margin-left: auto; background: #2f2d2a; border-color: #2f2d2a; color: #fff; }
.debug-header-grid-head, .debug-header-row { display: grid; grid-template-columns: 24px minmax(100px, .8fr) minmax(140px, 1.2fr) auto 32px; gap: 8px; align-items: start; }
.debug-header-grid-head { color: #766f66; font-size: 11px; padding: 0 2px; }
.debug-header-rows { display: grid; gap: 8px; }
.debug-header-field { display: grid; gap: 4px; }
input[type='text'], input[type='password'] { width: 100%; min-height: 34px; box-sizing: border-box; border: 1px solid #d4cec5; border-radius: 7px; background: #fff; padding: 6px 8px; color: #272522; }
input[aria-invalid='true'] { border-color: #a34b3f; outline-color: #a34b3f; }
.is-disabled { opacity: .56; }
.debug-header-error { color: #913d32; font-size: 12px; }
.debug-header-reveal { min-width: 48px; }
.debug-header-delete { width: 32px; padding: 0; font-size: 18px; }
.group-save-row { min-height: 34px; color: #665f57; font-size: 12px; }
@media (max-width: 560px) {
  .debug-header-grid-head { display: none; }
  button { min-height: 44px; }
  .debug-header-actions button { flex: 1 1 120px; }
  .debug-header-row { grid-template-columns: 24px minmax(0, 1fr) auto 44px; align-items: center; }
  .debug-header-enabled { grid-column: 1; grid-row: 1; }
  .debug-header-field:nth-of-type(2) { grid-column: 2 / 4; grid-row: 1; }
  .debug-header-delete { grid-column: 4; grid-row: 1; width: 44px; }
  .debug-header-field:nth-of-type(3) { grid-column: 1 / 4; grid-row: 2; }
  .debug-header-reveal { grid-column: 4; grid-row: 2; min-width: 44px; padding-inline: 4px; }
}
</style>
