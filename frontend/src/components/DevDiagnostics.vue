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
    <p>仅开发构建可见。用于公共 Agent/Chat API 完成前的 Runtime 联调，不接收凭据。</p>
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
