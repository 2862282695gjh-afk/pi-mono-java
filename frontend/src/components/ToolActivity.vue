<script setup lang="ts">
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
  if (status === 'confirming') return '等待确认';
  if (status === 'unconfirmed') return '结果待核对';
  return '已完成';
}

function emptyResult(status: ActivityTurn['status']): string {
  if (status === 'running') return '等待工具返回…';
  if (status === 'error') return '工具未返回错误详情。';
  if (status === 'confirming') return '请在输入框上方允许或拒绝本次调用。';
  if (status === 'unconfirmed') return '尚未收到完整结果，请重新核对历史。';
  return '工具已完成，未返回内容。';
}
</script>

<template>
  <details class="tool-activity" :open="turn.status !== 'completed'">
    <summary>
      <span class="tool-state-icon" :class="turn.status" aria-hidden="true">
        <svg v-if="turn.status === 'completed'" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
        <svg v-else-if="turn.status === 'error'" viewBox="0 0 24 24"><path d="m7 7 10 10M17 7 7 17" /></svg>
        <span v-else-if="turn.status === 'running'" class="spinner"></span>
        <span v-else>·</span>
      </span>
      <strong>{{ toolLabel(turn.toolName) }}</strong>
      <span class="tool-status">{{ statusLabel(turn.status) }}</span>
      <svg class="tool-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <div class="tool-detail">
      <section>
        <h4>调用参数</h4>
        <dl v-if="turn.arguments.length" class="tool-arguments">
          <template v-for="row in turn.arguments" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd>{{ row.value }}</dd>
          </template>
        </dl>
        <p v-else class="tool-empty">工具未提供输入参数。</p>
      </section>
      <section class="tool-output">
        <h4>输出结果</h4>
        <div class="tool-output-content" tabindex="0" aria-label="完整工具结果">
          <pre v-if="turn.result" class="plain-result">{{ turn.result }}</pre>
          <p v-else class="tool-empty">{{ emptyResult(turn.status) }}</p>
        </div>
        <small v-if="turn.errorCode" class="event-notice">{{ turn.errorCode }}</small>
      </section>
    </div>
  </details>
</template>
