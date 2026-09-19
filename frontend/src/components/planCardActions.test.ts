import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import PlanCard from './PlanCard.vue';

describe('PlanCard actions', () => {
  it('uses concise plan actions and keeps controls visible but disabled while submitting', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(PlanCard, {
      plan: { key: 'plan', kind: 'plan', eventId: 'plan', sourceEventId: 'root', rawMarkdown: '# 计划',
        streaming: false, unconfirmed: false },
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
        streaming: false, unconfirmed: false, decision: 'revise' },
      actionable: false,
      pending: false,
      sessionId: 'session',
    }) }));

    expect(html).toContain('已退回修改');
    expect(html).toContain('旧计划');
    expect(html).not.toContain('执行计划');
    expect(html).not.toContain('提交修改意见');
  });

  it('renders an interrupted preview as read-only and never exposes review actions', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(PlanCard, {
      plan: { key: 'draft', kind: 'plan', eventId: 'draft', sourceEventId: 'root', rawMarkdown: '# 草稿',
        streaming: false, unconfirmed: true, lifecycle: 'stopped' },
      actionable: false,
      pending: false,
      sessionId: 'session',
    }) }));

    expect(html).toContain('未提交审核');
    expect(html).toContain('计划未完整提交审核，不能批准。');
    expect(html).not.toContain('执行计划');
    expect(html).not.toContain('修改计划');
  });

  it('distinguishes a live draft from a disconnected draft', async () => {
    const render = (streaming: boolean) => renderToString(createSSRApp({ render: () => h(PlanCard, {
      plan: { key: 'draft', kind: 'plan', eventId: 'draft', sourceEventId: 'root', rawMarkdown: '# 草稿',
        streaming, unconfirmed: true },
      actionable: false,
      pending: false,
      sessionId: 'session',
    }) }));

    expect(await render(true)).toContain('计划生成中');
    expect(await render(false)).toContain('待核对');
  });
});
