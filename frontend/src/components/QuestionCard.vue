<script lang="ts">
import { QuestionDraftStore } from '../runtime/questionInteraction';

const questionDraftStore = new QuestionDraftStore();
</script>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { copyQuestionDrafts, displayedCustomAnswer, validateQuestionDrafts } from '../runtime/questionInteraction';
import type { QuestionDrafts } from '../runtime/questionInteraction';
import { javaStrip } from '../runtime/protocol';
import type { QuestionAnswer, RuntimeQuestion } from '../types/runtime';
import type { QuestionTurn } from '../types/product';

const props = defineProps<{
  turn: QuestionTurn;
  sessionId: string;
  actionable: boolean;
  pending: boolean;
}>();
const emit = defineEmits<{ answer: [eventId: string, answers: QuestionAnswer[]] }>();
const drafts = ref<QuestionDrafts>(questionDraftStore.read(props.sessionId, props.turn.eventId, props.turn.questions));
const errors = ref<Record<string, string>>(Object.create(null) as Record<string, string>);

const state = computed(() => {
  if (props.turn.answer) return 'answered';
  if (props.pending) return 'submitting';
  if (props.turn.lifecycle === 'closed' || props.turn.lifecycle === 'stopping') return 'closed';
  return props.actionable ? 'waiting' : 'recovering';
});
const statusLabel = computed(() => ({
  waiting: '需要你的回答', submitting: '正在提交回答', answered: '已回答',
  closed: props.turn.lifecycle === 'stopping' ? '正在停止，问题已关闭' : '本轮已结束，问题已关闭',
  recovering: '正在核对问题状态',
})[state.value]);

watch(drafts, (value) => questionDraftStore.write(props.sessionId, props.turn.eventId, copyQuestionDrafts(value)), { deep: true });

function answerFor(question: RuntimeQuestion): QuestionAnswer | undefined {
  return props.turn.answer?.find((answer) => answer.id === question.id);
}
function selected(question: RuntimeQuestion): string[] {
  return answerFor(question)?.selected ?? drafts.value[question.id]?.selected ?? [];
}
function custom(question: RuntimeQuestion): string {
  return displayedCustomAnswer(answerFor(question), drafts.value[question.id]);
}
function choose(question: RuntimeQuestion, label: string, checked: boolean): void {
  const draft = drafts.value[question.id];
  if (!draft || !props.actionable || props.pending) return;
  if (question.multiSelect) {
    draft.selected = checked ? [...new Set([...draft.selected, label])] : draft.selected.filter((item) => item !== label);
  } else {
    draft.selected = checked ? [label] : [];
    if (checked) draft.custom = '';
  }
  delete errors.value[question.id];
}
function writeCustom(question: RuntimeQuestion, event: Event): void {
  const draft = drafts.value[question.id];
  if (!draft || !props.actionable || props.pending) return;
  draft.custom = (event.target as HTMLTextAreaElement).value;
  if (!question.multiSelect && javaStrip(draft.custom)) draft.selected = [];
  delete errors.value[question.id];
}
function submit(): void {
  if (!props.actionable || props.pending) return;
  const validation = validateQuestionDrafts(props.turn.questions, drafts.value);
  errors.value = validation.errors;
  if (validation.answers) emit('answer', props.turn.eventId, validation.answers);
}
</script>

<template>
  <section class="question-card" :class="`question-${state}`" aria-label="Agent 提问">
    <header>
      <strong>{{ statusLabel }}</strong>
      <span>{{ turn.questions.length }} 个问题</span>
    </header>
    <form @submit.prevent="submit">
      <fieldset v-for="(question, index) in turn.questions" :key="question.id"
        :disabled="!actionable || pending" :aria-describedby="errors[question.id] ? `${turn.eventId}-${question.id}-error` : undefined">
        <legend>
          <small v-if="question.header">{{ question.header }}</small>
          <span>{{ index + 1 }}. {{ question.question }}</span>
        </legend>
        <div v-if="question.options" class="question-options">
          <label v-for="option in question.options" :key="option.label" class="question-option">
            <input :type="question.multiSelect ? 'checkbox' : 'radio'" :name="`${turn.eventId}-${question.id}`"
              :value="option.label" :checked="selected(question).includes(option.label)"
              @change="choose(question, option.label, ($event.target as HTMLInputElement).checked)" />
            <span><b>{{ option.label }}</b><small v-if="option.description">{{ option.description }}</small></span>
          </label>
        </div>
        <label class="question-custom">
          <span>{{ question.options ? '自由输入（可选）' : '回答' }}</span>
          <textarea :value="custom(question)" rows="3"
            :placeholder="question.options ? '也可以填写其他回答' : '请输入回答'"
            @input="writeCustom(question, $event)" />
        </label>
        <p v-if="errors[question.id]" :id="`${turn.eventId}-${question.id}-error`" class="question-error" role="alert">
          {{ errors[question.id] }}
        </p>
      </fieldset>
      <button v-if="actionable" class="primary-button question-submit" type="submit" :disabled="pending">
        {{ pending ? '正在提交…' : '提交全部回答' }}
      </button>
    </form>
    <p v-if="state === 'recovering'" class="event-notice">问题或历史尚未完整对账，暂不能提交。</p>
    <p v-else-if="turn.lifecycle === 'stopping' && !turn.answer" class="event-notice">正在停止，不能再回答这批问题。</p>
  </section>
</template>
