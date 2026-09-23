import { describe, expect, it } from 'vitest';
import { reactive } from 'vue';
import { RuntimeApiError } from '../types/runtime';
import { BUSINESS_CONFIRMATION_ANSWER_ID, displayedCustomAnswer, emptyQuestionDrafts, normalizeQuestionAnswers,
  normalizeQuestionEventAnswers, QuestionDraftStore, questionAnswerEvent, summarizeQuestionAnswers,
  validateBusinessConfirmationDraft, validateQuestionDraft, validateQuestionDrafts } from './questionInteraction';
import type { RuntimeQuestion } from '../types/runtime';

const questions: RuntimeQuestion[] = [
  { id: 'single', question: '单选', options: [{ label: 'A' }, { label: 'B' }], multiSelect: false },
  { id: 'multi', question: '多选', options: [{ label: 'X' }, { label: 'Y' }], multiSelect: true },
  { id: 'text', question: '文本', multiSelect: false },
];

describe('question answer snapshots', () => {
  it('normalizes by question and option order with Java strip', () => {
    const drafts = emptyQuestionDrafts(questions);
    drafts.single.selected = ['B'];
    drafts.multi.selected = ['Y', 'X'];
    drafts.multi.custom = ' 补充 ';
    drafts.text.custom = '\u3000正文\u3000';
    expect(validateQuestionDrafts(questions, drafts)).toEqual({ errors: {}, answers: [
      { id: 'single', selected: ['B'] },
      { id: 'multi', selected: ['X', 'Y'], custom: '补充' },
      { id: 'text', selected: [], custom: '正文' },
    ] });
  });

  it('reports each incomplete or conflicting question without a partial answer', () => {
    const drafts = emptyQuestionDrafts(questions);
    drafts.single.selected = ['A']; drafts.single.custom = '其他';
    expect(validateQuestionDrafts(questions, drafts)).toEqual({ errors: {
      single: '单选题请选择一个选项或填写自由输入。',
      multi: '请选择选项或填写自由输入。',
      text: '请输入回答。',
    } });
  });

  it('freezes a canonical event and rejects duplicate or unknown answer identities', () => {
    const answers = [
      { id: 'single', selected: ['A'] }, { id: 'multi', selected: ['Y'] }, { id: 'text', selected: [], custom: '文本' },
    ];
    expect(questionAnswerEvent('question-a', normalizeQuestionAnswers(questions, answers))).toEqual({
      type: 'user.question_answer', targetEventId: 'question-a', answers,
    });
    expect(() => normalizeQuestionAnswers(questions, [answers[0], answers[0], answers[2]])).toThrow(RuntimeApiError);
    expect(() => normalizeQuestionAnswers(questions, [{ ...answers[0], id: 'unknown' }, answers[1], answers[2]])).toThrow(RuntimeApiError);
  });
  it('builds the fixed business confirmation answer slot and enforces blank and UTF-16 limits', () => {
    expect(validateBusinessConfirmationDraft({ selected: [], custom: ' 继续执行\n' })).toEqual({
      answer: { id: BUSINESS_CONFIRMATION_ANSWER_ID, selected: [], custom: '继续执行' },
    });
    expect(validateBusinessConfirmationDraft({ selected: [], custom: ' \n\t' })).toEqual({ error: '请输入回答。' });
    expect(validateBusinessConfirmationDraft({ selected: [], custom: '😀'.repeat(2049) })).toEqual({
      error: '回答最多 4096 个字符。',
    });
    const payload = { label: 'driver_design_confirm', context: { nested: [null, true] } };
    expect(normalizeQuestionEventAnswers(payload, [{ id: 'confirmation', selected: [], custom: '\u3000确认\u3000' }]))
      .toEqual([{ id: 'confirmation', selected: [], custom: '确认' }]);
    expect(() => normalizeQuestionEventAnswers(payload, [{ id: 'driver_design_confirm', selected: [], custom: '确认' }]))
      .toThrow(RuntimeApiError);
    expect(() => normalizeQuestionEventAnswers(payload, [{ id: 'confirmation', selected: ['同意'], custom: '确认' }]))
      .toThrow(RuntimeApiError);
  });
  it('copies reactive drafts and isolates them by Session and exact question event', () => {
    const constructorQuestion: RuntimeQuestion[] = [{ id: 'constructor', question: '回答', multiSelect: false }];
    const store = new QuestionDraftStore();
    const draft = reactive(emptyQuestionDrafts(constructorQuestion));
    const errors = validateQuestionDrafts(constructorQuestion, draft).errors;
    expect(Object.hasOwn(errors, 'constructor')).toBe(true);
    expect(errors['constructor']).toBe('请输入回答。');
    draft['constructor'].custom = 'Session A 草稿';
    expect(() => store.write('session-a', 'question-a', draft)).not.toThrow();
    expect(store.read('session-a', 'question-a', constructorQuestion)['constructor'].custom).toBe('Session A 草稿');
    expect(store.read('session-b', 'question-a', constructorQuestion)['constructor'].custom).toBe('');
    expect(store.read('session-a', 'question-b', constructorQuestion)['constructor'].custom).toBe('');
    store.writeIndex('session-a', 'question-a', 2, 3);
    expect(store.readIndex('session-a', 'question-a', 3)).toBe(2);
    expect(store.readIndex('session-b', 'question-a', 3)).toBe(0);
    expect(store.readIndex('session-a', 'question-b', 3)).toBe(0);
    expect(store.readIndex('session-a', 'question-a', 1)).toBe(0);
  });
  it('does not display a stale local custom draft after another client answered by option', () => {
    expect(displayedCustomAnswer({ id: 'scope', selected: ['A'] }, { selected: [], custom: '本地旧草稿' })).toBe('');
    expect(displayedCustomAnswer(undefined, { selected: [], custom: '本地旧草稿' })).toBe('本地旧草稿');
  });
  it('validates one page independently and summarizes authoritative answers in question order', () => {
    expect(validateQuestionDraft(questions[0], { selected: [], custom: '' })).toEqual({
      error: '请选择选项或填写自由输入。',
    });
    expect(validateQuestionDraft(questions[0], { selected: ['B'], custom: '' })).toEqual({
      answer: { id: 'single', selected: ['B'] },
    });
    const summary = summarizeQuestionAnswers(questions, [
      { id: 'text', selected: [], custom: '正文' },
      { id: 'single', selected: ['A'] },
      { id: 'multi', selected: ['X', 'Y'] },
    ]);
    expect(summary).toBe('单选：A；多选：X、Y；文本：正文');
    expect(summarizeQuestionAnswers(questions, [{ id: 'text', selected: [], custom: '长'.repeat(200) }], 20))
      .toHaveLength(20);
  });
});
