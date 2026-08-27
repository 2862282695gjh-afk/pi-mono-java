<script setup lang="ts">
import { ref } from 'vue';

const props = defineProps<{ defaultAgentId: string; busy: boolean }>();
const emit = defineEmits<{
  create: [agentId: string];
  resume: [sessionId: string];
}>();
const agentId = ref(props.defaultAgentId);
const sessionId = ref('');
</script>

<template>
  <details class="dev-diagnostics">
    <summary>开发者诊断入口</summary>
    <p>用于手动创建或恢复 Runtime Session。工具调用中已有的参数会在对话区按原值展示。</p>
    <div class="diagnostic-row">
      <label>Agent ID<input v-model="agentId" placeholder="agent-…" /></label>
      <button type="button" :disabled="busy || !agentId.trim()" @click="emit('create', agentId.trim())">创建</button>
    </div>
    <div class="diagnostic-row">
      <label>Session ID<input v-model="sessionId" placeholder="session-…" /></label>
      <button type="button" :disabled="busy || !sessionId.trim()" @click="emit('resume', sessionId.trim())">恢复</button>
    </div>
  </details>
</template>
