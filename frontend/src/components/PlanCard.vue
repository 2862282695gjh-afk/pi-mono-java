<script lang="ts">
const reviewDrafts = new Map<string, string>();
</script>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import type { PlanTurn } from '../types/product';
import SafeRichText from './SafeRichText';
import { copyPlanMarkdown } from '../runtime/planClipboard';

const props = defineProps<{ plan: PlanTurn; actionable: boolean; pending: boolean; sessionId: string }>();
const emit = defineEmits<{ decide: [decision: 'approve' | 'revise', feedback: string] }>();
const expanded = ref(props.plan.decision !== 'approve');
const editing = ref(false);
const draftKey = `${props.sessionId}:${props.plan.eventId}`;
const feedback = ref(reviewDrafts.get(draftKey) ?? '');
const textarea = ref<HTMLTextAreaElement | null>(null);
const reviseButton = ref<HTMLButtonElement | null>(null);
const status = ref<HTMLElement | null>(null);
const copyStatus = ref('');

function statusLabel(): string {
  if (props.plan.decision === 'approve') return '已批准方案';
  if (props.plan.decision === 'revise') return '已退回修改';
  if (props.pending) return '正在提交决定';
  if (props.plan.lifecycle === 'stopping') return '正在停止，方案尚未执行';
  if (props.plan.lifecycle === 'stopped') return '本轮已停止，方案未执行';
  if (props.plan.lifecycle === 'failed') return '本轮失败，方案未执行';
  if (props.plan.lifecycle === 'ended') return '本轮已结束，方案未执行';
  return props.actionable ? '方案已就绪 · 待审核' : '方案状态待核对';
}

watch(() => props.plan.decision, (decision) => { if (decision === 'approve') expanded.value = false; });
watch(feedback, (value) => reviewDrafts.set(draftKey, value));
async function openFeedback(): Promise<void> {
  editing.value = true;
  await nextTick();
  textarea.value?.focus();
}
async function cancelFeedback(): Promise<void> {
  editing.value = false;
  await nextTick();
  reviseButton.value?.focus();
}
function submitRevision(): void {
  if (feedback.value && !feedback.value.trim()) return;
  emit('decide', 'revise', feedback.value);
  void nextTick(() => status.value?.focus());
}
function approve(): void {
  emit('decide', 'approve', '');
  void nextTick(() => status.value?.focus());
}
async function copyPlan(): Promise<void> {
  copyStatus.value = await copyPlanMarkdown(props.plan.rawMarkdown);
}
</script>

<template>
  <section class="plan-card" :class="{ approved: plan.decision === 'approve' }" :aria-label="plan.decision ? '历史方案' : '待审核方案'">
    <header>
      <button type="button" class="plan-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        <span ref="status" tabindex="-1">{{ statusLabel() }}</span>
        <span aria-hidden="true">{{ expanded ? '收起' : '展开' }}</span>
      </button>
      <button type="button" class="secondary-button plan-copy" @click="copyPlan">复制方案</button>
    </header>
    <p class="plan-copy-status" aria-live="polite">{{ copyStatus }}</p>
    <div v-if="expanded" class="plan-content"><SafeRichText :source="plan.rawMarkdown" :streaming="false" /></div>
    <div v-if="actionable" class="plan-actions">
      <button class="primary-button" type="button" :disabled="pending" @click="approve">批准并执行</button>
      <button ref="reviseButton" class="secondary-button" type="button" :disabled="pending" @click="openFeedback">修改方案</button>
    </div>
    <div v-if="actionable && editing" class="plan-feedback">
      <label>修改意见（可选）
        <textarea ref="textarea" v-model="feedback" maxlength="4096" rows="3" :disabled="pending"
          :aria-invalid="Boolean(feedback && !feedback.trim())" @keydown.esc.prevent="cancelFeedback" />
      </label>
      <p v-if="feedback && !feedback.trim()" role="alert">修改意见不能只包含空白字符。</p>
      <div>
        <button class="primary-button" type="button" :disabled="pending || Boolean(feedback && !feedback.trim())" @click="submitRevision">提交修改意见</button>
        <button class="secondary-button" type="button" :disabled="pending" @click="cancelFeedback">取消</button>
      </div>
    </div>
    <p v-if="!actionable && !plan.decision && !plan.lifecycle && !pending" class="event-notice">正在核对方案状态，暂不能审核。</p>
  </section>
</template>
