<script setup lang="ts">
import SafeRichText from './SafeRichText';
import type { ActivityTurn } from '../types/product';

defineProps<{ turn: ActivityTurn }>();

const BUILT_IN_TOOL_LABELS: Record<string, string> = {
  Read: '读取文件',
  Find: '查找文件',
  Grep: '搜索内容',
  Ls: '列出目录',
  Cron: '管理定时任务',
  ListMateTools: '查询可用工具',
  CallMateTool: '调用业务工具',
  Agent: '运行子 Agent',
};

function toolLabel(name: string): string {
  return BUILT_IN_TOOL_LABELS[name] || name.replace(/[_-]+/gu, ' ');
}

function statusLabel(status: ActivityTurn['status']): string {
  if (status === 'running') return '执行中';
  if (status === 'error') return '执行失败';
  return '已完成';
}

function emptyResult(status: ActivityTurn['status']): string {
  if (status === 'running') return '等待工具返回…';
  if (status === 'error') return '工具未返回错误详情。';
  return '工具已完成，未返回内容。';
}
</script>

<template>
  <details class="tool-activity" :open="turn.status !== 'completed'">
    <summary>
      <span class="tool-state-icon" :class="turn.status" aria-hidden="true">
        <svg v-if="turn.status === 'completed'" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
        <svg v-else-if="turn.status === 'error'" viewBox="0 0 24 24"><path d="m7 7 10 10M17 7 7 17" /></svg>
        <span v-else class="spinner"></span>
      </span>
      <strong>{{ toolLabel(turn.toolName) }}</strong>
      <span class="tool-status">{{ statusLabel(turn.status) }}</span>
      <svg class="tool-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <div class="tool-detail">
      <section>
        <h4>输入参数</h4>
        <dl v-if="turn.arguments.length" class="tool-arguments">
          <template v-for="row in turn.arguments" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd :class="{ redacted: row.redacted }">{{ row.value }}</dd>
          </template>
        </dl>
        <p v-else class="tool-empty">未提供可安全显示的参数摘要。</p>
      </section>
      <section class="tool-output">
        <h4>输出结果</h4>
        <div class="tool-output-content">
          <SafeRichText v-if="turn.result" :source="turn.result" />
          <p v-else class="tool-empty">{{ emptyResult(turn.status) }}</p>
        </div>
      </section>
    </div>
  </details>
</template>
