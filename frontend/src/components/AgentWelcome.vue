<script setup lang="ts">
import BrandMark from './BrandMark.vue';
import type { AgentOption } from '../types/product';

defineProps<{
  agent: AgentOption;
  creating: boolean;
  configured: boolean;
}>();

const emit = defineEmits<{ start: [] }>();
</script>

<template>
  <section class="welcome" aria-labelledby="welcome-title">
    <div class="welcome-mark"><BrandMark /></div>
    <p class="eyebrow">CAMPUSCLAW RUNTIME DEBUG</p>
    <h1 id="welcome-title">选择要调试的 Agent</h1>
    <p class="welcome-intro">创建真实 Runtime Session，检查模型、Thinking、工具调用和执行控制。</p>

    <article class="agent-card" :class="{ unavailable: !configured }">
      <div class="agent-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M4 17 10 5l3.2 6L16 7l4 10H4Z" /><path d="M7 17h10" /></svg>
      </div>
      <div>
        <span>{{ agent.category }}</span>
        <h2>{{ agent.name }}</h2>
        <p>{{ agent.description }}</p>
      </div>
      <button type="button" :disabled="creating || !configured" @click="emit('start')">
        <span>{{ creating ? '正在创建…' : '开始会话' }}</span>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
      </button>
    </article>

    <p v-if="!configured" class="setup-note">
      当前环境尚未配置默认 Agent。开发环境可在下方诊断入口临时指定。
    </p>
    <div class="welcome-promises" aria-label="调试工作台能力">
      <span>原始推理可见</span><span>原始参数可见</span><span>运行中可调整</span>
    </div>
  </section>
</template>
