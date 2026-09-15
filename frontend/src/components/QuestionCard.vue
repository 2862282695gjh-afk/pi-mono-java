<script lang="ts">
import { QuestionDraftStore } from '../runtime/questionInteraction';

const questionDraftStore = new QuestionDraftStore();
</script>

<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue';
import {
  copyQuestionDrafts,
  displayedCustomAnswer,
  summarizeQuestionAnswers,
  validateQuestionDraft,
  validateQuestionDrafts,
} from '../runtime/questionInteraction';
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
const currentIndex = ref(questionDraftStore.readIndex(props.sessionId, props.turn.eventId, props.turn.questions.length));
const errors = ref<Record<string, string>>(Object.create(null) as Record<string, string>);
const root = ref<HTMLElement | null>(null);
const toggle = ref<HTMLButtonElement | null>(null);
const expanded = ref(!props.turn.answer);
const announcement = ref('');
const domId = 'question-card-' + useId();
let submittedHere = false;

const state = computed(() => {
  if (props.turn.answer) return 'answered';
  if (props.pending) return 'submitting';
  if (props.turn.lifecycle === 'closed' || props.turn.lifecycle === 'stopping') return 'closed';
  return props.actionable ? 'waiting' : 'recovering';
});
const statusLabel = computed(() => ({
  waiting: '等待回答',
  submitting: '提交中',
  answered: '已回答',
  closed: props.turn.lifecycle === 'stopping' ? '停止中' : '已关闭',
  recovering: '同步中',
})[state.value]);
const currentQuestion = computed(() => props.turn.questions[currentIndex.value]);
const cardTitle = computed(() => props.turn.answer ? '已回答' : currentQuestion.value?.header || '等待回答');
const progressLabel = computed(() => {
  if (props.turn.answer) return `${props.turn.questions.length} 个回答`;
  if (props.turn.questions.length > 1) return `${currentIndex.value + 1} / ${props.turn.questions.length}`;
  return statusLabel.value;
});
const displayedQuestions = computed(() => props.turn.answer
  ? props.turn.questions.map((question, index) => ({ question, index }))
  : currentQuestion.value ? [{ question: currentQuestion.value, index: currentIndex.value }] : []);
const answerSummary = computed(() => summarizeQuestionAnswers(props.turn.questions, props.turn.answer ?? []));
const showControls = computed(() => (props.actionable || props.pending) && !props.turn.answer);

watch(drafts, (value) => {
  questionDraftStore.write(props.sessionId, props.turn.eventId, copyQuestionDrafts(value));
}, { deep: true });
watch(currentIndex, (value) => {
  questionDraftStore.writeIndex(props.sessionId, props.turn.eventId, value, props.turn.questions.length);
});
watch(() => [props.sessionId, props.turn.eventId] as const, () => {
  drafts.value = questionDraftStore.read(props.sessionId, props.turn.eventId, props.turn.questions);
  currentIndex.value = questionDraftStore.readIndex(props.sessionId, props.turn.eventId, props.turn.questions.length);
  errors.value = Object.create(null) as Record<string, string>;
  expanded.value = !props.turn.answer;
});
watch(() => props.pending, async (pending, previous) => {
  if (pending && !previous && submittedHere) { await nextTick(); toggle.value?.focus(); }
  if (!pending && previous && !props.turn.answer) submittedHere = false;
});
watch(() => props.turn.answer, async (answer, previous) => {
  if (!answer || previous) return;
  const ownsFocus = submittedHere || (typeof document !== 'undefined' && !!root.value?.contains(document.activeElement));
  submittedHere = false;
  expanded.value = false;
  announcement.value = '回答已接受';
  if (ownsFocus) { await nextTick(); toggle.value?.focus(); }
});

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
function inputId(questionIndex: number, optionIndex?: number): string {
  return `${domId}-question-${questionIndex}-${optionIndex === undefined ? 'custom' : `option-${optionIndex}`}`;
}
function errorId(questionIndex: number): string { return `${domId}-question-${questionIndex}-error`; }
async function previousQuestion(): Promise<void> {
  if (!props.actionable || props.pending || currentIndex.value === 0) return;
  currentIndex.value -= 1;
  await focusQuestionTitle();
}
async function nextQuestion(): Promise<void> {
  const question = currentQuestion.value;
  if (!question || !props.actionable || props.pending || currentIndex.value >= props.turn.questions.length - 1) return;
  const validation = validateQuestionDraft(question, drafts.value[question.id]);
  if (validation.error) {
    errors.value[question.id] = validation.error;
    await focusQuestionInput(currentIndex.value);
    return;
  }
  delete errors.value[question.id];
  currentIndex.value += 1;
  await focusQuestionTitle();
}
async function submit(): Promise<void> {
  if (!props.actionable || props.pending) return;
  const validation = validateQuestionDrafts(props.turn.questions, drafts.value);
  errors.value = validation.errors;
  if (validation.answers) {
    submittedHere = true;
    emit('answer', props.turn.eventId, validation.answers);
    return;
  }
  const firstError = props.turn.questions.findIndex((question) => Boolean(validation.errors[question.id]));
  currentIndex.value = Math.max(0, firstError);
  await focusQuestionInput(currentIndex.value);
}
async function focusQuestionTitle(): Promise<void> {
  await nextTick();
  root.value?.querySelector<HTMLElement>(`[data-question-title="${currentIndex.value}"]`)?.focus();
}
async function focusQuestionInput(index: number): Promise<void> {
  await nextTick();
  root.value?.querySelector<HTMLElement>(`[data-question-input="${index}"]`)?.focus();
}
function collapseOnEscape(event: KeyboardEvent): void {
  if (!expanded.value || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  event.stopPropagation();
  expanded.value = false;
  void nextTick(() => toggle.value?.focus());
}
</script>

<template>
  <section ref="root" class="question-card" :class="`question-${state}`" aria-label="Agent 提问" @keydown.esc="collapseOnEscape">
    <header>
      <button ref="toggle" type="button" class="question-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        <span class="question-heading">
          <strong>{{ cardTitle }}</strong>
          <small v-if="cardTitle !== statusLabel">{{ statusLabel }}</small>
        </span>
        <span>{{ progressLabel }} · {{ expanded ? '收起' : '展开' }}</span>
      </button>
    </header>
    <p v-if="turn.answer && !expanded" class="question-summary">{{ answerSummary }}</p>
    <form v-if="expanded" @submit.prevent="submit">
      <fieldset v-for="entry in displayedQuestions" :key="entry.question.id"
        :disabled="!actionable || pending" :aria-describedby="errors[entry.question.id] ? errorId(entry.index) : undefined">
        <legend :data-question-title="entry.index" tabindex="-1">
          <small v-if="turn.answer && entry.question.header">{{ entry.question.header }}</small>
          <span>{{ turn.answer && turn.questions.length > 1 ? `${entry.index + 1}. ` : '' }}{{ entry.question.question }}</span>
        </legend>
        <div v-if="entry.question.options" class="question-options">
          <label v-for="(option, optionIndex) in entry.question.options" :key="option.label" class="question-option" :for="inputId(entry.index, optionIndex)">
            <input :id="inputId(entry.index, optionIndex)" :data-question-input="entry.index"
              :type="entry.question.multiSelect ? 'checkbox' : 'radio'" :name="`${domId}-question-${entry.index}`"
              :value="option.label" :checked="selected(entry.question).includes(option.label)"
              @change="choose(entry.question, option.label, ($event.target as HTMLInputElement).checked)" />
            <span><b>{{ option.label }}</b><small v-if="option.description">{{ option.description }}</small></span>
          </label>
        </div>
        <label class="question-custom">
          <span>{{ entry.question.options ? entry.question.multiSelect ? '补充回答（可选）' : '或填写自己的回答' : '你的回答' }}</span>
          <textarea :id="inputId(entry.index)" :data-question-input="entry.index" :value="custom(entry.question)" rows="3"
            :placeholder="entry.question.options ? '也可以填写其他回答' : '请输入回答'"
            @input="writeCustom(entry.question, $event)" />
        </label>
        <p v-if="errors[entry.question.id]" :id="errorId(entry.index)" class="question-error" role="alert">
          {{ errors[entry.question.id] }}
        </p>
      </fieldset>
      <div v-if="showControls && turn.questions.length > 1" class="question-navigation">
        <button class="secondary-button" type="button" :disabled="pending || currentIndex === 0" @click="previousQuestion">上一步</button>
        <button v-if="currentIndex < turn.questions.length - 1" class="primary-button" type="button" :disabled="pending" @click="nextQuestion">
          下一步
        </button>
        <button v-else class="primary-button" type="submit" :disabled="pending">
          {{ pending ? '提交中' : '提交回答' }}
        </button>
      </div>
      <button v-else-if="showControls" class="primary-button question-submit" type="submit" :disabled="pending">
        {{ pending ? '提交中' : '提交回答' }}
      </button>
    </form>
    <p v-if="state === 'recovering'" class="event-notice">问题尚未完整同步，暂不能回答。</p>
    <p v-else-if="turn.lifecycle === 'stopping' && !turn.answer" class="event-notice">停止中，不能再回答。</p>
    <span class="sr-only" aria-live="polite">{{ announcement }}</span>
  </section>
</template>

<style scoped>
.question-heading {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.question-heading strong {
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.question-heading small {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 500;
}
.question-navigation {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}
.question-navigation button {
  flex: 1 1 0;
}
</style>
