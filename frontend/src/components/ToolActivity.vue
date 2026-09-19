<script setup lang="ts">
import { computed } from 'vue';
import { toolDisplayIdentity } from '../runtime/toolDisplay';
import type { ActivityTurn } from '../types/product';
import StructuredValueView from './StructuredValueView.vue';

const props = defineProps<{ turn: ActivityTurn }>();

function statusLabel(status: ActivityTurn['status']): string {
  if (status === 'running') return '执行中';
  if (status === 'error') return '执行失败';
  if (status === 'confirming') return '等待确认';
  if (status === 'waiting_answer') return '等待回答';
  if (status === 'unconfirmed') return '结果待核对';
  return '已完成';
}

function emptyResult(status: ActivityTurn['status']): string {
  if (status === 'running') return '等待工具返回…';
  if (status === 'error') return '工具未返回错误详情。';
  if (status === 'confirming') return '请在输入框上方允许或拒绝本次调用。';
  if (status === 'waiting_answer') return '请在问题卡填写并提交回答。';
  if (status === 'unconfirmed') return '尚未收到完整结果，请重新核对历史。';
  return '工具已完成，未返回内容。';
}

const hasArguments = computed(() => props.turn.argumentValue
  ? Object.keys(props.turn.argumentValue).length > 0
  : props.turn.arguments.length > 0);
const resultBlocks = computed(() => props.turn.resultBlocks
  ?? ((props.turn.hasResult ?? Boolean(props.turn.result)) ? [props.turn.result] : []));
const identity = computed(() => toolDisplayIdentity({
  toolName: props.turn.toolName,
  displayName: props.turn.displayName,
  arguments: props.turn.argumentValue,
}));
</script>

<template>
  <div v-if="turn.statusMessage" class="tool-status-only" role="status"
    :aria-label="`${identity.label}，${turn.statusMessage}`">
    <span class="tool-state-icon" :class="turn.status" aria-hidden="true">
      <svg v-if="turn.status === 'error'" viewBox="0 0 24 24"><path d="m7 7 10 10M17 7 7 17" /></svg>
      <span v-else-if="turn.status === 'running'" class="spinner"></span>
      <span v-else>·</span>
    </span>
    <strong>{{ identity.label }}</strong>
    <span class="tool-status">{{ turn.statusMessage }}</span>
  </div>
  <details v-else class="tool-activity" :open="turn.status !== 'completed'">
    <summary :aria-label="`${identity.label}，${statusLabel(turn.status)}`">
      <span class="tool-state-icon" :class="turn.status" aria-hidden="true">
        <svg v-if="turn.status === 'completed'" viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg>
        <svg v-else-if="turn.status === 'error'" viewBox="0 0 24 24"><path d="m7 7 10 10M17 7 7 17" /></svg>
        <span v-else-if="turn.status === 'running'" class="spinner"></span>
        <span v-else>·</span>
      </span>
      <strong>{{ identity.label }}</strong>
      <span class="tool-status">{{ statusLabel(turn.status) }}</span>
      <svg class="tool-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </summary>
    <div class="tool-detail">
      <section v-if="hasArguments">
        <h4>调用参数</h4>
        <StructuredValueView v-if="turn.argumentValue" :value="turn.argumentValue" label="完整调用参数" />
        <dl v-else class="tool-arguments">
          <template v-for="row in turn.arguments" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd>{{ row.value }}</dd>
          </template>
        </dl>
      </section>
      <section class="tool-output">
        <h4>输出结果</h4>
        <div class="tool-output-content" aria-label="完整工具结果">
          <template v-if="resultBlocks.length">
            <div v-for="(block, index) in resultBlocks" :key="index" class="tool-result-block">
              <small v-if="resultBlocks.length > 1">结果 {{ index + 1 }}</small>
              <StructuredValueView :source="block" :label="`工具结果 ${index + 1}`" />
            </div>
          </template>
          <p v-else class="tool-empty">{{ emptyResult(turn.status) }}</p>
        </div>
        <small v-if="turn.errorCode" class="event-notice">{{ turn.errorCode }}</small>
      </section>
    </div>
  </details>
</template>

<style scoped>
.tool-status-only {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  min-height: 38px;
  gap: 9px;
  color: var(--text-secondary);
  font-size: 13px;
}
.tool-status-only strong {
  overflow: hidden;
  color: var(--text-primary);
  font-weight: 590;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-result-block + .tool-result-block {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.tool-result-block > small {
  display: block;
  margin-bottom: 3px;
  color: var(--text-tertiary);
  font: 12px/1.5 var(--font-ui, ui-sans-serif, sans-serif);
}
</style>
