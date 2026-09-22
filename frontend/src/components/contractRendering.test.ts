import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import CommandResultRegion from './CommandResultRegion.vue';
import ToolActivity from './ToolActivity.vue';
import PlanCard from './PlanCard.vue';
import QuestionCard from './QuestionCard.vue';
import AppSidebar from './AppSidebar.vue';
import { decodeCommandResult, freezeInvocation } from '../runtime/commands';

describe('literal command and tool content', () => {
  it('renders hostile Help as text, never active HTML, links or remote images', async () => {
    const result = decodeCommandResult(freezeInvocation({ name: 'help', kind: 'builtin', description: '' }, '/help'), {
      displayName: '<script>window.bad=true</script>', description: ['![remote](https://invalid.test/x.png)'], useCases: ['<img src=x onerror=bad()>'],
    });
    const html = await renderToString(createSSRApp({ render: () => h(CommandResultRegion, { result, expanded: true }) }));
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('![remote]');
    expect(html).not.toMatch(/<(script|img|a)\b/u);
    expect(html).toContain('仅当前页面');
  });
  it.each([
    { description: [], useCases: [], notice: '维护者尚未填写 Agent 介绍和使用场景', absent: ['暂无介绍。', '暂无使用场景。'] },
    { description: [], useCases: ['排查连接异常'], notice: '暂无介绍。', absent: ['维护者尚未填写', '暂无使用场景。'] },
    { description: ['网络分析助手'], useCases: [], notice: '暂无使用场景。', absent: ['维护者尚未填写', '暂无介绍。'] },
  ])('shows the Help empty state for description=$description and useCases=$useCases', async ({ description, useCases, notice, absent }) => {
    const result = decodeCommandResult(freezeInvocation({ name: 'help', kind: 'builtin', description: '' }, '/help'), {
      displayName: 'Agent', description, useCases,
    });
    const html = await renderToString(createSSRApp({ render: () => h(CommandResultRegion, { result, expanded: true }) }));
    expect(html).toContain(notice);
    for (const text of absent) expect(html).not.toContain(text);
    for (const text of [...description, ...useCases]) expect(html).toContain(text);
  });
  it('renders authored Help use cases with line-preserving text styling', async () => {
    const result = decodeCommandResult(freezeInvocation({ name: 'help', kind: 'builtin', description: '' }, '/help'), {
      displayName: 'Agent', description: ['介绍'], useCases: ['先检查连接\n再检查配置'],
    });
    const html = await renderToString(createSSRApp({ render: () => h(CommandResultRegion, { result, expanded: true }) }));
    expect(html).toContain('<li class="plain-result">先检查连接\n再检查配置</li>');
  });
  it('does not render Markdown actions or silently truncate tool results', async () => {
    const result = '[run](https://invalid.test)\n'.repeat(200);
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool', kind: 'activity', toolCallId: 't', toolName: 'Read', status: 'completed', arguments: [], result,
    } }) }));
    expect(html).toContain(result);
    expect(html).not.toContain('<a ');
    expect(html).toContain('tabindex="0"');
  });
  it('uses a distinct plan-copy action and keeps question protocol IDs out of DOM identities', async () => {
    const plan = await renderToString(createSSRApp({ render: () => h(PlanCard, { plan: {
      key: 'plan', kind: 'plan', eventId: 'plan-event', sourceEventId: 'root', rawMarkdown: '# 完整方案',
      streaming: false, unconfirmed: false,
    }, actionable: false, pending: false, sessionId: 'session' }) }));
    expect(plan).toContain('复制计划');
    expect(plan).toContain('完整方案');
    const question = await renderToString(createSSRApp({ render: () => h(QuestionCard, { turn: {
      key: 'question', kind: 'question', eventId: 'unsafe-event-id', sourceEventId: 'root', questions: [{
        id: 'unsafe-question-id', question: '请选择', options: [{ label: 'A' }], multiSelect: false,
      }], answer: [{ id: 'unsafe-question-id', selected: ['A'] }],
    }, sessionId: 'session', actionable: false, pending: false }) }));
    expect(question).toContain('已回答');
    expect(question).toContain('aria-expanded="false"');
    expect(question).not.toContain('unsafe-event-id');
    expect(question).not.toMatch(/(?:id|name)="[^"]*unsafe-question-id/u);
  });
  it('renders searchable pinned and recent local session sections with direct actions', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(AppSidebar, { threads: [{
      sessionId: 'a', title: '置顶会话', agentName: 'Agent', lastActivityAt: '2026-09-10T00:00:00Z', pinned: true,
    }, { sessionId: 'b', title: '最近会话', agentName: 'Agent', lastActivityAt: '2026-09-11T00:00:00Z', pinned: false }],
    compact: false, renameThread: async () => undefined, togglePinned: () => undefined }) }));
    expect(html).toContain('搜索会话');
    expect(html).toContain('置顶');
    expect(html).toContain('最近');
    expect(html).toContain('会话操作');
  });
});
