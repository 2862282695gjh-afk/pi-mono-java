<script setup lang="ts">
import BrandMark from './BrandMark.vue';
import type { AgentOption } from '../types/product';

defineProps<{
  agent: AgentOption;
  creating: boolean;
  configured: boolean;
  connectionReady?: boolean;
}>();

const emit = defineEmits<{ start: [] }>();
</script>

<template>
  <section class="welcome" aria-labelledby="welcome-title">
    <div class="welcome-mark"><BrandMark /></div>
    <p class="eyebrow">CAMPUSCLAW RUNTIME DEBUG</p>
    <h1 id="welcome-title">选择要调试的 Agent</h1>
    <p class="welcome-intro">连接配置的 Claw Runtime，检查消息、模型原始推理、工具确认和命令。仅限受控联调环境。</p>

    <article class="agent-card" :class="{ unavailable: !configured }">
      <div class="agent-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M4 17 10 5l3.2 6L16 7l4 10H4Z" /><path d="M7 17h10" /></svg>
      </div>
      <div>
        <span>{{ agent.category }}</span>
        <h2>{{ agent.name }}</h2>
        <p>{{ agent.description }}</p>
      </div>
      <button type="button" :disabled="creating || !configured || connectionReady === false" @click="emit('start')">
        <span>{{ creating ? '创建中…' : connectionReady === false ? '连接未就绪' : '开始会话' }}</span>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
      </button>
    </article>

    <p v-if="!configured" class="setup-note">
      请在开发者设置中配置默认 Agent，或从 Session 诊断创建会话。
    </p>
    <div class="welcome-promises" aria-label="调试工作台能力">
      <span>模型原始推理</span><span>工具确认与停止</span><span>Slash 命令</span>
    </div>
  </section>
</template>
