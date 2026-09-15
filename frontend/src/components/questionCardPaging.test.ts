import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import QuestionCard from './QuestionCard.vue';
import type { QuestionTurn } from '../types/product';

const turn: QuestionTurn = {
  key: 'question',
  kind: 'question',
  eventId: 'question-event',
  sourceEventId: 'root',
  questions: [
    { id: 'scope', header: '实施范围', question: '先实施哪个区域？', options: [{ label: '东区' }], multiSelect: false },
    { id: 'time', header: '实施时间', question: '何时实施？', multiSelect: false },
  ],
};

describe('QuestionCard paging', () => {
  it('shows only the current question and local navigation for a multi-question batch', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(QuestionCard, {
      turn, sessionId: 'session', actionable: true, pending: false,
    }) }));

    expect(html).toContain('实施范围');
    expect(html).toContain('先实施哪个区域？');
    expect(html).toContain('1 / 2');
    expect(html).toContain('下一步');
    expect(html).toContain('data-question-title="0" tabindex="-1"');
    expect(html).not.toContain('何时实施？');
    expect(html).not.toContain('提交全部回答');
  });

  it('hides pagination for one question and keeps recovery read-only', async () => {
    const single = { ...turn, eventId: 'single', questions: [turn.questions[0]] };
    const active = await renderToString(createSSRApp({ render: () => h(QuestionCard, {
      turn: single, sessionId: 'session', actionable: true, pending: false,
    }) }));
    const recovering = await renderToString(createSSRApp({ render: () => h(QuestionCard, {
      turn, sessionId: 'session', actionable: false, pending: false,
    }) }));

    expect(active).toContain('提交回答');
    expect(active).not.toContain('1 / 1');
    expect(active).not.toContain('下一步');
    expect(recovering).toContain('同步中');
    expect(recovering).toContain('暂不能回答');
    expect(recovering).not.toContain('下一步');
  });

  it('shows a short semantic answer summary without JSON', async () => {
    const answered = { ...turn, answer: [{ id: 'scope', selected: ['东区'] }, { id: 'time', selected: [], custom: '22:00' }] };
    const html = await renderToString(createSSRApp({ render: () => h(QuestionCard, {
      turn: answered, sessionId: 'session', actionable: false, pending: false,
    }) }));

    expect(html).toContain('已回答');
    expect(html).toContain('实施范围：东区；实施时间：22:00');
    expect(html).not.toContain('&quot;selected&quot;');
    expect(html).not.toContain('提交回答');
  });
});
