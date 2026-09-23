<script lang="ts">
import { QuestionDraftStore } from '../runtime/questionInteraction';

const questionDraftStore = new QuestionDraftStore();
</script>

<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue';
import {
  copyQuestionDrafts,
  displayedCustomAnswer,
  BUSINESS_CONFIRMATION_ANSWER_ID,
  summarizeQuestionAnswers,
  validateQuestionDraft,
  validateQuestionDrafts,
  validateBusinessConfirmationDraft,
} from '../runtime/questionInteraction';
import type { QuestionDrafts } from '../runtime/questionInteraction';
import { javaStrip } from '../runtime/protocol';
import type { QuestionAnswer, RuntimeQuestion } from '../types/runtime';
import type { BusinessConfirmationQuestionTurn, QuestionTurn } from '../types/product';

const props = defineProps<{
  turn: QuestionTurn;
  sessionId: string;
  actionable: boolean;
  pending: boolean;
}>();
const emit = defineEmits<{ answer: [eventId: string, answers: QuestionAnswer[]] }>();
const confirmationDraftQuestion: RuntimeQuestion = {
  id: BUSINESS_CONFIRMATION_ANSWER_ID,
  question: '用户确认',
  multiSelect: false,
};
const draftQuestions = (turn: QuestionTurn): RuntimeQuestion[] => turn.questions ?? [confirmationDraftQuestion];
const drafts = ref<QuestionDrafts>(questionDraftStore.read(props.sessionId, props.turn.eventId, draftQuestions(props.turn)));
const currentIndex = ref(questionDraftStore.readIndex(props.sessionId, props.turn.eventId, draftQuestions(props.turn).length));
const errors = ref<Record<string, string>>(Object.create(null) as Record<string, string>);
const root = ref<HTMLElement | null>(null);
const toggle = ref<HTMLButtonElement | null>(null);
const expanded = ref(!props.turn.answer);
const announcement = ref('');
const localSubmitting = ref(false);
const domId = 'question-card-' + useId();
let submittedHere = false;

const isBusinessConfirmationTurn = (turn: QuestionTurn): turn is BusinessConfirmationQuestionTurn => typeof turn.label === 'string';
const isBusinessConfirmation = computed(() => isBusinessConfirmationTurn(props.turn));
const businessConfirmationTurn = computed(() => isBusinessConfirmationTurn(props.turn) ? props.turn : undefined);
const questions = computed<RuntimeQuestion[]>(() => props.turn.questions ?? []);
const pendingNow = computed(() => props.pending || localSubmitting.value);
const state = computed(() => {
  if (props.turn.answer) return 'answered';
  if (pendingNow.value) return 'submitting';
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
const currentQuestion = computed(() => questions.value[currentIndex.value]);
const cardTitle = computed(() => props.turn.answer ? '已回答'
  : isBusinessConfirmation.value ? '用户确认' : currentQuestion.value?.header || '等待回答');
const progressLabel = computed(() => {
  if (props.turn.answer) return isBusinessConfirmation.value ? statusLabel.value : `${questions.value.length} 个回答`;
  if (questions.value.length > 1) return `${currentIndex.value + 1} / ${questions.value.length}`;
  return statusLabel.value;
});
const displayedQuestions = computed(() => props.turn.answer
  ? questions.value.map((question, index) => ({ question, index }))
  : currentQuestion.value ? [{ question: currentQuestion.value, index: currentIndex.value }] : []);
const confirmationAnswer = computed(() => props.turn.answer?.find((answer) => answer.id === BUSINESS_CONFIRMATION_ANSWER_ID));
const answerSummary = computed(() => isBusinessConfirmation.value
  ? confirmationAnswer.value?.custom ?? '' : summarizeQuestionAnswers(questions.value, props.turn.answer ?? []));
const contextText = computed(() => businessConfirmationTurn.value?.context !== undefined
  ? JSON.stringify(businessConfirmationTurn.value.context, null, 2) : '');
const showControls = computed(() => (props.actionable || pendingNow.value) && !props.turn.answer);

watch(drafts, (value) => {
  questionDraftStore.write(props.sessionId, props.turn.eventId, copyQuestionDrafts(value));
}, { deep: true });
watch(currentIndex, (value) => {
  questionDraftStore.writeIndex(props.sessionId, props.turn.eventId, value, questions.value.length);
});
watch(() => [props.sessionId, props.turn.eventId] as const, () => {
  const currentQuestions = draftQuestions(props.turn);
  drafts.value = questionDraftStore.read(props.sessionId, props.turn.eventId, currentQuestions);
  currentIndex.value = questionDraftStore.readIndex(props.sessionId, props.turn.eventId, currentQuestions.length);
  errors.value = Object.create(null) as Record<string, string>;
  localSubmitting.value = false;
  expanded.value = !props.turn.answer;
});
watch(() => props.pending, async (pending, previous) => {
  if (pending && !previous && submittedHere) { await nextTick(); toggle.value?.focus(); }
  if (!pending && previous && !props.turn.answer) { submittedHere = false; localSubmitting.value = false; }
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
  if (!draft || !props.actionable || pendingNow.value) return;
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
  if (!draft || !props.actionable || pendingNow.value) return;
  draft.custom = (event.target as HTMLTextAreaElement).value;
  if (!question.multiSelect && javaStrip(draft.custom)) draft.selected = [];
  delete errors.value[question.id];
}
function writeBusinessConfirmationCustom(event: Event): void {
  const draft = drafts.value[BUSINESS_CONFIRMATION_ANSWER_ID];
  if (!draft || !props.actionable || pendingNow.value) return;
  draft.custom = (event.target as HTMLTextAreaElement).value;
  delete errors.value[BUSINESS_CONFIRMATION_ANSWER_ID];
}
function inputId(questionIndex: number, optionIndex?: number): string {
  return `${domId}-question-${questionIndex}-${optionIndex === undefined ? 'custom' : `option-${optionIndex}`}`;
}
function errorId(questionIndex: number): string { return `${domId}-question-${questionIndex}-error`; }
async function previousQuestion(): Promise<void> {
  if (!props.actionable || pendingNow.value || currentIndex.value === 0) return;
  currentIndex.value -= 1;
  await focusQuestionTitle();
}
async function nextQuestion(): Promise<void> {
  const question = currentQuestion.value;
  if (!question || !props.actionable || pendingNow.value || currentIndex.value >= questions.value.length - 1) return;
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
  if (!props.actionable || pendingNow.value) return;
  if (isBusinessConfirmation.value) {
    const validation = validateBusinessConfirmationDraft(drafts.value[BUSINESS_CONFIRMATION_ANSWER_ID]);
    errors.value = validation.error ? { [BUSINESS_CONFIRMATION_ANSWER_ID]: validation.error } : {};
    if (!validation.answer) { await focusQuestionInput(0); return; }
    submittedHere = true;
    localSubmitting.value = true;
    emit('answer', props.turn.eventId, [validation.answer]);
    await nextTick();
    if (!props.pending && !props.turn.answer) localSubmitting.value = false;
    return;
  }
  const validation = validateQuestionDrafts(questions.value, drafts.value);
  errors.value = validation.errors;
  if (validation.answers) {
    submittedHere = true;
    localSubmitting.value = true;
    emit('answer', props.turn.eventId, validation.answers);
    await nextTick();
    if (!props.pending && !props.turn.answer) localSubmitting.value = false;
    return;
  }
  const firstError = questions.value.findIndex((question) => Boolean(validation.errors[question.id]));
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
  if (isBusinessConfirmation.value || !expanded.value || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  event.stopPropagation();
  expanded.value = false;
  void nextTick(() => toggle.value?.focus());
}
</script>

<template>
  <section ref="root" class="question-card" :class="`question-${state}`" aria-label="Agent 提问" @keydown.esc="collapseOnEscape">
    <header>
      <div v-if="isBusinessConfirmation" class="question-toggle business-confirmation-heading">
        <span class="question-heading">
          <strong>{{ cardTitle }}</strong>
          <small v-if="cardTitle !== statusLabel">{{ statusLabel }}</small>
        </span>
      </div>
      <button v-else ref="toggle" type="button" class="question-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        <span class="question-heading"><strong>{{ cardTitle }}</strong><small v-if="cardTitle !== statusLabel">{{ statusLabel }}</small></span>
        <span>{{ progressLabel }} · {{ expanded ? '收起' : '展开' }}</span>
      </button>
    </header>
    <p v-if="turn.answer && !expanded" class="question-summary">{{ answerSummary }}</p>
    <form v-if="expanded" @submit.prevent="submit">
      <fieldset v-if="isBusinessConfirmation" class="business-confirmation"
        :disabled="!actionable || pendingNow || Boolean(turn.answer)"
        :aria-describedby="errors[BUSINESS_CONFIRMATION_ANSWER_ID] ? errorId(0) : undefined">
        <legend data-question-title="0" tabindex="-1">
          <span>{{ businessConfirmationTurn && businessConfirmationTurn.label.length ? businessConfirmationTurn.label : '用户确认' }}</span>
        </legend>
        <pre v-if="businessConfirmationTurn?.context !== undefined" class="business-confirmation-context" aria-label="确认上下文">{{ contextText }}</pre>
        <label class="question-custom">
          <span>你的回答</span>
          <textarea :id="inputId(0)" data-question-input="0"
            :value="confirmationAnswer?.custom ?? drafts[BUSINESS_CONFIRMATION_ANSWER_ID]?.custom ?? ''" rows="4"
            placeholder="请输入回答" @input="writeBusinessConfirmationCustom" />
        </label>
        <p v-if="errors[BUSINESS_CONFIRMATION_ANSWER_ID]" :id="errorId(0)" class="question-error" role="alert">
          {{ errors[BUSINESS_CONFIRMATION_ANSWER_ID] }}
        </p>
      </fieldset>
      <template v-else>
        <fieldset v-for="entry in displayedQuestions" :key="entry.question.id"
          :disabled="!actionable || pendingNow" :aria-describedby="errors[entry.question.id] ? errorId(entry.index) : undefined">
          <legend :data-question-title="entry.index" tabindex="-1">
            <small v-if="turn.answer && entry.question.header">{{ entry.question.header }}</small>
            <span>{{ turn.answer && questions.length > 1 ? `${entry.index + 1}. ` : '' }}{{ entry.question.question }}</span>
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
      </template>
      <button v-if="showControls && isBusinessConfirmation" class="primary-button question-submit" type="button"
        :disabled="pendingNow" @click="submit">
        {{ pendingNow ? '提交中' : '确认' }}
      </button>
      <div v-else-if="showControls && questions.length > 1" class="question-navigation">
        <button class="secondary-button" type="button" :disabled="pendingNow || currentIndex === 0" @click="previousQuestion">上一步</button>
        <button v-if="currentIndex < questions.length - 1" class="primary-button" type="button" :disabled="pendingNow" @click="nextQuestion">
          下一步
        </button>
        <button v-else class="primary-button" type="submit" :disabled="pendingNow">
          {{ pendingNow ? '提交中' : '提交回答' }}
        </button>
      </div>
      <button v-else-if="showControls" class="primary-button question-submit" type="submit" :disabled="pendingNow">
        {{ pendingNow ? '提交中' : '提交回答' }}
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
.business-confirmation legend span {
  white-space: pre-wrap;
}
.business-confirmation-heading {
  cursor: default;
}
.business-confirmation-context {
  max-height: 18rem;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  margin: 0;
  background: var(--surface-primary);
  color: var(--text-primary);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
