import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import PlanCard from './PlanCard.vue';

describe('PlanCard actions', () => {
  it('uses concise plan actions and keeps controls visible but disabled while submitting', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(PlanCard, {
      plan: { key: 'plan', kind: 'plan', eventId: 'plan', sourceEventId: 'root', rawMarkdown: '# 计划' },
      actionable: false,
      pending: true,
      sessionId: 'session',
    }) }));

    expect(html).toContain('提交中');
    expect(html).toContain('执行计划');
    expect(html).toContain('修改计划');
    expect(html).toContain('复制计划');
    expect(html.match(/disabled/gu)).toHaveLength(2);
  });

  it('keeps a revised historical plan readable without review actions', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(PlanCard, {
      plan: { key: 'plan', kind: 'plan', eventId: 'plan', sourceEventId: 'root', rawMarkdown: '# 旧计划',
        decision: 'revise' },
      actionable: false,
      pending: false,
      sessionId: 'session',
    }) }));

    expect(html).toContain('已退回修改');
    expect(html).toContain('旧计划');
    expect(html).not.toContain('执行计划');
    expect(html).not.toContain('提交修改意见');
  });
});
