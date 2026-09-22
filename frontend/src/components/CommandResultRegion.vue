<script setup lang="ts">
import type { CommandResult } from '../runtime/commands';
withDefaults(defineProps<{ result: CommandResult; expanded: boolean; temporarilyCollapsed?: boolean }>(), { temporarilyCollapsed: false });
defineEmits<{ close: []; 'update:expanded': [expanded: boolean] }>();
</script>

<template>
  <section class="command-result" aria-label="命令结果" aria-live="polite">
    <header class="region-header">
      <button type="button" class="region-toggle" :aria-expanded="expanded && !temporarilyCollapsed" :disabled="temporarilyCollapsed"
        @click="$emit('update:expanded', !expanded)">
        <strong>命令结果 · 仅当前页面</strong><span aria-hidden="true">{{ expanded && !temporarilyCollapsed ? '收起' : '展开' }}</span>
      </button>
      <button type="button" class="icon-button" aria-label="关闭命令结果" @click="$emit('close')">×</button>
    </header>
    <div v-if="expanded && !temporarilyCollapsed" class="command-result-body" tabindex="0">
    <p class="event-notice">/{{ result.invocation.request.name }}</p>
    <template v-if="result.kind === 'agentGuide'">
      <h3>{{ result.value.displayName }}</h3>
      <p v-if="!result.value.description.length && !result.value.useCases.length">维护者尚未填写 Agent 介绍和使用场景</p>
      <template v-else>
        <p v-for="(line, index) in result.value.description" :key="index" class="plain-result">{{ line }}</p>
        <p v-if="!result.value.description.length">暂无介绍。</p>
        <h4>典型使用场景</h4>
        <ul v-if="result.value.useCases.length"><li v-for="(line, index) in result.value.useCases" :key="index" class="plain-result">{{ line }}</li></ul>
        <p v-else>暂无使用场景。</p>
      </template>
    </template>
    <template v-else-if="result.kind === 'session'">
      <h3>{{ result.value.displayName ?? '未命名会话' }}</h3>
      <dl class="resource-summary">
        <dt>运行状态</dt><dd>{{ result.value.state === 'running' ? '执行中（可能等待确认）' : '空闲' }}</dd>
        <dt>模型</dt><dd>{{ result.value.modelId }}</dd>
        <dt>后续思考展示</dt><dd>{{ result.value.thinking ? '开启' : '关闭' }}</dd>
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
    </div>
  </section>
</template>
