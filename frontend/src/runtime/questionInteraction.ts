import type { AgentQuestionPayload, QuestionAnswer, RuntimeQuestion, UserEvent } from '../types/runtime';
import { RuntimeApiError } from '../types/runtime';
import { javaStrip } from './protocol';

export interface QuestionDraft {
  selected: string[];
  custom: string;
}

export type QuestionDrafts = Record<string, QuestionDraft>;

export interface QuestionDraftValidation {
  answers?: QuestionAnswer[];
  errors: Record<string, string>;
}

export interface SingleQuestionDraftValidation {
  answer?: QuestionAnswer;
  error?: string;
}

export const BUSINESS_CONFIRMATION_ANSWER_ID = 'confirmation';

export function emptyQuestionDrafts(questions: RuntimeQuestion[]): QuestionDrafts {
  const drafts = Object.create(null) as QuestionDrafts;
  for (const question of questions) drafts[question.id] = { selected: [], custom: '' };
  return drafts;
}

export function validateQuestionDrafts(questions: RuntimeQuestion[], drafts: QuestionDrafts): QuestionDraftValidation {
  const errors = Object.create(null) as Record<string, string>;
  const answers = questions.map((question) => normalizeAnswer(question, drafts[question.id], errors));
  return Object.keys(errors).length ? { errors } : { answers, errors };
}

export function validateQuestionDraft(question: RuntimeQuestion, draft: QuestionDraft | undefined): SingleQuestionDraftValidation {
  const errors = Object.create(null) as Record<string, string>;
  const answer = normalizeAnswer(question, draft, errors);
  return errors[question.id] ? { error: errors[question.id] } : { answer };
}

function normalizeAnswer(question: RuntimeQuestion, draft: QuestionDraft | undefined,
  errors: Record<string, string>): QuestionAnswer {
  const custom = javaStrip(draft?.custom ?? '');
  const selectedSet = new Set(draft?.selected ?? []);
  const options = question.options ?? [];
  const selected = options.filter((option) => selectedSet.has(option.label)).map((option) => option.label);
  if (selected.length !== selectedSet.size) errors[question.id] = '选项已变化，请重新选择。';
  else if (custom.length > 4096) errors[question.id] = '自由输入最多 4096 个字符。';
  else if (!options.length && !custom) errors[question.id] = '请输入回答。';
  else if (!question.multiSelect && selected.length && custom) errors[question.id] = '单选题请选择一个选项或填写自由输入。';
  else if (!question.multiSelect && selected.length > 1) errors[question.id] = '单选题只能选择一个选项。';
  else if (!selected.length && !custom) errors[question.id] = '请选择选项或填写自由输入。';
  return { id: question.id, selected, ...(custom ? { custom } : {}) };
}

export function questionAnswerEvent(targetEventId: string, answers: QuestionAnswer[]): UserEvent {
  return { type: 'user.question_answer', targetEventId, answers: structuredClone(answers) };
}

export function normalizeQuestionAnswers(questions: RuntimeQuestion[], answers: QuestionAnswer[]): QuestionAnswer[] {
  const byId = new Map(answers.map((answer) => [answer.id, answer]));
  if (answers.length !== questions.length || byId.size !== answers.length
    || answers.some((answer) => !Array.isArray(answer.selected)
      || new Set(answer.selected).size !== answer.selected.length
      || !questions.some((question) => question.id === answer.id))) {
    throw invalidQuestionAnswer();
  }
  const drafts = Object.create(null) as QuestionDrafts;
  questions.forEach((question) => {
    const answer = byId.get(question.id)!;
    drafts[question.id] = { selected: [...answer.selected], custom: answer.custom ?? '' };
  });
  const validation = validateQuestionDrafts(questions, drafts);
  if (!validation.answers) throw invalidQuestionAnswer();
  return validation.answers;
}

export function validateBusinessConfirmationDraft(draft: QuestionDraft | undefined): SingleQuestionDraftValidation {
  const custom = javaStrip(draft?.custom ?? '');
  if (custom.length > 4096) return { error: '回答最多 4096 个字符。' };
  if (!custom) return { error: '请输入回答。' };
  return { answer: { id: BUSINESS_CONFIRMATION_ANSWER_ID, selected: [], custom } };
}

export function normalizeQuestionEventAnswers(payload: AgentQuestionPayload, answers: QuestionAnswer[]): QuestionAnswer[] {
  if ('questions' in payload && payload.questions) return normalizeQuestionAnswers(payload.questions, answers);
  if (answers.length !== 1) throw invalidQuestionAnswer();
  const answer = answers[0];
  if (answer.id !== BUSINESS_CONFIRMATION_ANSWER_ID || !Array.isArray(answer.selected) || answer.selected.length) {
    throw invalidQuestionAnswer();
  }
  const validation = validateBusinessConfirmationDraft({ selected: [], custom: answer.custom ?? '' });
  if (!validation.answer) throw invalidQuestionAnswer();
  return [validation.answer];
}

export function copyQuestionDrafts(drafts: QuestionDrafts): QuestionDrafts {
  const copy = Object.create(null) as QuestionDrafts;
  for (const [id, draft] of Object.entries(drafts)) {
    copy[id] = { selected: [...draft.selected], custom: draft.custom };
  }
  return copy;
}

/** 页面生命周期内按 Session 和问题事件隔离草稿，不把回答正文写入恢复索引。 */
export class QuestionDraftStore {
  private readonly drafts = new Map<string, QuestionDrafts>();

  private readonly indexes = new Map<string, number>();

  read(sessionId: string, eventId: string, questions: RuntimeQuestion[]): QuestionDrafts {
    const saved = this.drafts.get(this.key(sessionId, eventId));
    const current = emptyQuestionDrafts(questions);
    for (const question of questions) {
      if (saved?.[question.id]) current[question.id] = copyQuestionDraft(saved[question.id]);
    }
    return current;
  }

  write(sessionId: string, eventId: string, drafts: QuestionDrafts): void {
    this.drafts.set(this.key(sessionId, eventId), copyQuestionDrafts(drafts));
  }

  readIndex(sessionId: string, eventId: string, questionCount: number): number {
    const saved = this.indexes.get(this.key(sessionId, eventId)) ?? 0;
    return Math.max(0, Math.min(saved, Math.max(0, questionCount - 1)));
  }

  writeIndex(sessionId: string, eventId: string, index: number, questionCount: number): void {
    const normalized = Math.max(0, Math.min(Math.trunc(index), Math.max(0, questionCount - 1)));
    this.indexes.set(this.key(sessionId, eventId), normalized);
  }

  clear(sessionId: string, eventId: string): void {
    const key = this.key(sessionId, eventId);
    this.drafts.delete(key);
    this.indexes.delete(key);
  }

  private key(sessionId: string, eventId: string): string {
    return JSON.stringify([sessionId, eventId]);
  }
}

export function summarizeQuestionAnswers(questions: RuntimeQuestion[], answers: QuestionAnswer[], maxLength = 180): string {
  const byId = new Map(answers.map((answer) => [answer.id, answer]));
  const summary = questions.map((question) => {
    const answer = byId.get(question.id);
    const values = [...(answer?.selected ?? []), ...(answer?.custom ? [answer.custom] : [])];
    return `${question.header || question.question}：${values.join('、')}`;
  }).join('；');
  return summary.length > maxLength ? `${summary.slice(0, Math.max(0, maxLength - 1))}…` : summary;
}

export function displayedCustomAnswer(answer: QuestionAnswer | undefined, draft: QuestionDraft | undefined): string {
  return answer ? answer.custom ?? '' : draft?.custom ?? '';
}

function copyQuestionDraft(draft: QuestionDraft): QuestionDraft {
  return { selected: [...draft.selected], custom: draft.custom };
}

function invalidQuestionAnswer(): RuntimeApiError {
  return new RuntimeApiError({ code: 'QUESTION_ANSWER_INVALID', message: '回答不完整或不符合当前问题，请重新核对。' });
}
