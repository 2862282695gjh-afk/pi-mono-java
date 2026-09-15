import type { QuestionAnswer, RuntimeQuestion, UserEvent } from '../types/runtime';
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

  read(sessionId: string, eventId: string, questions: RuntimeQuestion[]): QuestionDrafts {
    return copyQuestionDrafts(this.drafts.get(this.key(sessionId, eventId)) ?? emptyQuestionDrafts(questions));
  }

  write(sessionId: string, eventId: string, drafts: QuestionDrafts): void {
    this.drafts.set(this.key(sessionId, eventId), copyQuestionDrafts(drafts));
  }

  private key(sessionId: string, eventId: string): string {
    return JSON.stringify([sessionId, eventId]);
  }
}

export function displayedCustomAnswer(answer: QuestionAnswer | undefined, draft: QuestionDraft | undefined): string {
  return answer ? answer.custom ?? '' : draft?.custom ?? '';
}

function invalidQuestionAnswer(): RuntimeApiError {
  return new RuntimeApiError({ code: 'QUESTION_ANSWER_INVALID', message: '回答不完整或不符合当前问题，请重新核对。' });
}
