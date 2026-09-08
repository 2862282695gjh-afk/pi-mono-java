<script setup lang="ts">
import type { CommandResult } from '../runtime/commands';
defineProps<{ result: CommandResult }>();
defineEmits<{ close: [] }>();
</script>

<template>
  <section class="command-result" aria-label="命令结果" aria-live="polite" tabindex="0">
    <header class="region-header">
      <strong>命令结果 · 仅当前页面</strong>
      <button type="button" class="icon-button" aria-label="关闭命令结果" @click="$emit('close')">×</button>
    </header>
    <p class="event-notice">/{{ result.invocation.request.name }}</p>
    <template v-if="result.kind === 'agentGuide'">
      <h3>{{ result.value.displayName }}</h3>
      <p v-for="(line, index) in result.value.description" :key="index" class="plain-result">{{ line }}</p>
      <p v-if="!result.value.description.length">暂无介绍。</p>
      <h4>典型使用场景</h4>
      <ul v-if="result.value.userCases.length"><li v-for="(line, index) in result.value.userCases" :key="index">{{ line }}</li></ul>
      <p v-else>暂无使用场景。</p>
    </template>
    <template v-else-if="result.kind === 'session'">
      <h3>{{ result.value.displayName ?? '未命名会话' }}</h3>
      <dl class="resource-summary">
        <dt>运行状态</dt><dd>{{ result.value.state === 'running' ? '执行中（可能等待确认）' : '空闲' }}</dd>
        <dt>模型</dt><dd>{{ result.value.modelId }}</dd>
        <dt>后续思考摘要</dt><dd>{{ result.value.thinking ? '开启' : '关闭' }}</dd>
      </dl>
    </template>
    <template v-else-if="result.kind === 'models'">
      <p>当前模型：{{ result.value.currentModelId }}</p>
      <ul><li v-for="model in result.value.models" :key="model">{{ model }}</li></ul>
      <p v-if="!result.value.models.length">暂无可选模型。</p>
    </template>
    <p v-else-if="result.kind === 'compaction'">{{ result.value.compacted ? '上下文压缩已完成。' : '本次无需压缩。' }}</p>
    <template v-else-if="result.kind === 'skills'">
      <ul><li v-for="skill in result.value.skills" :key="skill.name"><strong>{{ skill.name }}</strong><p>{{ skill.description }}</p></li></ul>
      <p v-if="!result.value.skills.length">当前 Agent 未绑定 Skill。</p>
    </template>
  </section>
</template>
