<script setup lang="ts">
import { computed, ref } from 'vue';
import { codePointLength } from '../runtime/protocol';
import { toolDisplayIdentity } from '../runtime/toolDisplay';
import type { RuntimeEvent } from '../types/runtime';
const props = defineProps<{ tool: Extract<RuntimeEvent, { type: 'agent.tool_call' }>; disabled: boolean }>();
const emit = defineEmits<{ decide: [decision: 'allow' | 'deny', reason: string] }>();
const reason = ref('');
const tooLong = computed(() => codePointLength(reason.value) > 2048);
const identity = computed(() => toolDisplayIdentity(props.tool));
</script>

<template>
  <section class="tool-confirmation" aria-label="工具调用确认">
    <div class="tool-confirmation-title"
      :aria-label="`需要你确认，${identity.label}`">
      <span>需要你确认 ·</span>
      <strong>{{ identity.label }}</strong>
    </div>
    <p>只授权这一次调用。拒绝后 Agent 将收到工具失败结果，并继续处理。</p>
    <details>
      <summary>查看本次调用详情</summary>
      <p class="tool-parameter-label">调用参数</p>
      <pre class="plain-result">{{ JSON.stringify(tool.arguments, null, 2) }}</pre>
    </details>
    <label>拒绝说明（可选）<textarea v-model="reason" rows="2" :disabled="disabled" :aria-invalid="tooLong" /></label>
    <p v-if="tooLong" role="alert">说明最多 2048 个字符。</p>
    <div class="region-actions">
      <button class="secondary-button" type="button" :disabled="disabled || tooLong" @click="emit('decide', 'deny', reason)">拒绝本次</button>
      <button class="submit-button" type="button" :disabled="disabled" @click="emit('decide', 'allow', '')">允许本次</button>
    </div>
  </section>
</template>
